package org.chappie.bot.rag;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

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

import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.response.ConvertDocumentResponse;
import ai.docling.testcontainers.serve.DoclingServeContainer;
import ai.docling.testcontainers.serve.config.DoclingServeContainerConfig;
import io.quarkiverse.docling.runtime.client.DoclingService;
import jakarta.inject.Inject;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Hybrid approach CLI command to build a pgvector database image with Quarkus documentation.
 *
 * 1. Clones Quarkus repository at specific version tag
 * 2. Extracts rich metadata from AsciiDoc files (topics, categories, extensions, summary)
 * 3. Fetches HTML guides from quarkus.io at same version
 * 4. Uses Docling to convert HTML to well-formatted Markdown
 * 5. Combines Docling content with AsciiDoc metadata
 * 6. Ingests into pgvector and bakes a Docker image
 */
@Command(
    name = "bake-image",
    mixinStandardHelpOptions = true,
    description = "Hybrid approach: Clone Quarkus repo for metadata, use Docling for HTML conversion, combine both."
)
public class BakeImageCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger(BakeImageCommand.class);
    private static final String DB_NAME = "postgres";
    private static final String DOCLING_IMAGE = "ghcr.io/docling-project/docling-serve:v1.10.0";
    private static final int EMBEDDING_DIMENSIONS = 384; // BGE Small EN v15

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
            description = "Use semantic chunking (split by AsciiDoc/Markdown headers) instead of fixed-size chunks")
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

    @Inject
    DoclingService doclingService;

    @Override
    public void run() {
        long t0 = System.nanoTime();
        LOG.infof("[bake-image] Started at %s", Instant.now());
        LOG.infof("[bake-image] Quarkus version: %s", quarkusVersion);
        LOG.infof("[bake-image] Chunk size: %d, overlap: %d, semantic: %s",
                  chunkSize, chunkOverlap, semanticChunking);

        Path workDir = null;
        try (var doclingContainer = new DoclingServeContainer(
            DoclingServeContainerConfig.builder()
                .image(DOCLING_IMAGE)
                .build());
             var pgContainer = new PostgreSQLContainer<>(DockerImageName.parse(this.baseImageRef))
                 .withDatabaseName(DB_NAME)
                 .withUsername("postgres")
                 .withPassword("postgres")) {

            // 1) Start Docling Serve container on fixed port 5001
            LOG.info("=== Starting Docling Serve container ===");
            doclingContainer.setPortBindings(List.of("%d:%d".formatted(DoclingServeContainer.DEFAULT_DOCLING_PORT, DoclingServeContainer.DEFAULT_DOCLING_PORT)));
            doclingContainer.start();
            LOG.infof("[bake-image] Docling Serve started at: %s", doclingContainer.getApiUrl());

            // 2) Start pgvector container
            LOG.info("=== Starting pgvector container ===");
            pgContainer.start();

            String jdbcUrl = pgContainer.getJdbcUrl();
            String user = pgContainer.getUsername();
            String pass = pgContainer.getPassword();
            LOG.infof("[bake-image] PGVector started: %s", jdbcUrl);

            // 3) Setup embedding store and model
            LOG.info("=== Setting up embedding infrastructure ===");
            DataSource ds = makeDataSource(jdbcUrl, user, pass);

            PgVectorEmbeddingStore store = PgVectorEmbeddingStore.datasourceBuilder()
                    .datasource(ds)
                    .table("rag_documents")
                    .dimension(EMBEDDING_DIMENSIONS)
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

            // 4) Clone Quarkus repository for AsciiDoc metadata extraction
            LOG.info("=== Cloning Quarkus repository ===");
            Path quarkusRepoDir = null;
            try {
                quarkusRepoDir = Files.createTempDirectory("quarkus-repo-");
                LOG.infof("[bake-image] Cloning quarkusio/quarkus to: %s", quarkusRepoDir);

                Git git = Git.cloneRepository()
                        .setURI("https://github.com/quarkusio/quarkus.git")
                        .setDirectory(quarkusRepoDir.toFile())
                        .setBranch("refs/tags/" + quarkusVersion)
                        .setDepth(1)  // Shallow clone for faster download
                        .call();
                git.close();

                LOG.infof("[bake-image] Cloned Quarkus %s successfully", quarkusVersion);
            } catch (GitAPIException e) {
                LOG.errorf(e, "[bake-image] Failed to clone Quarkus repository at tag %s", quarkusVersion);
                throw new RuntimeException("Git clone failed", e);
            }
            final Path quarkusRepo = quarkusRepoDir;  // Make effectively final for lambda

            // 5) List all AsciiDoc files from cloned repository
            LOG.info("=== Finding AsciiDoc guides in cloned repository ===");
            Path docsDir = quarkusRepo.resolve("docs/src/main/asciidoc");
            List<Path> adocFiles = new ArrayList<>();

            try (var stream = Files.walk(docsDir)) {
                stream.filter(Files::isRegularFile)
                     .filter(p -> p.getFileName().toString().endsWith(".adoc"))
                     .filter(p -> !p.getFileName().toString().startsWith("_"))  // Exclude includes
                     .filter(p -> !p.toString().contains("/includes/"))  // Exclude includes directory
                     .filter(p -> !p.toString().contains("/_includes/"))  // Exclude _includes directory
                     .filter(p -> !p.toString().contains("/_templates/"))  // Exclude _templates directory
                     .forEach(adocFiles::add);
            }

            adocFiles.sort(Comparator.comparing(Path::toString));

            if (maxGuides > 0 && adocFiles.size() > maxGuides) {
                LOG.infof("[bake-image] Limiting to first %d guides (out of %d)", maxGuides, adocFiles.size());
                adocFiles = adocFiles.subList(0, maxGuides);
            }

            LOG.infof("[bake-image] Found %d AsciiDoc guides to process", adocFiles.size());

            // 6) Determine version string for HTML URLs (e.g., "3.15" from "3.15.0")
            String versionForUrl = quarkusVersion;
            if (versionForUrl.matches("\\d+\\.\\d+\\.\\d+")) {
                // Extract major.minor from major.minor.patch
                versionForUrl = versionForUrl.substring(0, versionForUrl.lastIndexOf('.'));
            }
            LOG.infof("[bake-image] Using version %s for HTML URLs", versionForUrl);

            // 7) Process each guide: Fetch HTML from quarkus.io → Docling → Markdown + AsciiDoc metadata
            LOG.info("=== Processing guides with hybrid approach ===");
            int processed = 0;
            int total = adocFiles.size();

            for (Path adocPath : adocFiles) {
                try {
                    // Extract metadata from AsciiDoc file
                    Metadata metadata = new Metadata();
                    metadata.put("quarkus_version", quarkusVersion);

                    // Set repo_path (relative path from repo root)
                    String repoPath = quarkusRepo.relativize(adocPath).toString();
                    metadata.put("repo_path", repoPath);

                    // Extract title from filename
                    String fileName = adocPath.getFileName().toString();
                    String title = fileName.substring(0, fileName.lastIndexOf('.'));
                    metadata.put("title", title);

                    // Extract AsciiDoc metadata (topics, categories, extensions, summary)
                    Map<String, String> adocMeta = AsciiDocMetadataExtractor.extractMetadata(adocPath);

                    // Add topics (most important for matching!)
                    String topics = adocMeta.get("topics");
                    if (topics != null && !topics.isEmpty()) {
                        metadata.put("topics", topics);
                        LOG.debugf("[bake-image] %s has topics: %s", title, topics);
                    }

                    // Add categories
                    String categories = adocMeta.get("categories");
                    if (categories != null && !categories.isEmpty()) {
                        metadata.put("categories", categories);
                    }

                    // Add extensions
                    String extensions = adocMeta.get("extensions");
                    if (extensions != null && !extensions.isEmpty()) {
                        metadata.put("extensions", extensions);
                    }

                    // Add summary
                    String summary = adocMeta.get("summary");
                    if (summary != null && !summary.isEmpty()) {
                        metadata.put("summary", summary);
                    }

                    // Build versioned HTML URL
                    String htmlUrl = "https://quarkus.io/version/" + versionForUrl + "/guides/" + title;

                    // Use Docling to fetch and convert HTML from quarkus.io to Markdown
                    // Try versioned URL first, fallback to latest if it fails
                    ConvertDocumentResponse resp = null;
                    String actualUrl = htmlUrl;
                    try {
                        URI htmlUri = URI.create(htmlUrl);
                        resp = doclingService.convertFromUri(htmlUri, OutputFormat.MARKDOWN);
                        LOG.infof("[bake-image] Fetched versioned URL: %s", htmlUrl);
                    } catch (Exception e) {
                        // Fallback to latest (non-versioned) URL
                        String latestUrl = "https://quarkus.io/guides/" + title;
                        LOG.warnf("[bake-image] Versioned URL failed (%s), trying latest URL: %s",
                                  e.getMessage(), latestUrl);
                        try {
                            URI latestUri = URI.create(latestUrl);
                            resp = doclingService.convertFromUri(latestUri, OutputFormat.MARKDOWN);
                            actualUrl = latestUrl;
                            LOG.infof("[bake-image] Successfully fetched latest URL: %s", latestUrl);
                        } catch (Exception fallbackEx) {
                            // Both URLs failed, re-throw to be caught by outer exception handler
                            LOG.errorf(fallbackEx, "[bake-image] Both versioned and latest URLs failed for %s", title);
                            throw fallbackEx;
                        }
                    }

                    String markdownContent = resp.getDocument().getMarkdownContent();
                    metadata.put("url", actualUrl);
                    LOG.infof("[bake-image] Converted %s -> %d chars", actualUrl, markdownContent.length());

                    // Create document and ingest (using Docling-converted Markdown content + AsciiDoc metadata)
                    Document doc = Document.from(markdownContent, metadata);
                    ingestor.ingest(doc);

                    processed++;
                    if (processed % 10 == 0 || processed == total) {
                        LOG.infof("[bake-image] Processed %d / %d guides", processed, total);
                    }
                } catch (Exception e) {
                    LOG.errorf(e, "[bake-image] Failed to process %s - skipping", adocPath);
                }
            }

            LOG.infof("[bake-image] Successfully ingested %d / %d guides", processed, total);

            // 6) Dump database to SQL
            LOG.info("=== Dumping database ===");
            workDir = Files.createTempDirectory("rag-bake-" + System.nanoTime());
            Path initDir = Files.createDirectories(workDir.resolve("init"));
            Path dump = initDir.resolve("01-rag.sql");

            // Dump inside container to /tmp/rag.sql then copy to host
            String inside = "/tmp/rag.sql";
            var result = pgContainer.execInContainer(
                    "bash", "-lc",
                    "PGPASSWORD=" + pgContainer.getPassword() +
                            " pg_dump -U " + pgContainer.getUsername() +
                            " -d " + DB_NAME +
                            " --no-owner --no-privileges --format=plain -f " + inside
            );

            if (result.getExitCode() != 0) {
                throw new IllegalStateException("pg_dump failed: " + result.getStderr());
            }

            pgContainer.copyFileFromContainer(inside, dump.toString());
            LOG.infof("[bake-image] Dumped SQL -> %s", dump);

            // 7) Build and push the image with Jib
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

    private static String extractTitleFromUrl(String url) {
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
