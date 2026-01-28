# Retrieval Quality Investigation

**Date:** 2026-01-28
**Issue:** Docling 3.30-fixed achieving only 44/47 tests (94%) instead of expected 47/47 (100%)

## Summary

The metadata fix (`repo_path`) worked correctly - all documents now have proper repo_path metadata. However, we're seeing **retrieval quality issues** where the correct documents exist in the database but are not ranking high enough in search results.

## Root Cause

With 266 guides in the Docling corpus (vs 148 in AsciiDoc baseline), more documents compete for relevance. Some less relevant documents are scoring higher than the target documents, pushing them outside the top 10 results.

## Failing Test Cases

### 1. dev-mode (Deferred Test)
- **Query:** "What is Quarkus Dev Mode and how does it work?"
- **Expected:** repo_path containing "dev-mode" OR "continuous-testing" in top 10
- **Actual Results (top 10):**
  1. spring-data-jpa.adoc (0.9372)
  2. spring-data-rest.adoc (0.9372)
  3. maven-tooling.adoc (0.9368)
  4. azure-functions-http.adoc (0.9360)
  5. funqy-http.adoc (0.9313)
  6. gradle-tooling.adoc (0.9308)
  7. azure-functions.adoc (0.9302)
  8. **dev-mode-differences.adoc (0.9288)** ✅ Contains "dev-mode"!
  9. dev-ui.adoc (0.9288)
  10. cli-tooling.adoc (0.9286)

- **Analysis:** dev-mode-differences.adoc IS at rank #8! This should PASS the test.
- **Status:** Need to re-verify actual test output - may be passing now

### 2. application-lifecycle (Deferred Test)
- **Query:** "How do I run code on application startup in Quarkus?"
- **Expected:** repo_path containing "lifecycle" OR "startup" in top 10
- **Actual Results (top 20):**
  1. continuous-testing.adoc (0.9280)
  2. spring-di.adoc (0.9255)
  3. security-openid-connect-dev-services.adoc (0.9251)
  4. security-oidc-code-flow-authentication-tutorial.adoc (0.9235)
  5. security-openid-connect-client.adoc (0.9210)
  ...
  16. **lifecycle.adoc (0.9155)** ❌ Outside top 10!

- **Analysis:** lifecycle.adoc exists and retrieved, but ranks too low (position #16)
- **Status:** FAILING - correct document not in top 10

### 3. cdi-injection (Golden Set)
- **Query:** "How do I inject beans using CDI in Quarkus?"
- **Expected:** repo_path containing "cdi", "injection", OR "dependency" in top 10
- **Actual Results (keyword search "cdi"):**
  1. extension-faq.adoc (0.8896)
  2. **cdi-reference.adoc (0.8473)** ✅ Contains "cdi"!
  3. lra-dev-services.adoc (0.7910)
  ...

- **Analysis:** cdi-reference.adoc IS at rank #2 for keyword search. Need to check full query.
- **Status:** Need to re-verify actual test output

## What Works

1. ✅ **Metadata fix successful** - All documents have correct `repo_path` metadata
2. ✅ **All documents in database** - lifecycle.adoc, dev-mode-differences.adoc, cdi-reference.adoc all exist
3. ✅ **Retrieval working** - Documents ARE being retrieved, just not always in top 10

## Why Some Tests Fail

**Too much content creates noise:**
- 266 guides vs 148 in baseline = 80% more content
- More documents compete for top ranks
- Some queries retrieve many partially-relevant documents
- Target documents get pushed below rank 10

**Examples:**
- "application startup" matches many security/config/deployment docs
- These score higher than lifecycle.adoc (0.9280 vs 0.9155)
- Score difference is small (0.0125), but enough to miss top 10

## Comparison: Docling vs AsciiDoc

| Metric | Docling 3.30-fixed | AsciiDoc 3.30.6 |
|--------|-------------------|-----------------|
| **Pass Rate** | 44/47 (94%) | 45/47 (96%) |
| **Guide Count** | 266 guides | 148 guides |
| **Coverage** | All docs | Curated subset |
| **Failing Tests** | dev-mode?, lifecycle, cdi-injection? | health-checks, panache-testing |

## Detailed Findings

### lifecycle.adoc Investigation

**Query variations tested:**
1. "How do I run code on application startup in Quarkus?" → lifecycle at rank #16 (0.9155)
2. "application startup lifecycle" → lifecycle NOT in top 20!
3. "@Startup annotation Quarkus" → lifecycle at rank #13 (0.8886)

**Why lifecycle ranks low:**
- Query focuses on "how", "code", "startup" - matches many tutorials
- security-getting-started-tutorial, spring-di, continuous-testing all match
- lifecycle.adoc is shorter/more concise, less text overlap

### dev-mode Investigation

**No standalone dev-mode.adoc guide exists!**
- URL `https://quarkus.io/guides/dev-mode` does NOT exist
- Only `https://quarkus.io/guides/dev-mode-differences` exists
- Test expects "dev-mode" OR "continuous-testing"
- dev-mode-differences.adoc SHOULD match "dev-mode" substring

**Query results:**
1. "What is Quarkus Dev Mode..." → dev-mode-differences at rank #8 ✅
2. "Quarkus Dev Mode" → dev-mode-differences at rank #3, #8, #11, #14 ✅
3. "dev mode continuous testing" → dev-mode-differences at rank #19 ✅

**Status:** This test SHOULD be passing! Needs re-verification.

### cdi-injection Investigation

**Query:** "How do I inject beans using CDI in Quarkus?"
- Keyword "cdi" search: cdi-reference.adoc at rank #2 (0.8473) ✅
- Need to test full query with all keywords

## Recommendations

### Option 1: Accept Current Results (Recommended)
- **94% pass rate is excellent** for a 266-guide corpus
- Failing queries still retrieve relevant docs (just not in top 10)
- Real users would find answers in top 20 results
- Trade-off: More coverage (266 vs 148) for slightly lower precision

**Verdict:** Docling is production-ready at 94%

### Option 2: Curate the Guide List
- Reduce from 266 to ~150 most important guides
- Match AsciiDoc baseline coverage
- Should improve precision to match or exceed baseline

**Effort:** Medium - Need to identify which guides to keep

### Option 3: Improve Retrieval
- Adjust embedding model parameters
- Use reranking / hybrid search
- Boost certain document types
- Fine-tune chunk sizes

**Effort:** High - Requires significant engineering

### Option 4: Relax Test Expectations
- Change assertions from top 10 to top 15 or top 20
- Acknowledge that larger corpus needs more tolerance
- Tests become more realistic for production

**Effort:** Low - Just update test assertions

## Next Steps

1. **Re-run tests** to verify actual current state (dev-mode may be passing)
2. **Decision point:** Choose between options above
3. **Document final recommendation** for production deployment

## Files Referenced

- Test cases: `/home/pkruger/Projects/chappie-bot.org/chappie-server/src/test/resources/rag-eval-deferred.json`
- Test cases: `/home/pkruger/Projects/chappie-bot.org/chappie-server/src/test/resources/rag-eval.json`
- Investigation: `/home/pkruger/Projects/chappie-bot.org/chappie-server/src/test/java/org/chappiebot/rag/InvestigateFailuresTest.java`
- Build log: `/tmp/claude/-home-pkruger-Projects-chappie-bot-org-chappie-server/tasks/ba68301.output`
