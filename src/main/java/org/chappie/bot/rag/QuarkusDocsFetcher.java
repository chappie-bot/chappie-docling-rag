package org.chappie.bot.rag;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

/**
 * Fetches the list of Quarkus documentation guide URLs for a specific version.
 * Also extracts keywords metadata from the guides index page.
 */
public class QuarkusDocsFetcher {

    private static final Logger LOG = Logger.getLogger(QuarkusDocsFetcher.class);
    // Note: Quarkus guides are version-independent at /guides/, not /version/X.Y/guides/
    private static final String GUIDES_INDEX_URL = "https://quarkus.io/guides/";
    private static final Pattern GUIDE_LINK_PATTERN = Pattern.compile("href=\"(/guides/[^\"#?]+)\"");

    // Pattern to extract <qs-guide> blocks
    private static final Pattern QS_GUIDE_PATTERN = Pattern.compile(
        "<qs-guide[^>]*?>(.*?)</qs-guide>",
        Pattern.DOTALL
    );

    // Patterns to extract attributes from <qs-guide> tags
    private static final Pattern URL_ATTR_PATTERN = Pattern.compile("url=\"(/guides/[^\"]+)\"");
    private static final Pattern KEYWORDS_ATTR_PATTERN = Pattern.compile("keywords=\"([^\"]*)\"");

    private final HttpClient httpClient;

    public QuarkusDocsFetcher() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches all guide URLs for a specific Quarkus version.
     *
     * @param version Quarkus version (e.g., "3.30.6")
     * @return List of guide URLs
     */
    public List<String> fetchGuideUrls(String version) {
        return new ArrayList<>(fetchGuideMetadata(version).keySet());
    }

    /**
     * Fetches all guide URLs with their keywords metadata.
     *
     * @param version Quarkus version (e.g., "3.30.6")
     * @return Map of guide URL to keywords string (may be empty)
     */
    public Map<String, String> fetchGuideMetadata(String version) {
        LOG.infof("[fetcher] Fetching guides index from: %s", GUIDES_INDEX_URL);

        try {
            String html = fetchPage(GUIDES_INDEX_URL);
            Map<String, String> metadata = extractGuideMetadata(html);
            LOG.infof("[fetcher] Found %d guides with metadata", metadata.size());

            // Log sample of guides with keywords
            long withKeywords = metadata.values().stream().filter(k -> !k.isEmpty()).count();
            LOG.infof("[fetcher] %d guides have keywords", withKeywords);

            return metadata;
        } catch (Exception e) {
            LOG.error("[fetcher] Failed to fetch guide metadata", e);
            throw new RuntimeException("Failed to fetch guide metadata for version " + version, e);
        }
    }

    /**
     * Fetches a web page and returns its HTML content.
     */
    private String fetchPage(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }

        return response.body();
    }

    /**
     * Extracts guide URLs and their keywords from the guides index page HTML.
     * Returns a map of absolute URL -> keywords string (empty if no keywords)
     */
    private Map<String, String> extractGuideMetadata(String html) {
        Map<String, String> metadata = new HashMap<>();

        // Extract each <qs-guide> block
        Matcher qsGuideMatcher = QS_GUIDE_PATTERN.matcher(html);
        while (qsGuideMatcher.find()) {
            String guideBlock = qsGuideMatcher.group(0);  // Full <qs-guide>...</qs-guide> block

            // Extract URL from this block
            Matcher urlMatcher = URL_ATTR_PATTERN.matcher(guideBlock);
            if (urlMatcher.find()) {
                String path = urlMatcher.group(1);  // e.g., "/guides/lifecycle"
                String absoluteUrl = "https://quarkus.io" + path;

                // Extract keywords from this block (may be empty)
                String keywords = "";
                Matcher keywordsMatcher = KEYWORDS_ATTR_PATTERN.matcher(guideBlock);
                if (keywordsMatcher.find()) {
                    keywords = keywordsMatcher.group(1);
                }

                metadata.put(absoluteUrl, keywords);
            }
        }

        // Fallback: Add any URLs from href links that weren't in <qs-guide> blocks
        Matcher linkMatcher = GUIDE_LINK_PATTERN.matcher(html);
        while (linkMatcher.find()) {
            String href = linkMatcher.group(1);

            // Filter for actual guide pages
            if (!href.endsWith("/guides/") &&
                !href.contains("/stylesheet/") &&
                !href.contains("/assets/") &&
                !href.endsWith(".css") &&
                !href.endsWith(".js") &&
                !href.endsWith(".png") &&
                !href.endsWith(".jpg") &&
                !href.endsWith(".svg")) {

                String absoluteUrl = "https://quarkus.io" + href;
                // Add if not already present (from <qs-guide> extraction)
                metadata.putIfAbsent(absoluteUrl, "");
            }
        }

        return metadata;
    }

    /**
     * Extracts guide URLs from the guides index page HTML.
     * Looks for links matching the pattern /guides/{guide-name}
     * @deprecated Use fetchGuideMetadata() to also get keywords
     */
    @Deprecated
    private List<String> extractGuideUrls(String html, String version) {
        return new ArrayList<>(extractGuideMetadata(html).keySet());
    }
}
