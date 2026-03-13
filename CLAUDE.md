# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Chappie Docling RAG builds pgvector Docker images containing embedded Quarkus documentation for RAG (Retrieval-Augmented Generation). It combines AsciiDoc metadata extraction with Docling-powered HTML-to-Markdown conversion to achieve 100% accuracy on the RAG golden set (36/36 test cases).

The output image (`ghcr.io/quarkusio/chappie-ingestion-quarkus:<version>`) is consumed by chappie-server. This project is part of the larger Chappie system (see parent `CLAUDE.md`).

## Build and Run Commands

```bash
# Build
mvn clean package -DskipTests

# Run tests
mvn test

# Dev mode (Docling UI at http://localhost:8080/q/dev/)
./mvnw quarkus:dev

# Quick test with 5 guides
java -jar target/quarkus-app/quarkus-run.jar bake-image \
  --quarkus-version=3.15.0 --max-guides=5 --semantic

# Production build (all ~250 guides, takes 15-20 min)
java -jar target/quarkus-app/quarkus-run.jar bake-image \
  --quarkus-version=3.15.0 --semantic

# Push to registry
java -jar target/quarkus-app/quarkus-run.jar bake-image \
  --quarkus-version=3.15.0 --semantic --push \
  --registry-username=USER --registry-password=PASS
```

**Requires:** Java 21+, Docker, Maven 3.9+

## Architecture

The entire codebase is 4 Java files in `src/main/java/org/chappie/bot/rag/`:

**Pipeline (orchestrated by `BakeImageCommand`):**
1. Start Docling Serve container on **fixed port 5001** (Testcontainers) — required because Quarkus CDI injects `DoclingService` with a stable base URL
2. Start pgvector container (Testcontainers)
3. Clone Quarkus repo at version tag (JGit, shallow clone)
4. Find `.adoc` files in `docs/src/main/asciidoc/` (excludes `_includes`, `_templates`, files starting with `_`)
5. For each guide:
   - `AsciiDocMetadataExtractor` — scans first 120 lines for `:topics:`, `:categories:`, `:extensions:`, `:summary:` attributes
   - Fetch versioned HTML from `quarkus.io/version/<major.minor>/guides/<title>` (falls back to latest URL on 404)
   - Convert HTML → Markdown via Docling
   - Chunk with `MarkdownSemanticSplitter` (splits by headers, merges small sections <300 chars, falls back to recursive splitting for oversized sections)
   - Generate BGE-Small-En-v1.5 embeddings (384 dimensions)
   - Ingest to pgvector
6. `pg_dump` the database to SQL
7. Build Docker image with Jib (multi-platform amd64+arm64 when using `--push`)

**Key classes:**
- `DoclingCli` — Picocli top command, entry point
- `BakeImageCommand` — Main pipeline orchestrator with all CLI options
- `AsciiDocMetadataExtractor` — Static utility, extracts 4 metadata fields from AsciiDoc headers
- `MarkdownSemanticSplitter` — Custom `DocumentSplitter` that splits by Markdown headers preserving hierarchical section paths

## Critical Constraints

- **Embedding dimension is 384** (BGE-Small-En-v1.5). Never change `quarkus.langchain4j.pgvector.dimension` without re-ingesting all documents. Dimension mismatch causes silent failures.
- **Docling port 5001 is hardcoded** — `BakeImageCommand` binds port 5001 and `application.properties` sets `quarkus.docling.base-url=http://localhost:5001`. Both must agree.
- **Docling devservices is disabled** (`quarkus.docling.devservices.enabled=false`) because `BakeImageCommand` manages the container lifecycle manually.

## CLI Options

| Option | Default | Description |
|--------|---------|-------------|
| `--quarkus-version` | required | Quarkus version tag to process |
| `--semantic` | false | Use header-based semantic chunking (recommended) |
| `--chunk-size` | 1000 | Max chunk size in chars |
| `--chunk-overlap` | 300 | Overlap between chunks |
| `--max-guides` | 0 (all) | Limit guides for testing |
| `--base-image` | `pgvector/pgvector:pg16` | Base Docker image |
| `--push` | false | Push to registry vs local Docker |
| `--latest` | false | Also tag as `:latest` |
