# Docling-based RAG Evaluation

**Date:** 2026-01-27
**Status:** ✅ **EXPERIMENTAL - SUCCESSFUL PROOF OF CONCEPT**

## Summary

Successfully created and tested a **Docling-based RAG implementation** that processes Quarkus documentation from web HTML instead of AsciiDoc files. The implementation achieved comparable RAG quality to the baseline AsciiDoc approach.

## Approach Comparison

| Aspect | **AsciiDoc Baseline** | **Docling (This Implementation)** |
|--------|----------------------|-----------------------------------|
| **Source** | AsciiDoc files from quarkus repo | Live HTML from quarkus.io |
| **Processor** | Manual AsciiDoc parsing | Docling Serve (layout-aware) |
| **Chunking** | AsciiDocSemanticSplitter | MarkdownSemanticSplitter |
| **Guides Processed** | ~148 (selected subset) | 266 (all available guides) |
| **Docker Image Size** | 575 MB (3.30.6) | 660 MB (3.30) |
| **Processing Time** | ~8-10 min (estimated) | 15.96 minutes |
| **Image Version** | 3.30.6 | 3.30 |

## Implementation Details

### Pipeline Architecture

```
Web Fetch → Docling Serve (HTML→MD) → Semantic Chunking → Embedding → PGVector → Docker Image
```

### Key Components

1. **QuarkusDocsFetcher** - Fetches guide URLs from https://quarkus.io/guides/
2. **Docling Serve Container** - Converts HTML to Markdown with layout preservation
3. **MarkdownSemanticSplitter** - Splits by Markdown headers (adapted from AsciiDoc version)
4. **Embedding Model** - BGE Small EN v15 Quantized (same as baseline)
5. **Vector Store** - PGVector with 384 dimensions

### Configuration

| Parameter | Value | Notes |
|-----------|-------|-------|
| MIN_SECTION_SIZE | 300 chars | Same as baseline |
| Max Chunk Size | 1000 chars | Same as baseline |
| Chunk Overlap | 300 chars (30%) | Same as baseline |
| Semantic Chunking | Enabled | Same as baseline |
| Guides Processed | 266 | vs 148 in baseline |
| Failed Guides | 1 (all-config) | Too large: 20MB of Markdown |

## Test Results

### Overall Performance

✅ **All 36 RAG golden set tests PASSED**

```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

### Key Metrics (Docling 3.30)

| Test Case | Score | Baseline (3.30.6) | Δ |
|-----------|-------|-------------------|---|
| kafka-producer | 0.9349 | 0.9395 | -0.5% |
| kafka-consumer | 0.9350 | 0.9382 | -0.3% |
| panache-entity | 0.9109 | 0.9109 | 0.0% |
| metrics | 0.9487 | 0.9487 | 0.0% |
| hibernate-search | 0.9667 | 0.9667 | 0.0% |
| devservices-postgresql | 0.9350 | - | - |
| security-oidc | 0.9369 | - | - |
| datasource-config | 0.9367 | - | - |
| config-properties | 0.9028 | ~0.90 (est) | ~+0.3% |

**Notes:**
- Direct comparison is approximate due to different guide sets (266 vs 148)
- Baseline focused on selected high-quality guides
- Docling version includes ALL available guides, potentially diluting precision
- Despite larger guide set, maintained competitive scores

### Top Scores

| Test Case | Score | Result |
|-----------|-------|--------|
| hibernate-search | **0.9667** | Excellent |
| metrics | **0.9487** | Excellent |
| devui-add-page | **0.9384** | Very Good |
| security-oidc | **0.9369** | Very Good |
| datasource-config | **0.9367** | Very Good |
| kafka-producer | **0.9349** | Very Good |
| kafka-consumer | **0.9350** | Very Good |
| devservices-postgresql | **0.9350** | Very Good |
| rest-create-endpoint | **0.9306** | Very Good |

## Advantages of Docling Approach

### ✅ Benefits

1. **Always Up-to-Date**
   - Fetches live content from quarkus.io
   - No need to sync with quarkus repository
   - Automatically includes new guides

2. **Comprehensive Coverage**
   - Processed 266 guides vs 148 in baseline
   - 80% more documentation covered
   - Better coverage for edge cases

3. **Layout-Aware Processing**
   - Docling understands HTML structure
   - Preserves tables, code blocks, lists
   - Better semantic understanding

4. **Simplified Pipeline**
   - No need to clone quarkus repository
   - No AsciiDoc toolchain required
   - Direct web → markdown conversion

5. **Flexibility**
   - Can process any web documentation
   - Not tied to AsciiDoc format
   - Easier to extend to other sources

### ⚠️ Trade-offs

1. **Network Dependency**
   - Requires internet connection
   - Slower processing (15.96 min vs ~10 min)
   - ~2-4 seconds per guide for HTML conversion

2. **Larger Image**
   - 660 MB vs 575 MB (+15%)
   - More guides = more data
   - Acceptable for improved coverage

3. **Version Tracking**
   - Web content may change without notice
   - No git commit hash for reproducibility
   - Need to timestamp builds

4. **One Failed Guide**
   - all-config guide too large (20MB Markdown)
   - Not critical (just a config reference)
   - Could be addressed with streaming

## Technical Challenges Solved

### 1. URL Pattern Discovery
**Problem:** Quarkus guides are version-independent at `/guides/`, not `/version/X.Y/guides/`
**Solution:** Updated pattern to fetch from `https://quarkus.io/guides/` and filter guide links

### 2. Asset Filtering
**Problem:** Initial extraction included CSS, JS, and image files
**Solution:** Added filters for `.css`, `.js`, `.png`, `.jpg`, `.svg`, `/stylesheet/`, `/assets/`

### 3. Large Document Handling
**Problem:** all-config guide generates 20MB of Markdown, exceeding JSON parser limit
**Solution:** Skipped (acceptable trade-off - not a tutorial guide)

### 4. Docling Container Port
**Problem:** Container runs on port 5001 but initially configured for 5000
**Solution:** Updated all port references to 5001

### 5. Semantic Chunking Adaptation
**Problem:** Needed Markdown version of AsciiDoc semantic splitter
**Solution:** Created MarkdownSemanticSplitter with same logic for `#`, `##`, `###` headers

## Deployment

### Docker Image Created

```bash
Image: ghcr.io/quarkusio/chappie-ingestion-quarkus:3.30
Size: 660 MB
Guides: 266 (99.6% success rate)
Build Time: 15.96 minutes
```

### Usage

```bash
# Build new version
cd chappie-docling-rag
java -jar target/quarkus-app/quarkus-run.jar bake-image --quarkus-version 3.30 --semantic

# Test against golden set
cd ../chappie-server
mvn test -Dtest=RagGoldenSetTest
```

## Comparison with Baseline

### Strengths vs AsciiDoc Approach

| Criterion | Docling | AsciiDoc | Winner |
|-----------|---------|----------|--------|
| **Coverage** | 266 guides | 148 guides | ✅ Docling |
| **Up-to-date** | Always current | Requires sync | ✅ Docling |
| **Processing Speed** | 16 min | ~10 min | ✅ AsciiDoc |
| **Image Size** | 660 MB | 575 MB | ✅ AsciiDoc |
| **RAG Quality** | ~0.93 | ~0.93 | ➖ Tie |
| **Reproducibility** | Timestamp | Git commit | ✅ AsciiDoc |
| **Setup Complexity** | Simple | Requires repo | ✅ Docling |
| **Flexibility** | Any web docs | AsciiDoc only | ✅ Docling |

### When to Use Each Approach

**Use Docling approach when:**
- ✅ You want comprehensive, always-current documentation
- ✅ You need to process web documentation (not just AsciiDoc)
- ✅ Setup simplicity is important
- ✅ You can tolerate longer build times

**Use AsciiDoc approach when:**
- ✅ You need exact reproducibility (git commit)
- ✅ Build speed is critical
- ✅ You want curated, high-quality guide selection
- ✅ You're working offline

## Recommendations

### For Production

**Option 1: Hybrid Approach** (Recommended)
- Use AsciiDoc for stable releases (reproducible)
- Use Docling for preview/dev builds (always current)
- Best of both worlds

**Option 2: Docling-Only**
- If always-current is top priority
- Accept longer build times
- Add timestamp metadata for tracking

**Option 3: Stick with AsciiDoc**
- If reproducibility is critical
- Manually update guide selection periodically
- Maintain current approach

### Future Improvements

1. **Streaming for Large Guides**
   - Handle all-config guide with streaming parser
   - Avoid 20MB string limit

2. **Parallel Processing**
   - Process multiple guides concurrently
   - Reduce 16-minute build time

3. **Incremental Updates**
   - Only fetch changed guides
   - Cache previous conversions
   - Delta-based builds

4. **Quality Scoring**
   - Track per-guide contribution to RAG quality
   - Identify low-value guides to skip
   - Optimize coverage vs. noise ratio

## Conclusion

The Docling-based approach successfully demonstrates that **web-based RAG** can achieve **comparable quality** to AsciiDoc-based RAG while providing **significantly better coverage** (80% more guides).

Key achievements:
- ✅ 266 guides processed (vs 148 baseline)
- ✅ All 36 golden set tests passed
- ✅ Maintained ~0.93 average score
- ✅ Simplified pipeline (no repo cloning)
- ✅ Always up-to-date documentation

The trade-offs (longer build time, larger image) are acceptable given the benefits of comprehensive coverage and simplified maintenance.

**Recommendation:** Consider hybrid approach for production - use AsciiDoc for stable releases and Docling for development/preview environments.
