# Chappie Docling RAG - New Approach

**Goal:** Replace `chappie-quarkus-rag` with a Docling-based solution that produces the same output (pgvector Docker image with Quarkus documentation).

## Key Differences from Current Approach

### Current (chappie-quarkus-rag)
- Reads AsciiDoc files from downloaded Quarkus source
- Manual manifest creation and enrichment
- Custom `AsciiDocSemanticSplitter` for chunking
- LangChain4J for embeddings (BGE Small EN v15)
- Outputs: pgvector Docker image

### New (chappie-docling-rag)
- Fetches Quarkus documentation from **web URLs** (e.g., https://quarkus.io/version/3.30/guides/*)
- **Docling** converts HTML to Markdown with layout-aware parsing
- Docling's intelligent document understanding (preserves structure, tables, code blocks)
- Same embedding model (BGE Small EN v15 for consistency)
- Same output: pgvector Docker image

## Why This Approach?

1. **Simpler Pipeline**
   - No need to download/unzip Quarkus source code
   - No manual manifest enrichment needed
   - Docling handles document structure automatically

2. **Always Up-to-Date**
   - Fetches live documentation from quarkus.io
   - No version mismatches

3. **Better HTML Processing**
   - Docling designed for web content
   - Preserves layout, tables, code blocks
   - Handles complex document structures

4. **Testing Docling**
   - Validates Quarkus Docling extension in production scenario
   - Real-world use case for feedback to Docling team

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  1. Fetch Quarkus Guide URLs                                 │
│     • List all guides from quarkus.io/version/X.Y.Z/guides/  │
└────────────────────┬─────────────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────────────┐
│  2. Docling Processing                                       │
│     • Convert HTML → Markdown                                │
│     • Preserve structure (headers, tables, code)             │
│     • Extract metadata                                       │
└────────────────────┬─────────────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────────────┐
│  3. Chunking & Embedding                                     │
│     • Sentence-based splitter (200 tokens, 20 overlap)       │
│     • OR Semantic splitter by Markdown headers               │
│     • BGE Small EN v15 embeddings                            │
└────────────────────┬─────────────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────────────┐
│  4. PGVector Ingestion                                       │
│     • Store embeddings + text segments                       │
│     • Metadata: URL, title, section_path                     │
└────────────────────┬─────────────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────────────┐
│  5. Database Dump & Docker Image                             │
│     • pg_dump → SQL file                                     │
│     • Jib: pgvector base + SQL init script                   │
│     • Output: ghcr.io/quarkusio/chappie-ingestion-quarkus   │
└──────────────────────────────────────────────────────────────┘
```

## Implementation Plan

### Phase 1: Basic Web Fetching
```java
@Command(name = "bake-image")
public class BakeImageCommand {
    @Inject DoclingService doclingService;
    @Inject EmbeddingModel embeddingModel;
    @Inject EmbeddingStore embeddingStore;

    public void run() {
        // 1. Fetch guide list from quarkus.io
        List<String> guideUrls = fetchQuarkusGuideUrls(version);

        // 2. Process each URL with Docling
        for (String url : guideUrls) {
            String markdown = doclingService.convertUrl(url);
            Document doc = Document.from(markdown);

            // 3. Split & embed
            List<TextSegment> segments = splitter.split(doc);
            for (TextSegment segment : segments) {
                Embedding embedding = embeddingModel.embed(segment);
                embeddingStore.add(embedding, segment);
            }
        }

        // 4. Dump database & build image
        dumpAndBuildImage();
    }
}
```

### Phase 2: Metadata Enrichment
- Extract title from HTML `<title>` or first H1
- Build section hierarchy from Markdown headers
- Add URL as metadata for source tracking

### Phase 3: Smart Chunking
- Option 1: Sentence-based (like tutorial example)
- Option 2: Markdown header-based (like our semantic splitter)
- Test both and compare results

### Phase 4: Docker Image Building
- Reuse Jib approach from chappie-quarkus-rag
- Same base image: `pgvector/pgvector:pg16`
- Same output format for compatibility

## Configuration

```properties
# Docling
quarkus.docling.devservices.enabled=true
quarkus.docling.devservices.enable-ui=true
quarkus.docling.timeout=3M

# PGVector
quarkus.langchain4j.pgvector.dimension=384
quarkus.langchain4j.pgvector.table=rag_documents
quarkus.langchain4j.pgvector.use-index=true

# Embedding Model
quarkus.langchain4j.embedding-model=bge-small-en-v15-q
```

## Command Line Interface

Same as current project for compatibility:

```bash
# Bake image with Docling approach
java -jar chappie-docling-rag.jar bake-image \
  --quarkus-version 3.30.6 \
  --chunk-size 1000 \
  --chunk-overlap 300 \
  [--semantic]

# Output
ghcr.io/quarkusio/chappie-ingestion-quarkus:3.30.6
```

## Testing Strategy

Use the **same test suite** from chappie-server:
- `RagGoldenSetTest` with 36 test cases
- Compare Docling approach vs. current approach
- Metrics: retrieval accuracy, chunk quality

## Expected Benefits

1. **Simpler maintenance** - no source code downloads
2. **Better HTML handling** - Docling designed for web content
3. **More accurate** - layout-aware parsing preserves structure
4. **Faster pipeline** - direct URL fetching vs. file I/O
5. **Real-time updates** - always uses latest docs

## Questions to Answer

1. How does Docling handle code blocks in HTML?
2. Does Docling preserve admonition blocks (NOTE, TIP, etc.)?
3. How well does Markdown chunking compare to AsciiDoc semantic chunking?
4. Can we fetch the guide list automatically from quarkus.io sitemap?

## Next Steps

1. ✅ Create project structure
2. ✅ Add dependencies
3. ✅ Configure Docling + pgvector
4. ⬜ Implement URL fetcher
5. ⬜ Create DoclingConverter wrapper
6. ⬜ Implement BakeImageCommand
7. ⬜ Test with one guide
8. ⬜ Test with all guides
9. ⬜ Compare results with current approach
10. ⬜ Document findings

## References

- [Quarkus Docling Extension](https://docs.quarkiverse.io/quarkus-docling/dev/index.html)
- [Build Agent-Ready RAG Systems with Quarkus and Docling](https://www.the-main-thread.com/p/enterprise-rag-quarkus-docling-pgvector-tutorial)
- [Taming Unstructured Data with Quarkus and Docling](https://www.the-main-thread.com/p/quarkus-docling-data-preparation-for-ai)
- [LangChain4J Quarkus Integration](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)

## Success Criteria

The new Docling-based approach is successful if:
- ✅ Produces a working pgvector Docker image
- ✅ Achieves comparable RAG quality scores (±5% vs. current)
- ✅ Simpler codebase (< lines of code than current)
- ✅ Faster execution time
- ✅ No regressions in golden set tests
