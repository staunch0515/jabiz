package com.jabiz.runtime.report;

import com.jabiz.report.ReportColumn;
import com.jabiz.report.ReportDocument;
import com.jabiz.report.ReportFormat;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Writes a report as PDF (docs/design/19-reports.md section 4). Every page has the header - company, run time,
 * title, period and parameters - the column labels, and a footer with the recorded time, the template version and
 * "page n of m". Amounts are right aligned with negatives in parentheses; a cell too wide for its column is cut with
 * an ellipsis. A long report is landscape when the template asks for it.
 *
 * <p>The output is deterministic: the document's dates and identifier come from the run time and the embedded font
 * subsets from the characters used, so the same document always gives the same bytes (report archives rely on it).
 */
public final class PdfReportWriter {

    private static final float MARGIN = 36;
    private static final float CELL_PADDING = 3;
    private static final float TABLE_SIZE = 8;
    private static final float ROW_HEIGHT = TABLE_SIZE * 1.6f;
    private static final float HEADER_SIZE = 8.5f;
    private static final float TITLE_SIZE = 14;
    private static final float FOOTER_SIZE = 7;
    private static final float MIN_COLUMN = 24;
    private static final String ELLIPSIS = "…";

    private PdfReportWriter() {}

    public static void write(ReportDocument document, ReportFormat format, ReportLabels labels, PdfFonts fonts,
        OutputStream out) {
        try (PDDocument pdf = new PDDocument()) {
            Fonts text = new Fonts(pdf, fonts);
            describe(pdf, document);
            PDRectangle size = document.landscape()
                ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
            List<ReportColumn> columns = document.columns();
            List<List<String>> cells = new ArrayList<>(document.rows().size());
            for (List<Object> row : document.rows()) {
                List<String> texts = new ArrayList<>(columns.size());
                for (int c = 0; c < columns.size(); c++) {
                    texts.add(format.value(columns.get(c), row.get(c)));
                }
                cells.add(texts);
            }
            float width = size.getWidth() - 2 * MARGIN;
            float[] widths = columnWidths(columns, cells, text, width);

            float headerHeight = HEADER_SIZE + TITLE_SIZE * 1.4f + HEADER_SIZE * 1.5f * 2 + ROW_HEIGHT * 1.5f;
            float tableTop = size.getHeight() - MARGIN - headerHeight;
            float footerTop = MARGIN + FOOTER_SIZE * 2;
            int rowsPerPage = Math.max(1, (int) ((tableTop - ROW_HEIGHT * 1.15f - footerTop) / ROW_HEIGHT));
            int pages = Math.max(1, (cells.size() + rowsPerPage - 1) / rowsPerPage);

            for (int page = 0; page < pages; page++) {
                PDPage pdfPage = new PDPage(size);
                pdf.addPage(pdfPage);
                try (PDPageContentStream content = new PDPageContentStream(pdf, pdfPage)) {
                    header(content, text, document, format, labels, size, width);
                    float y = tableTop;
                    tableRow(content, text, columns, columns.stream().map(ReportColumn::label).toList(), widths, y,
                        true);
                    // A rule between the labels' descenders and the first row's capitals.
                    content.setLineWidth(0.5f);
                    content.moveTo(MARGIN, y - ROW_HEIGHT * 0.3f);
                    content.lineTo(MARGIN + width, y - ROW_HEIGHT * 0.3f);
                    content.stroke();
                    y -= ROW_HEIGHT * 1.15f;
                    int end = Math.min(cells.size(), (page + 1) * rowsPerPage);
                    for (int r = page * rowsPerPage; r < end; r++) {
                        tableRow(content, text, columns, cells.get(r), widths, y, false);
                        y -= ROW_HEIGHT;
                    }
                    footer(content, text, document, format, labels, page + 1, pages, width);
                }
            }
            pdf.save(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Title, author and dates from the report, and an identifier from the run time: nothing from the clock. */
    private static void describe(PDDocument pdf, ReportDocument document) {
        PDDocumentInformation info = pdf.getDocumentInformation();
        Calendar created = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        created.setTimeInMillis(document.runTime().toEpochMilli());
        info.setTitle(document.title());
        info.setAuthor(document.company());
        info.setProducer("jabiz");
        info.setCreationDate(created);
        info.setModificationDate(created);
        pdf.setDocumentId(document.runTime().toEpochMilli());
    }

    private static void header(PDPageContentStream content, Fonts text, ReportDocument document, ReportFormat format,
        ReportLabels labels, PDRectangle size, float width) throws IOException {
        float y = size.getHeight() - MARGIN - HEADER_SIZE;
        String runAt = labels.runAt() + " " + format.dateTime(document.runTime());
        text.draw(content, fit(text, document.company(), false, HEADER_SIZE, width / 2), false, HEADER_SIZE,
            MARGIN, y);
        text.draw(content, runAt, false, HEADER_SIZE, MARGIN + width - text.width(runAt, false, HEADER_SIZE), y);
        y -= TITLE_SIZE * 1.4f;
        text.draw(content, fit(text, document.title(), true, TITLE_SIZE, width), true, TITLE_SIZE, MARGIN, y);
        y -= HEADER_SIZE * 1.5f;
        if (document.period() != null) {
            text.draw(content, fit(text, labels.period() + " " + document.period(), false, HEADER_SIZE, width), false,
                HEADER_SIZE, MARGIN, y);
        }
        y -= HEADER_SIZE * 1.5f;
        List<String> given = document.parameters().stream().map(p -> p.label() + ": " + p.value()).toList();
        text.draw(content, fit(text, String.join("   ", given), false, HEADER_SIZE, width), false, HEADER_SIZE,
            MARGIN, y);
    }

    private static void footer(PDPageContentStream content, Fonts text, ReportDocument document, ReportFormat format,
        ReportLabels labels, int page, int pages, float width) throws IOException {
        float y = MARGIN;
        String version = document.templateVersion() == null ? ""
            : "   " + labels.version() + " " + document.templateVersion().substring(0,
                Math.min(12, document.templateVersion().length()));
        String left = labels.knownAt() + " " + format.dateTime(document.knownAt()) + version;
        String right = labels.page(page, pages);
        text.draw(content, fit(text, left, false, FOOTER_SIZE, width * 0.75f), false, FOOTER_SIZE, MARGIN, y);
        text.draw(content, right, false, FOOTER_SIZE, MARGIN + width - text.width(right, false, FOOTER_SIZE), y);
    }

    private static void tableRow(PDPageContentStream content, Fonts text, List<ReportColumn> columns,
        List<String> values, float[] widths, float y, boolean bold) throws IOException {
        float x = MARGIN;
        for (int c = 0; c < columns.size(); c++) {
            float inner = widths[c] - 2 * CELL_PADDING;
            String value = fit(text, values.get(c), bold, TABLE_SIZE, inner);
            float offset = columns.get(c).numeric() ? inner - text.width(value, bold, TABLE_SIZE) : 0;
            text.draw(content, value, bold, TABLE_SIZE, x + CELL_PADDING + offset, y);
            x += widths[c];
        }
    }

    /**
     * Each column as wide as its widest text. When they do not fit the page, the narrow columns (amounts, dates,
     * codes) keep their width and the widest share what is left equally, down to a minimum: long texts are cut
     * before any amount is.
     */
    static float[] columnWidths(List<ReportColumn> columns, List<List<String>> cells, Fonts text, float available) {
        float[] widths = new float[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            float widest = text.width(columns.get(c).label(), true, TABLE_SIZE);
            for (List<String> row : cells) {
                widest = Math.max(widest, text.width(row.get(c), false, TABLE_SIZE));
            }
            // One point of slack: the widest text must still fit after the padding is taken off again.
            widths[c] = Math.max(MIN_COLUMN, widest + 2 * CELL_PADDING + 1);
        }
        return fitted(widths, available);
    }

    /** Max-min fair shares of the available width: a column never gets more than it needs. */
    static float[] fitted(float[] natural, float available) {
        float total = 0;
        for (float w : natural) {
            total += w;
        }
        if (total <= available) {
            return natural;
        }
        Integer[] order = new Integer[natural.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(natural[a], natural[b]));
        float[] widths = new float[natural.length];
        float remaining = available;
        for (int k = 0; k < order.length; k++) {
            float share = remaining / (order.length - k);
            int column = order[k];
            widths[column] = Math.max(MIN_COLUMN, Math.min(natural[column], share));
            remaining -= widths[column];
        }
        return widths;
    }

    /** The text, cut with an ellipsis to fit the width. */
    static String fit(Fonts text, String value, boolean bold, float size, float width) {
        if (value == null || value.isEmpty() || text.width(value, bold, size) <= width) {
            return value == null ? "" : value;
        }
        int end = value.length();
        while (end > 0 && text.width(value.substring(0, end) + ELLIPSIS, bold, size) > width) {
            end = value.offsetByCodePoints(end, -1);
        }
        return end == 0 ? "" : value.substring(0, end) + ELLIPSIS;
    }

    /**
     * The fonts of one document: Noto Sans (regular or bold), then the fallbacks, chosen per character. A character
     * no font has is shown as a question mark rather than failing the report.
     */
    static final class Fonts {

        private final PDType0Font regular;
        private final PDType0Font bold;
        private final List<PDType0Font> fallbacks = new ArrayList<>();
        private final Map<Integer, Integer> regularChoice = new HashMap<>();
        private final Map<Integer, Integer> boldChoice = new HashMap<>();

        Fonts(PDDocument pdf, PdfFonts fonts) throws IOException {
            this.regular = PDType0Font.load(pdf, new ByteArrayInputStream(fonts.regular()), true);
            this.bold = PDType0Font.load(pdf, new ByteArrayInputStream(fonts.bold()), true);
            for (byte[] fallback : fonts.fallbacks()) {
                fallbacks.add(PDType0Font.load(pdf, new ByteArrayInputStream(fallback), true));
            }
        }

        /** Runs of the text, each with the font that draws it; a character no font has becomes a question mark. */
        private List<Map.Entry<PDType0Font, String>> runs(String value, boolean strong) {
            List<Map.Entry<PDType0Font, String>> runs = new ArrayList<>();
            PDType0Font current = null;
            StringBuilder run = new StringBuilder();
            for (int i = 0; i < value.length(); ) {
                int codePoint = value.codePointAt(i);
                i += Character.charCount(codePoint);
                PDType0Font font = fontFor(codePoint, strong);
                String character = font == null ? "?" : new String(Character.toChars(codePoint));
                if (font == null) {
                    font = strong ? bold : regular;
                }
                if (font != current && !run.isEmpty()) {
                    runs.add(Map.entry(current, run.toString()));
                    run.setLength(0);
                }
                current = font;
                run.append(character);
            }
            if (!run.isEmpty()) {
                runs.add(Map.entry(current, run.toString()));
            }
            return runs;
        }

        private PDType0Font fontFor(int codePoint, boolean strong) {
            if (Character.isISOControl(codePoint)) {
                return null;
            }
            Map<Integer, Integer> choices = strong ? boldChoice : regularChoice;
            int choice = choices.computeIfAbsent(codePoint, cp -> {
                String character = new String(Character.toChars(cp));
                if (encodes(strong ? bold : regular, character)) {
                    return 0;
                }
                for (int f = 0; f < fallbacks.size(); f++) {
                    if (encodes(fallbacks.get(f), character)) {
                        return f + 1;
                    }
                }
                return -1;
            });
            return choice < 0 ? null : choice == 0 ? (strong ? bold : regular) : fallbacks.get(choice - 1);
        }

        /** Whether the font has a glyph for the character: PDFBox refuses to encode one it lacks. */
        private static boolean encodes(PDType0Font font, String character) {
            try {
                font.encode(character);
                return true;
            } catch (IllegalArgumentException | IOException e) {
                return false;
            }
        }

        float width(String value, boolean strong, float size) {
            float width = 0;
            try {
                for (Map.Entry<PDType0Font, String> run : runs(value, strong)) {
                    width += run.getKey().getStringWidth(run.getValue()) / 1000 * size;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return width;
        }

        void draw(PDPageContentStream content, String value, boolean strong, float size, float x, float y)
            throws IOException {
            if (value == null || value.isEmpty()) {
                return;
            }
            content.beginText();
            content.newLineAtOffset(x, y);
            for (Map.Entry<PDType0Font, String> run : runs(value, strong)) {
                content.setFont(run.getKey(), size);
                content.showText(run.getValue());
            }
            content.endText();
        }
    }
}
