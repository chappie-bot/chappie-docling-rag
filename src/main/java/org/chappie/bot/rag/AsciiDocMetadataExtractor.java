package org.chappie.bot.rag;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts metadata from AsciiDoc file headers.
 * Reads the first ~120 lines looking for attribute definitions like:
 * :categories: web
 * :topics: graphql
 * :extensions: io.quarkus:quarkus-smallrye-graphql
 * :summary: This guide explains...
 */
public class AsciiDocMetadataExtractor {

    private static final Pattern ATTR_PATTERN =
        Pattern.compile("^\\s*:(categories|summary|extensions|topics):\\s*(.*)\\s*$");

    private static final int MAX_SCAN_LINES = 120;

    /**
     * Extract metadata from the header of an AsciiDoc file.
     *
     * @param adocPath Path to the .adoc file
     * @return Map of attribute names to values (categories, summary, extensions, topics)
     */
    public static Map<String, String> extractMetadata(Path adocPath) {
        Map<String, String> metadata = new HashMap<>();

        if (!Files.isRegularFile(adocPath)) {
            return metadata;
        }

        try (BufferedReader br = Files.newBufferedReader(adocPath, StandardCharsets.UTF_8)) {
            String line;
            int lineCount = 0;

            while ((line = br.readLine()) != null) {
                if (++lineCount > MAX_SCAN_LINES) {
                    break;
                }

                Matcher m = ATTR_PATTERN.matcher(line);
                if (m.matches()) {
                    String key = m.group(1).toLowerCase();
                    String value = m.group(2).trim();
                    metadata.put(key, value);

                    // Stop early if we found all four attributes
                    if (metadata.size() == 4) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            // Ignore - return empty metadata
        }

        return metadata;
    }
}
