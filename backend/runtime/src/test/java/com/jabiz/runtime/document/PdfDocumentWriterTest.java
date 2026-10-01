package com.jabiz.runtime.document;

import com.jabiz.document.DocumentContent;
import com.jabiz.document.DocumentLayout;
import com.jabiz.entity.SemanticKind;
import com.jabiz.report.ReportColumn;
import com.jabiz.report.ReportFormat;
import com.jabiz.runtime.report.PdfFonts;
import com.jabiz.runtime.report.PdfReportWriter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * docs/design/22-documents.md section 3.3: the blocks of a layout on the page, a long table over several pages with
 * its header on each, long texts wrapped and not cut, the footer on every page, a preview marked as one, and the same
 * content giving the same bytes.
 */
class PdfDocumentWriterTest {

    private static final PdfFonts FONTS = PdfFonts.load(List.of());
    private static final ReportFormat FORMAT = new ReportFormat("en-US", ZoneOffset.UTC);
    private static final Instant ISSUED = Instant.parse("2026-01-06T15:30:00Z");

    private static final DocumentLayout LAYOUT = DocumentLayout.define("fin.invoice", d -> d
        .permissions("fin.ar.read")
        .number("h", "invoiceNo")
        .party("seller", "c", "name", "address")
        .party("billTo", "h", "customer", "billingAddress")
        .facts("h", "invoiceNo", "invoiceDate", "terms")
        .table("l", "line", "description", "quantity", "amount")
        .totals("h", "subtotal", "tax", "total")
        .text("remittance", "c", "remittance")
        .note("thanks"));

    private static DocumentContent content(int lines, String description, boolean preview) {
        DocumentContent.Source company = new DocumentContent.Source("c", "v1", List.of(
            new ReportColumn("name", "Name", text()), new ReportColumn("address", "Address", text()),
            new ReportColumn("remittance", "Remittance", text())),
            List.of(List.of("Acme Supply Inc.", "100 Main St\nAustin, TX 78701",
                "Pay by ACH to account 123456789 at First Bank, routing 111000025. Quote the invoice number with "
                    + "every payment so that it can be applied without delay; payments without it are held.")));
        List<Object> header = new ArrayList<>();
        header.add("INV-1004");
        header.add("Bluebonnet Retail LLC");
        header.add("200 Congress Ave\nAustin, TX 78701");
        header.add(LocalDate.parse("2026-01-06"));
        header.add("Net 30");
        header.add(new BigDecimal("50000.00"));
        header.add(new BigDecimal("3300.00"));
        header.add(new BigDecimal("53300.00"));
        DocumentContent.Source head = new DocumentContent.Source("h", "v1", List.of(
            new ReportColumn("invoiceNo", "Invoice", text()), new ReportColumn("customer", "Customer", text()),
            new ReportColumn("billingAddress", "Billing address", text()),
            new ReportColumn("invoiceDate", "Invoice date", new SemanticKind.Date()),
            new ReportColumn("terms", "Terms", text()), money("subtotal", "Subtotal"), money("tax", "Sales tax"),
            money("total", "Total due")), List.of(header));
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= lines; i++) {
            rows.add(List.of(BigDecimal.valueOf(i), i == 1 ? description : "Component " + i, BigDecimal.TEN,
                new BigDecimal("-1234.50")));
        }
        DocumentContent.Source table = new DocumentContent.Source("l", "v1", List.of(
            new ReportColumn("line", "Line", new SemanticKind.Numeric(4, 0)),
            new ReportColumn("description", "Description", text()),
            new ReportColumn("quantity", "Qty", new SemanticKind.Numeric(9, 0)), money("amount", "Amount")), rows);
        return new DocumentContent("fin.invoice", LAYOUT.version(), "Invoice", "INV-1004", "Acme Supply Inc.", "en",
            ISSUED, preview, Map.of("document.fin.invoice.seller", "From", "document.fin.invoice.billTo", "Bill to",
                "document.fin.invoice.remittance", "Remittance instructions",
                "document.fin.invoice.thanks", "Thank you for your business."),
            Map.of("c", company, "h", head, "l", table));
    }

    private static SemanticKind text() {
        return new SemanticKind.Text(500, true);
    }

    private static ReportColumn money(String name, String label) {
        return new ReportColumn(name, label, new SemanticKind.Monetary("USD", 2));
    }

    private static byte[] pdf(DocumentContent content, PdfDocumentWriter.PageSize size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfDocumentWriter.write(LAYOUT, content, FORMAT, PdfDocumentWriter.Labels.ENGLISH, FONTS, size, out);
        return out.toByteArray();
    }

    @Test
    void theBlocksAreOnThePageInTheirOrder() throws Exception {
        byte[] bytes = pdf(content(3, "Components", false), PdfDocumentWriter.PageSize.LETTER);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            PDRectangle size = document.getPage(0).getMediaBox();
            assertThat(size.getWidth()).isEqualTo(PDRectangle.LETTER.getWidth(), within(0.01f));
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("Invoice INV-1004");
            assertThat(document.getDocumentInformation().getAuthor()).isEqualTo("Acme Supply Inc.");
            assertThat(document.getDocumentInformation().getCreationDate().toInstant()).isEqualTo(ISSUED);
            String text = new PDFTextStripper().getText(document);
            assertThat(text).containsSubsequence("Invoice", "From", "Acme Supply Inc.", "100 Main St",
                "Austin, TX 78701", "Bill to", "Bluebonnet Retail LLC", "Invoice", "INV-1004", "Invoice date",
                "Jan 6, 2026", "Terms", "Net 30", "Line", "Description", "Qty", "Amount", "Components",
                "(1,234.50)", "Subtotal", "50,000.00", "Sales tax", "3,300.00", "Total due", "53,300.00",
                "Remittance instructions", "Pay by ACH", "Thank you for your business.", "Invoice INV-1004",
                "Page 1 of 1");
            assertThat(text).doesNotContain("PREVIEW", "…");
        }
    }

    @Test
    void aLongTableGoesOnOverPagesUnderItsHeaderAndEveryPageHasTheFooter() throws Exception {
        byte[] bytes = pdf(content(140, "Components", false), PdfDocumentWriter.PageSize.A4);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pages = document.getNumberOfPages();
            assertThat(pages).isGreaterThan(2);
            assertThat(document.getPage(0).getMediaBox().getHeight())
                .isEqualTo(PDRectangle.A4.getHeight(), within(0.01f));
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                assertThat(text).contains("Invoice INV-1004", "Page " + page + " of " + pages);
                if (page < pages) {
                    assertThat(text).contains("Description", "Qty", "Amount");
                }
            }
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            String all = stripper.getText(document);
            for (int i = 2; i <= 140; i++) {
                assertThat(all).contains("Component " + i + " ");
            }
            stripper.setStartPage(pages);
            assertThat(stripper.getText(document)).contains("Total due", "Thank you for your business.");
        }
    }

    @Test
    void aLongTextWrapsAndNothingIsCut() throws Exception {
        String words = "Sixteen kilograms of assorted stainless fasteners, washers and brackets for the north "
            + "assembly line, delivered to dock four and signed for by the receiving supervisor on duty";
        String unbroken = "X".repeat(180);
        byte[] bytes = pdf(content(2, words + " " + unbroken, false), PdfDocumentWriter.PageSize.A4);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
            for (String word : words.split(" ")) {
                assertThat(text).contains(word);
            }
            assertThat(text.replace(" ", "")).contains(unbroken);
            assertThat(text).doesNotContain("…");
        }
    }

    @Test
    void aWordWiderThanItsColumnStillMovesOnCharacterByCharacter() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PdfReportWriter.Fonts fonts = new PdfReportWriter.Fonts(pdf, FONTS);
            // A column narrower than one character, a surrogate pair first: one character per line, never stuck.
            assertThat(PdfDocumentWriter.wrap(fonts, "\uD83D\uDE00ab", false, 9, 1))
                .containsExactly("\uD83D\uDE00", "a", "b");
        }
    }

    @Test
    void partiesTallerThanAPageGoOnOverPagesWithoutLosingALine() throws Exception {
        StringBuilder address = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            address.append("Address line ").append(i).append('\n');
        }
        DocumentContent tall = content(2, "Components", false);
        DocumentContent.Source head = tall.source("h");
        List<List<Object>> rows = new ArrayList<>();
        List<Object> row = new ArrayList<>(head.rows().getFirst());
        row.set(2, address.toString());
        rows.add(row);
        Map<String, DocumentContent.Source> sources = new java.util.LinkedHashMap<>(tall.sources());
        sources.put("h", new DocumentContent.Source("h", "v1", head.columns(), rows));
        DocumentContent content = new DocumentContent(tall.layoutId(), tall.layoutVersion(), tall.title(),
            tall.number(), tall.company(), tall.language(), tall.issuedTime(), false, tall.labels(), sources);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfDocumentWriter.write(LAYOUT, content, FORMAT, PdfDocumentWriter.Labels.ENGLISH, FONTS,
            PdfDocumentWriter.PageSize.A4, out);
        try (PDDocument document = Loader.loadPDF(out.toByteArray())) {
            assertThat(document.getNumberOfPages()).isGreaterThan(2);
            String text = new PDFTextStripper().getText(document);
            for (int i = 1; i <= 200; i++) {
                assertThat(text).contains("Address line " + i + System.lineSeparator());
            }
            assertThat(text).contains("Total due", "Thank you for your business.");
        }
    }

    @Test
    void aPreviewSaysSoOnEveryPage() throws Exception {
        byte[] bytes = pdf(content(140, "Components", true), PdfDocumentWriter.PageSize.A4);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                assertThat(stripper.getText(document)).contains("PREVIEW");
            }
        }
    }

    @Test
    void theSameContentGivesTheSameBytes() {
        assertThat(pdf(content(60, "Components", false), PdfDocumentWriter.PageSize.A4))
            .isEqualTo(pdf(content(60, "Components", false), PdfDocumentWriter.PageSize.A4));
        assertThat(pdf(content(60, "Components", false), PdfDocumentWriter.PageSize.A4))
            .isNotEqualTo(pdf(content(60, "Components!", false), PdfDocumentWriter.PageSize.A4));
    }

    @Test
    void amountsKeepTheirWidthAndTextColumnsShareTheRest() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PdfReportWriter.Fonts fonts = new PdfReportWriter.Fonts(pdf, FONTS);
            List<ReportColumn> columns = List.of(new ReportColumn("d", "Description", text()), money("a", "Amount"));
            float[] spacious = PdfDocumentWriter.widths(fonts, columns, List.of(List.of("Short", "1,234.50")), 500);
            assertThat(spacious[0] + spacious[1]).isEqualTo(500, within(0.01f));
            float amount = spacious[1];
            float[] crowded = PdfDocumentWriter.widths(fonts, columns,
                List.of(List.of("Y".repeat(400), "1,234.50")), 500);
            assertThat(crowded[1]).isEqualTo(amount, within(0.01f));
            assertThat(crowded[0] + crowded[1]).isLessThanOrEqualTo(500.01f);
            List<ReportColumn> many = new ArrayList<>();
            List<String> row = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                many.add(money("a" + i, "Amount " + i));
                row.add("1,234,567.89");
            }
            float total = 0;
            for (float w : PdfDocumentWriter.widths(fonts, many, List.of(row), 500)) {
                total += w;
            }
            assertThat(total).isLessThanOrEqualTo(500.01f);
            assertThat(PdfDocumentWriter.wrap(fonts, "a\nb c", false, 9, 500)).containsExactly("a", "b c");
            assertThat(PdfDocumentWriter.wrap(fonts, null, false, 9, 500)).containsExactly("");
        }
    }

    @Test
    void theSettingsFollowTheRegionUnlessAPageSizeIsGiven() {
        assertThat(new DocumentSettings("", "en-US", 10, 10).pageSize()).isEqualTo(PdfDocumentWriter.PageSize.LETTER);
        assertThat(new DocumentSettings("", "ja-JP", 10, 10).pageSize()).isEqualTo(PdfDocumentWriter.PageSize.A4);
        assertThat(new DocumentSettings("", "", 10, 10).pageSize()).isEqualTo(PdfDocumentWriter.PageSize.A4);
        assertThat(new DocumentSettings("letter", "ja-JP", 10, 10).pageSize())
            .isEqualTo(PdfDocumentWriter.PageSize.LETTER);
        DocumentSettings wrong = new DocumentSettings("B5", "en-US", 10, 10);
        assertThat(wrong.pageSize()).isEqualTo(PdfDocumentWriter.PageSize.A4);
        assertThat(DocumentSettings.parse(wrong.configuredPageSize())).isNull();
    }
}
