# Deferred Tests Analysis: AsciiDoc vs Docling

**Date:** 2026-01-28
**Tests:** 11 previously deferred test cases

## Summary

Testing 11 cases that were deferred from the original baseline because they failed:

| Image | Original 36 Tests | Deferred 11 Tests | Total (47 Tests) |
|-------|-------------------|-------------------|------------------|
| **AsciiDoc (3.30.6)** | 34/36 (94.4%) | 11/11 (100%) | 45/47 (95.7%) |
| **Docling (3.30)** | 36/36 (100%) | 8/11 (73%) | 44/47 (93.6%) |

## Surprising Finding!

The baseline (AsciiDoc 3.30.6) **actually passes all 11 deferred tests**.

This suggests these tests were deferred not because they're inherently hard, but because:
1. The documentation coverage in 3.30.6 was improved since the tests were deferred
2. OR the tests were deferred during earlier experiments with different images

## Detailed Results

### Docling (3.30) - Passed 8/11

✅ **Passed (8):**
1. dev-mode
2. mongodb-client
3. fault-tolerance
4. cors-configuration
5. validation-constraints
6. qute-template
7. reactive-routes
8. panache-repository

❌ **Failed (3):**

1. **graphql-endpoint**
   ```
   Query: "How do I create a GraphQL endpoint in Quarkus?"
   Expected: Documents containing "graphql"
   Got: build-analytics.adoc, writing-extensions.adoc, dev-mode-differences.adoc
   ```
   **Analysis:** Docling image (266 guides) doesn't include GraphQL docs, or they're poorly chunked

2. **rest-client-create**
   ```
   Query: "How do I create a REST client using @RegisterRestClient?"
   Expected: Documents containing "rest-client" or "rest"
   Got: security-openid-connect-client-reference.adoc (5 times)
   ```
   **Analysis:** Query matches "client" too strongly, retrieves OIDC client docs instead

3. **application-lifecycle**
   ```
   Query: "How do I run code on application startup in Quarkus?"
   Expected: Documents containing "lifecycle" or "startup"
   Got: security-openid-connect-client.adoc, infinispan-client.adoc, scripting.adoc
   ```
   **Analysis:** Lifecycle docs missing or poorly retrieved

### AsciiDoc (3.30.6) - Passed 11/11

✅ **All tests passed!**

Sample results:
- **graphql-endpoint**: Found `smallrye-graphql.adoc` (score: 0.9067)
- **rest-client-create**: Found `rest-client.adoc` (score: 0.9299)
- **application-lifecycle**: Found `lifecycle.adoc` (score: 0.9182)

## Why Does Baseline Pass These?

The AsciiDoc baseline (3.30.6) includes specific documentation that Docling (3.30) is missing or poorly chunking:

1. **GraphQL**: `smallrye-graphql.adoc` exists in baseline
2. **REST Client**: Properly chunked in baseline
3. **Lifecycle**: `lifecycle.adoc` exists and well-chunked in baseline

## Coverage Analysis

### AsciiDoc (3.30.6) - 148 Guides

**Curated selection:**
- High-quality guides manually selected
- Includes critical docs: graphql, lifecycle, rest-client
- Well-tested and proven

**Strengths:**
- ✅ Proven to work for 45/47 tests (95.7%)
- ✅ Includes all critical documentation
- ✅ Well-curated for quality

**Weaknesses:**
- ❌ Missing 2 tests from original 36: health-checks, panache-testing
- ❌ Only 148 guides (limited coverage)

### Docling (3.30) - 266 Guides

**Comprehensive but raw:**
- ALL guides from quarkus.io
- Not curated - includes everything
- Some guides missing or poorly processed

**Strengths:**
- ✅ Passes all original 36 tests (100%)
- ✅ 80% more documentation (266 vs 148)
- ✅ Fixed health-checks and panache-testing

**Weaknesses:**
- ❌ Missing 3 deferred tests: graphql, rest-client, lifecycle
- ❌ GraphQL docs not included in 266 guides (possibly filtered out)
- ❌ Some docs poorly chunked

## Missing Documentation Investigation

### Why is GraphQL Missing from Docling?

The Docling approach fetches from `https://quarkus.io/guides/`. Let's check if GraphQL is there:

**Hypothesis 1:** GraphQL docs exist but were filtered out
- Possible: The guide URL filter excluded it
- Solution: Review QuarkusDocsFetcher filtering logic

**Hypothesis 2:** GraphQL docs are in a different location
- Possible: Extension-specific docs not in /guides/
- Solution: Fetch from additional locations

**Hypothesis 3:** GraphQL docs failed processing
- Possible: Like `all-config`, too large or malformed
- Solution: Review processing logs for failures

### Why is lifecycle Missing?

Similar investigation needed for `lifecycle.adoc`.

## Overall Performance Comparison

### Combined Score (47 Total Tests)

| Metric | AsciiDoc | Docling | Winner |
|--------|----------|---------|--------|
| **Original Tests** | 34/36 (94%) | 36/36 (100%) | ✅ Docling +6% |
| **Deferred Tests** | 11/11 (100%) | 8/11 (73%) | ✅ AsciiDoc +27% |
| **Total Score** | 45/47 (96%) | 44/47 (94%) | ✅ AsciiDoc +2% |

**Note:** The marginal difference (1 test) means both approaches are highly effective.

## Key Insights

### 1. Different Strengths

- **AsciiDoc**: Better on deferred/edge cases (graphql, lifecycle, rest-client)
- **Docling**: Better on common cases (health-checks, panache-testing)

### 2. Coverage vs. Curation Trade-off

- **AsciiDoc**: 148 curated guides = fewer but higher quality
- **Docling**: 266 comprehensive guides = more but some gaps

### 3. Missing Critical Docs

The Docling approach is missing some critical documentation:
- GraphQL endpoint creation
- Application lifecycle
- REST client (poorly retrieved)

These are common user queries, so missing them is problematic.

## Recommendations

### Option 1: Use AsciiDoc (3.30.6) - SAFER ✅

**Reasoning:**
- Passes 45/47 total tests (96%)
- Only fails 2 tests (health-checks, panache-testing)
- Proven, stable, well-curated
- **Fails on less critical queries** (subjective: health checks vs graphql)

**Use when:**
- Stability is paramount
- Can tolerate 2 specific failures
- Prefer proven approach

### Option 2: Use Docling (3.30) - PROMISING ⚠️

**Reasoning:**
- Passes 44/47 total tests (94%)
- Fails 3 tests (graphql, rest-client, lifecycle)
- More comprehensive coverage (266 guides)
- **Fails on common user queries** (graphql is very popular)

**Use when:**
- Comprehensive coverage matters
- Can fix missing docs (add graphql, lifecycle)
- Want always-current content

### Option 3: Hybrid Approach (RECOMMENDED) ✅

**Combine strengths of both:**

```yaml
Base: AsciiDoc (3.30.6)
  - Proven quality
  - 148 curated guides
  - Passes deferred tests

Enhancement: Add Docling guides for gaps
  - Add health-checks docs (fix baseline failures)
  - Add panache-testing docs (fix baseline failures)
  - Verify all guides are well-processed

Result: Best of both worlds
  - 47/47 tests passing (100%)
  - Comprehensive coverage
  - All critical docs included
```

**Implementation:**
1. Start with AsciiDoc baseline (3.30.6)
2. Add specific guides from Docling that fix failures:
   - `smallrye-health.adoc` (for health-checks)
   - Better panache testing coverage
3. Test thoroughly
4. Achieve 100% pass rate

### Option 4: Fix Docling Gaps

**Investigate and fix:**
1. Why GraphQL docs are missing
   - Check guide URL fetching
   - Verify processing logs
   - Add GraphQL guides manually if needed

2. Why lifecycle docs are missing
   - Same investigation as GraphQL

3. Why REST client retrieval fails
   - Query embeddings issue
   - Chunking problem
   - Scoring problem

**If fixed:** Docling could achieve 47/47 (100%)

## Conclusion

Both approaches have merit:

**AsciiDoc wins on total score:** 45/47 (96%) vs 44/47 (94%)
**Docling wins on comprehensiveness:** 266 guides vs 148 guides
**AsciiDoc wins on critical docs:** GraphQL, lifecycle, REST client all work
**Docling wins on freshness:** Always up-to-date

**Final Recommendation:** Use AsciiDoc (3.30.6) for now, but investigate why Docling is missing critical docs. If those gaps can be filled, Docling would be the superior choice.

The 1-test difference is marginal, but the **nature of the failures matters**:
- AsciiDoc fails: health-checks, panache-testing (moderate usage)
- Docling fails: graphql, rest-client, lifecycle (high usage)

GraphQL and REST clients are extremely common in Quarkus applications, so missing those docs is more problematic than missing health check details.

**Verdict:** AsciiDoc (3.30.6) remains the recommended choice until Docling gaps are addressed.
