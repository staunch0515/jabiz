package com.jabiz.runtime.report;

import com.jabiz.report.ReportFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/design/19-reports.md section 4: the page header and footer on every page, amounts in parentheses, and the
 * same document giving the same bytes.
 */
class PdfReportWriterTest {

    private static final PdfFonts FONTS = PdfFonts.load(List.of());
    private static final ReportFormat FORMAT = new ReportFormat("en-US", ZoneOffset.UTC);

    private static byte[] pdf(com.jabiz.report.ReportDocument document, PdfFonts fonts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfReportWriter.write(document, FORMAT, ReportLabels.ENGLISH, fonts, out);
        return out.toByteArray();
    }

    @Test
    void everyPageHasTheHeaderAndItsNumber() throws Exception {
        byte[] bytes = pdf(ReportSamples.trialBalance(120, false), FONTS);

        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pages = document.getNumberOfPages();
            assertThat(pages).isGreaterThan(1);
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("Trial balance");
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                assertThat(text).contains("Acme Inc.", "Trial balance", "Period 2026-02-01", "asOf: 2026-02-05",
                    "Account name", "Balance", "Page " + page + " of " + pages, "As known on", "Version c2741d77d343");
            }
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String first = stripper.getText(document);
            assertThat(first).contains("(5,000.00)", "12,345.50", "0.05", "Accounts payable", "…");
        }
    }

    @Test
    void narrowColumnsKeepTheirWidthWhenTheWidestAreCut() {
        assertThat(PdfReportWriter.fitted(new float[] {30, 400, 60, 50}, 300))
            .containsExactly(30f, 160f, 60f, 50f);
        assertThat(PdfReportWriter.fitted(new float[] {30, 40}, 300)).containsExactly(30f, 40f);
        assertThat(PdfReportWriter.fitted(new float[] {200, 200}, 300)).containsExactly(150f, 150f);
    }

    @Test
    void theSameDocumentGivesTheSameBytes() {
        assertThat(pdf(ReportSamples.trialBalance(10, true), FONTS))
            .isEqualTo(pdf(ReportSamples.trialBalance(10, true), FONTS));
    }

    @Test
    void landscapePagesAreWider() throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf(ReportSamples.trialBalance(0, true), FONTS))) {
            assertThat(document.getPage(0).getMediaBox().getWidth())
                .isGreaterThan(document.getPage(0).getMediaBox().getHeight());
        }
    }

    @Test
    void charactersNoFontHasBecomeQuestionMarksAndFallbacksAreRead(@TempDir Path dir) throws Exception {
        Path copy = dir.resolve("fallback.ttf");
        Files.write(copy, FONTS.regular());
        PdfFonts withFallback = PdfFonts.load(List.of(copy.toString(), " "));
        assertThat(withFallback.fallbacks()).hasSize(1);

        com.jabiz.report.ReportDocument sample = ReportSamples.trialBalance(0, false);
        com.jabiz.report.ReportDocument chinese = new com.jabiz.report.ReportDocument(sample.templateId(),
            sample.templateVersion(), "试算表 Trial", sample.company(), null, List.of(), sample.runTime(), null, false,
            sample.columns(), sample.rows());
        try (PDDocument document = Loader.loadPDF(pdf(chinese, withFallback))) {
            assertThat(new PDFTextStripper().getText(document)).contains("??? Trial");
        }
        assertThatThrownBy(() -> PdfFonts.load(List.of(dir.resolve("missing.ttf").toString())))
            .hasMessageContaining("missing.ttf");
    }
}
