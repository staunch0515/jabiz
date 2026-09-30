package com.jabiz.runtime.report;

import com.jabiz.report.ReportFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;

/**
 * Settings of report exports (docs/design/19-reports.md section 4):
 * <ul>
 *   <li>{@code jabiz.reports.company}: who issues the reports, in their page header (default: the application name)</li>
 *   <li>{@code jabiz.reports.zone}: the zone times are shown in (default UTC)</li>
 *   <li>{@code jabiz.region}: the region numbers and times follow, written into the jar from
 *       {@code jabizApp { region }} (decision D22 item 7)</li>
 *   <li>{@code jabiz.reports.export.max-rows}: the most rows an export may have (default 100000)</li>
 *   <li>{@code jabiz.reports.pdf.fonts}: font files for characters Noto Sans lacks, such as Chinese or Japanese</li>
 * </ul>
 */
@Component
public class ReportSettings {

    private final String company;
    private final ReportFormat format;
    private final int maxRows;
    private final List<String> fontPaths;
    private volatile PdfFonts fonts;

    public ReportSettings(
        @Value("${jabiz.reports.company:${spring.application.name:}}") String company,
        @Value("${jabiz.reports.zone:UTC}") String zone,
        @Value("${jabiz.region:}") String region,
        @Value("${jabiz.reports.export.max-rows:100000}") int maxRows,
        @Value("${jabiz.reports.pdf.fonts:}") List<String> fontPaths
    ) {
        if (maxRows < 1) {
            throw new IllegalArgumentException("jabiz.reports.export.max-rows must be positive, was " + maxRows);
        }
        this.company = company;
        this.format = new ReportFormat(region, ZoneId.of(zone));
        this.maxRows = maxRows;
        this.fontPaths = List.copyOf(fontPaths);
    }

    public String company() {
        return company;
    }

    public ReportFormat format() {
        return format;
    }

    public int maxRows() {
        return maxRows;
    }

    public boolean hasFallbackFonts() {
        return fontPaths.stream().anyMatch(path -> !path.isBlank());
    }

    /** The fonts, read on first use (they are only needed for PDF). */
    public PdfFonts fonts() {
        PdfFonts loaded = fonts;
        if (loaded == null) {
            synchronized (this) {
                if (fonts == null) {
                    fonts = PdfFonts.load(fontPaths);
                }
                loaded = fonts;
            }
        }
        return loaded;
    }
}
