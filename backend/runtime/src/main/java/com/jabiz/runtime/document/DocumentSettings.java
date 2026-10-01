package com.jabiz.runtime.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Settings of business documents (docs/design/22-documents.md section 4.4):
 * <ul>
 *   <li>{@code jabiz.documents.page-size}: {@code A4} or {@code LETTER}; by default Letter for the regions that print
 *       on it ({@code jabiz.region} in the United States, Canada, Mexico and the Philippines), A4 elsewhere</li>
 *   <li>{@code jabiz.documents.max-rows}: the most rows a template may give one document (default 2000)</li>
 *   <li>{@code jabiz.documents.max-bytes}: the largest PDF a document may be (default 10 MB)</li>
 * </ul>
 * A page size other than the two is reported by the startup check, and A4 is used meanwhile.
 */
@Component
public class DocumentSettings {

    private final String configuredPageSize;
    private final PdfDocumentWriter.PageSize pageSize;
    private final int maxRows;
    private final long maxBytes;

    public DocumentSettings(
        @Value("${jabiz.documents.page-size:}") String pageSize,
        @Value("${jabiz.region:}") String region,
        @Value("${jabiz.documents.max-rows:2000}") int maxRows,
        @Value("${jabiz.documents.max-bytes:10485760}") long maxBytes
    ) {
        if (maxRows < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("jabiz.documents.max-rows and max-bytes must be positive");
        }
        this.configuredPageSize = pageSize == null ? "" : pageSize.trim();
        this.pageSize = configuredPageSize.isEmpty() ? byRegion(region)
            : parse(configuredPageSize) == null ? PdfDocumentWriter.PageSize.A4 : parse(configuredPageSize);
        this.maxRows = maxRows;
        this.maxBytes = maxBytes;
    }

    static PdfDocumentWriter.PageSize parse(String value) {
        try {
            return PdfDocumentWriter.PageSize.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PdfDocumentWriter.PageSize byRegion(String region) {
        if (region == null || region.isBlank()) {
            return PdfDocumentWriter.PageSize.A4;
        }
        String country = Locale.forLanguageTag(region.trim()).getCountry();
        return switch (country) {
            case "US", "CA", "MX", "PH" -> PdfDocumentWriter.PageSize.LETTER;
            default -> PdfDocumentWriter.PageSize.A4;
        };
    }

    /** The page size as configured, or empty when it follows the region. */
    public String configuredPageSize() {
        return configuredPageSize;
    }

    public PdfDocumentWriter.PageSize pageSize() {
        return pageSize;
    }

    public int maxRows() {
        return maxRows;
    }

    public long maxBytes() {
        return maxBytes;
    }
}
