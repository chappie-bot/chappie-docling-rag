package org.chappie.bot.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.DockerDaemonImage;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.Platform;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import io.quarkiverse.docling.runtime.client.DoclingService;
import jakarta.inject.Inject;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI command to build a pgvector Docker image with Quarkus documentation embeddings
 * using Docling for document processing.
 */
@Command(
    name = "bake-image",
    mixinStandardHelpOptions = true,
    description = "Fetch Quarkus documentation, process with Docling, ingest into pgvector, and bake a Docker image."
)
public class BakeImageCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger(BakeImageCommand.class);
    private static final String DB_NAME = "postgres";
    private static final String DOCLING_IMAGE = "ghcr.io/docling-project/docling-serve:v1.10.0";

    @Inject
    DoclingService doclingService;

    @Option(names = "--quarkus-version", required = true,
            description = "Target Quarkus version (e.g., 3.30.6)")
    String quarkusVersion;

    @Option(names = "--chunk-size", defaultValue = "1000",
            description = "Splitter chunk size (default: ${DEFAULT-VALUE})")
    int chunkSize;

    @Option(names = "--chunk-overlap", defaultValue = "300",
            description = "Splitter chunk overlap (default: ${DEFAULT-VALUE})")
    int chunkOverlap;

    @Option(names = "--semantic",
            description = "Use semantic chunking (split by Markdown headers) instead of fixed-size chunks")
    boolean semanticChunking;

    @Option(names = "--push",
            description = "Push to remote registry instead of loading to local Docker daemon")
    boolean push;

    @Option(names = "--registry-username",
            description = "Registry username (used only with --push)")
    String registryUsername;

    @Option(names = "--registry-password",
            description = "Registry password (used only with --push)")
    String registryPassword;

    @Option(names = "--latest",
            description = "Tag this as the latest image")
    boolean latest;

    @Option(names = "--base-image", defaultValue = "pgvector/pgvector:pg16",
            description = "Base image for final image (default: ${DEFAULT-VALUE})")
    String baseImageRef;

    @Option(names = "--max-guides", defaultValue = "0",
            description = "Maximum number of guides to process (0 = all, useful for testing)")
    int maxGuides;

    private PostgreSQLContainer<?> container;

    @Override
    public void run() {
        long t0 = System.nanoTime();
        LOG.infof("[bake-image] Started at %s", Instant.now());
        LOG.infof("[bake-image] Quarkus version: %s", quarkusVersion);
        LOG.infof("[bake-image] Chunk size: %d, overlap: %d, semantic: %s",
                  chunkSize, chunkOverlap, semanticChunking);

        Path workDir = null;
        try {
            // 1) Start pgvector container
            LOG.info("=== Starting pgvector with Testcontainers ===");
            this.container = new PostgreSQLContainer<>(DockerImageName.parse(this.baseImageRef))
                    .withDatabaseName(DB_NAME)
                    .withUsername("postgres")
                    .withPassword("postgres");
            this.container.start();

            String jdbcUrl = this.container.getJdbcUrl();
            String user = this.container.getUsername();
            String pass = this.container.getPassword();
            LOG.infof("[bake-image] Started: %s id=%s", this.baseImageRef, this.container.getContainerId());

            // 2) Setup embedding store and model
            LOG.info("=== Setting up embedding infrastructure ===");
            int embeddingDimensions = getDim();
            DataSource ds = makeDataSource(jdbcUrl, user, pass);

            PgVectorEmbeddingStore store = PgVectorEmbeddingStore.datasourceBuilder()
                    .datasource(ds)
                    .table("rag_documents")
                    .dimension(embeddingDimensions)
                    .useIndex(true)
                    .indexListSize(100)
                    .build();

            EmbeddingModel embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();

            DocumentSplitter splitter;
            if (semanticChunking) {
                LOG.infof("[bake-image] Using semantic chunking (Markdown headers), max chunk=%d", chunkSize);
                splitter = new MarkdownSemanticSplitter(chunkSize, chunkOverlap);
            } else {
                LOG.infof("[bake-image] Using recursive chunking, size=%d, overlap=%d", chunkSize, chunkOverlap);
                splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
            }

            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(store)
                    .documentSplitter(splitter)
                    .build();

            // 3) Fetch guide URLs
            LOG.info("=== Fetching Quarkus guide URLs ===");
            QuarkusDocsFetcher fetcher = new QuarkusDocsFetcher();
            List<String> guideUrls = fetcher.fetchGuideUrls(quarkusVersion);

            if (maxGuides > 0 && guideUrls.size() > maxGuides) {
                LOG.infof("[bake-image] Limiting to first %d guides (out of %d)", maxGuides, guideUrls.size());
                guideUrls = guideUrls.subList(0, maxGuides);
            }

            LOG.infof("[bake-image] Found %d guides to process", guideUrls.size());

            // 4) Process each guide with Docling
            LOG.info("=== Processing guides with Docling ===");
            DoclingConverter converter = new DoclingConverter(doclingService);
            int processed = 0;
            int total = guideUrls.size();

            for (String url : guideUrls) {
                try {
                    // Convert HTML to Markdown using Docling
                    String markdown = converter.convertUrlToMarkdown(url);

                    // Extract metadata
                    Metadata metadata = new Metadata();
                    metadata.put("url", url);
                    metadata.put("quarkus_version", quarkusVersion);

                    // Extract title from URL (last segment)
                    String title = extractTitleFromUrl(url);
                    metadata.put("title", title);

                    // Create document and ingest
                    Document doc = Document.from(markdown, metadata);
                    ingestor.ingest(doc);

                    processed++;
                    if (processed % 10 == 0 || processed == total) {
                        LOG.infof("[bake-image] Processed %d / %d guides", processed, total);
                    }
                } catch (Exception e) {
                    LOG.errorf(e, "[bake-image] Failed to process %s - skipping", url);
                }
            }

            LOG.infof("[bake-image] Successfully ingested %d / %d guides", processed, total);

            // 5) Dump database to SQL
            LOG.info("=== Dumping database ===");
            workDir = Files.createTempDirectory("rag-bake-" + System.nanoTime());
            Path initDir = Files.createDirectories(workDir.resolve("init"));
            Path dump = initDir.resolve("01-rag.sql");

            // Dump inside container to /tmp/rag.sql then copy to host
            String inside = "/tmp/rag.sql";
            var result = this.container.execInContainer(
                    "bash", "-lc",
                    "PGPASSWORD=" + this.container.getPassword() +
                            " pg_dump -U " + this.container.getUsername() +
                            " -d " + DB_NAME +
                            " --no-owner --no-privileges --format=plain -f " + inside
            );

            if (result.getExitCode() != 0) {
                throw new IllegalStateException("pg_dump failed: " + result.getStderr());
            }

            this.container.copyFileFromContainer(inside, dump.toString());
            LOG.infof("[bake-image] Dumped SQL -> %s", dump);

            // 6) Build and push the image with Jib
            LOG.info("=== Building Docker image ===");
            FileEntriesLayer initLayer = FileEntriesLayer.builder()
                    .setName("initdb-sql")
                    .addEntryRecursive(initDir, AbsoluteUnixPath.get("/docker-entrypoint-initdb.d"))
                    .build();

            JibContainerBuilder jib = Jib.from(baseImageRef).addFileEntriesLayer(initLayer);

            String targetImageRef = "ghcr.io/quarkusio/chappie-ingestion-quarkus:" + quarkusVersion;
            LOG.infof("[bake-image] Creating image: %s", targetImageRef);

            Containerizer containerizer;
            if (push) {
                // Multi-platform
                jib.setPlatforms(Set.of(new Platform("amd64", "linux"), new Platform("arm64", "linux")));

                RegistryImage registry = RegistryImage.named(targetImageRef);
                if (registryUsername != null && registryPassword != null) {
                    registry.addCredential(registryUsername, registryPassword);
                }
                containerizer = Containerizer.to(registry);
            } else {
                containerizer = Containerizer.to(DockerDaemonImage.named(targetImageRef));
            }

            if (latest) {
                LOG.info("[bake-image] Also tagging as :latest");
                containerizer.withAdditionalTag("latest");
            }

            containerizer
                    .setToolName("bake-image")
                    .setAllowInsecureRegistries(false);

            jib.containerize(containerizer);
            LOG.infof("[bake-image] Image ready: %s", targetImageRef);

        } catch (Exception e) {
            LOG.error("[bake-image] Failed", e);
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        } finally {
            // Cleanup
            if (container != null) {
                LOG.info("[bake-image] Stopping container");
                try {
                    container.stop();
                } catch (Throwable t) {
                    LOG.warn("Failed to stop container", t);
                }
            }
            if (workDir != null) {
                try {
                    deleteRecursive(workDir);
                } catch (Throwable ignore) {
                }
            }

            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOG.infof("[bake-image] Completed in %d ms (%.2f minutes)", ms, ms / 60000.0);
        }
    }

    private static DataSource makeDataSource(String jdbc, String user, String pass) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(jdbc);
        ds.setUser(user);
        ds.setPassword(pass);
        return ds;
    }

    private int getDim() {
        Config c = ConfigProvider.getConfig();
        return c.getValue("quarkus.langchain4j.pgvector.dimension", Integer.class);
    }

    private static String extractTitleFromUrl(String url) {
        // Extract title from URL like "https://quarkus.io/version/3.30/guides/kafka"
        // Returns "kafka"
        String[] parts = url.split("/");
        if (parts.length > 0) {
            String last = parts[parts.length - 1];
            return last.isEmpty() && parts.length > 1 ? parts[parts.length - 2] : last;
        }
        return "unknown";
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            List<Path> paths = s.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
