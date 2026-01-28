package org.chappie.bot.rag;

import java.net.URI;

import org.jboss.logging.Logger;

import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.response.ConvertDocumentResponse;
import io.quarkiverse.docling.runtime.client.DoclingService;

/**
 * Wrapper around DoclingService for converting web URLs to Markdown.
 */
public class DoclingConverter {

    private static final Logger LOG = Logger.getLogger(DoclingConverter.class);

    private final DoclingService doclingService;

    public DoclingConverter(DoclingService doclingService) {
        this.doclingService = doclingService;
    }

    /**
     * Converts a web URL to Markdown using Docling.
     *
     * @param url URL to convert
     * @return Markdown content
     */
    public String convertUrlToMarkdown(String url) {
        LOG.infof("[docling] Converting: %s", url);

        try {
            // Convert URL directly using Docling
            URI uri = URI.create(url);
            ConvertDocumentResponse response = doclingService.convertFromUri(uri, OutputFormat.MARKDOWN);

            if (response == null || response.getDocument() == null) {
                throw new IllegalStateException("Docling returned null response for " + url);
            }

            String markdown = response.getDocument().getMarkdownContent();
            LOG.infof("[docling] Converted %s -> %d chars", url, markdown.length());

            return markdown;

        } catch (Exception e) {
            LOG.errorf(e, "[docling] Failed to convert %s", url);
            throw new RuntimeException("Failed to convert URL: " + url, e);
        }
    }

    /**
     * Extracts the title from a Markdown document (first # heading).
     *
     * @param markdown Markdown content
     * @return Title or null if not found
     */
    public static String extractTitleFromMarkdown(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return null;
        }

        // Look for first # heading
        String[] lines = markdown.split("\n", 50);  // Only check first 50 lines
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }

        return null;
    }
}
