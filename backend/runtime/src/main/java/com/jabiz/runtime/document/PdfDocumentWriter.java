package com.jabiz.runtime.document;

import com.jabiz.document.DocumentContent;
import com.jabiz.document.DocumentLayout;
import com.jabiz.report.ReportColumn;
import com.jabiz.report.ReportFormat;
import com.jabiz.runtime.report.PdfFonts;
import com.jabiz.runtime.report.PdfReportWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Lays a document out as PDF (docs/design/22-documents.md section 3.3): the title and number at the top of the first
 * page, the blocks of the layout top to bottom - parties and facts side by side, tables across the page with their
 * header repeated on every page, totals at the right, texts as paragraphs - and on every page a footer with the number
 * and "page n of m". Long texts wrap; nothing a document shows is cut. A preview says so on every page.
 *
 * <p>The output is deterministic, as for reports: the document's dates and identifier come from the issue time and
 * the embedded font subsets from the characters used.
 */
public final class PdfDocumentWriter {

    /** The paper a document is printed on. */
    public enum PageSize {
        A4(PDRectangle.A4), LETTER(PDRectangle.LETTER);

        private final PDRectangle rectangle;

        PageSize(PDRectangle rectangle) {
            this.rectangle = rectangle;
        }
    }

    /**
     * The fixed texts of a document's pages in its language.
     *
     * @param page    the page number, with {@code {page}} and {@code {pages}} placeholders
     * @param preview the mark of a preview
     */
    public record Labels(String page, String preview) {

        public static final Labels ENGLISH = new Labels("Page {page} of {pages}", "PREVIEW");

        String page(int page, int pages) {
            return this.page.replace("{page}", Integer.toString(page)).replace("{pages}", Integer.toString(pages));
        }
    }

    private static final float MARGIN = 48;
    private static final float TITLE_SIZE = 18;
    private static final float NUMBER_SIZE = 12;
    private static final float LABEL_SIZE = 8;
    private static final float TEXT_SIZE = 9.5f;
    private static final float TABLE_SIZE = 9;
    private static final float FOOTER_SIZE = 7.5f;
    private static final float LEADING = 1.35f;
    private static final float GAP = 14;
    private static final float CELL_PADDING = 3;
    private static final float MIN_COLUMN = 30;
    private static final int MAX_SIDE_BY_SIDE = 3;

    private PdfDocumentWriter() {}

    /** @return the number of pages */
    public static int write(DocumentLayout layout, DocumentContent content, ReportFormat format, Labels labels,
        PdfFonts fonts, PageSize pageSize, OutputStream out) {
        try (PDDocument pdf = new PDDocument()) {
            PdfReportWriter.Fonts text = new PdfReportWriter.Fonts(pdf, fonts);
            describe(pdf, content);
            Pages pages = new Pages(pdf, text, pageSize.rectangle);
            pages.newPage();
            title(pages, content);
            List<DocumentLayout.Block> row = new ArrayList<>();
            for (DocumentLayout.Block block : layout.blocks()) {
                boolean sideBySide = block instanceof DocumentLayout.Party || block instanceof DocumentLayout.Facts;
                if (sideBySide) {
                    row.add(block);
                    if (row.size() == MAX_SIDE_BY_SIDE) {
                        sideBySide(pages, layout, content, format, row);
                        row.clear();
                    }
                    continue;
                }
                if (!row.isEmpty()) {
                    sideBySide(pages, layout, content, format, row);
                    row.clear();
                }
                switch (block) {
                    case DocumentLayout.Table t -> table(pages, content.source(t.template()), t.columns(), format);
                    case DocumentLayout.Totals t -> totals(pages, content.source(t.template()), t.columns(), format);
                    case DocumentLayout.Text t -> paragraph(pages, content.label(layout.labelKey(t.key()), t.key()),
                        format.value(content.source(t.template()).column(t.column()),
                            content.source(t.template()).first(t.column())));
                    case DocumentLayout.Note n -> paragraph(pages, null, content.label(layout.labelKey(n.key()), ""));
                    default -> throw new IllegalStateException("Unexpected block " + block);
                }
            }
            if (!row.isEmpty()) {
                sideBySide(pages, layout, content, format, row);
            }
            pages.close();
            footers(pdf, text, content, labels, pageSize.rectangle);
            pdf.save(out);
            return pdf.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Title, author and dates from the document, and an identifier from the issue time: nothing from the clock. */
    private static void describe(PDDocument pdf, DocumentContent content) {
        PDDocumentInformation info = pdf.getDocumentInformation();
        Calendar created = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        created.setTimeInMillis(content.issuedTime().toEpochMilli());
        info.setTitle(content.number() == null ? content.title() : content.title() + " " + content.number());
        info.setAuthor(content.company());
        info.setProducer("jabiz");
        info.setCreationDate(created);
        info.setModificationDate(created);
        pdf.setDocumentId(content.issuedTime().toEpochMilli());
    }

    private static void title(Pages pages, DocumentContent content) throws IOException {
        float y = pages.y - TITLE_SIZE;
        pages.text.draw(pages.stream, content.title(), true, TITLE_SIZE, MARGIN, y);
        if (content.number() != null) {
            float w = pages.text.width(content.number(), true, NUMBER_SIZE);
            pages.text.draw(pages.stream, content.number(), true, NUMBER_SIZE, MARGIN + pages.width - w, y);
        }
        pages.y = y - TITLE_SIZE * 0.6f - GAP;
    }

    /** Parties and facts next to each other, each in an equal share of the width. */
    private static void sideBySide(Pages pages, DocumentLayout layout, DocumentContent content, ReportFormat format,
        List<DocumentLayout.Block> blocks) throws IOException {
        float share = (pages.width - GAP * (blocks.size() - 1)) / blocks.size();
        List<List<Line>> columns = new ArrayList<>();
        float tallest = 0;
        for (DocumentLayout.Block block : blocks) {
            List<Line> lines = switch (block) {
                case DocumentLayout.Party p -> party(pages, content.label(layout.labelKey(p.key()), p.key()),
                    content.source(p.template()), p.columns(), format, share);
                case DocumentLayout.Facts f -> facts(pages, content.source(f.template()), f.columns(), format, share);
                default -> throw new IllegalStateException("Unexpected block " + block);
            };
            columns.add(lines);
            tallest = Math.max(tallest, height(lines));
        }
        if (tallest > pages.fullPage()) {
            // Taller than a page (a very long address): one block after the other, continued on the next pages.
            for (DocumentLayout.Block block : blocks) {
                List<Line> lines = switch (block) {
                    case DocumentLayout.Party p -> party(pages, content.label(layout.labelKey(p.key()), p.key()),
                        content.source(p.template()), p.columns(), format, pages.width);
                    case DocumentLayout.Facts f -> facts(pages, content.source(f.template()), f.columns(), format,
                        pages.width);
                    default -> throw new IllegalStateException("Unexpected block " + block);
                };
                for (Line line : lines) {
                    if (!pages.fits(line.size * LEADING)) {
                        pages.newPage();
                    }
                    pages.y -= line.size * LEADING;
                    drawLine(pages, line, MARGIN, pages.y);
                }
                pages.y -= GAP;
            }
            return;
        }
        pages.room(tallest);
        float x = MARGIN;
        for (List<Line> lines : columns) {
            float y = pages.y;
            for (Line line : lines) {
                y -= line.size * LEADING;
                drawLine(pages, line, x, y);
            }
            x += share + GAP;
        }
        pages.y -= tallest + GAP;
    }

    private static void drawLine(Pages pages, Line line, float x, float y) throws IOException {
        for (Cell cell : line.cells) {
            float left = x + cell.offset;
            float at = cell.right ? left + cell.width - pages.text.width(cell.text, cell.bold, line.size) : left;
            pages.text.draw(pages.stream, cell.text, cell.bold, line.size, at, y);
        }
    }

    private static List<Line> party(Pages pages, String label, DocumentContent.Source source, List<String> columns,
        ReportFormat format, float width) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(LABEL_SIZE, List.of(new Cell(label, true, 0, width, false))));
        for (String column : columns) {
            String value = format.value(source.column(column), source.first(column));
            for (String wrapped : wrap(pages.text, value, false, TEXT_SIZE, width)) {
                if (!wrapped.isEmpty()) {
                    lines.add(new Line(TEXT_SIZE, List.of(new Cell(wrapped, false, 0, width, false))));
                }
            }
        }
        return lines;
    }

    /** Label and value, the labels in a column as wide as the widest of them (at most half the block). */
    private static List<Line> facts(Pages pages, DocumentContent.Source source, List<String> columns,
        ReportFormat format, float width) {
        float labelWidth = 0;
        for (String column : columns) {
            labelWidth = Math.max(labelWidth, pages.text.width(source.column(column).label(), true, TEXT_SIZE));
        }
        labelWidth = Math.min(labelWidth + 8, width * 0.5f);
        float valueWidth = width - labelWidth;
        List<Line> lines = new ArrayList<>();
        for (String column : columns) {
            ReportColumn col = source.column(column);
            List<String> labelLines = wrap(pages.text, col.label(), true, TEXT_SIZE, labelWidth - 8);
            List<String> valueLines = wrap(pages.text, format.value(col, source.first(column)), false, TEXT_SIZE,
                valueWidth);
            for (int i = 0; i < Math.max(labelLines.size(), valueLines.size()); i++) {
                List<Cell> cells = new ArrayList<>();
                if (i < labelLines.size()) {
                    cells.add(new Cell(labelLines.get(i), true, 0, labelWidth, false));
                }
                if (i < valueLines.size()) {
                    cells.add(new Cell(valueLines.get(i), false, labelWidth, valueWidth, false));
                }
                lines.add(new Line(TEXT_SIZE, cells));
            }
        }
        return lines;
    }

    /** The rows across the page; a row too long for the page moves to the next, under the header repeated. */
    private static void table(Pages pages, DocumentContent.Source source, List<String> columns, ReportFormat format)
        throws IOException {
        List<ReportColumn> shown = columns.stream().map(source::column).toList();
        List<List<String>> cells = new ArrayList<>();
        for (List<Object> row : source.rows()) {
            List<String> texts = new ArrayList<>();
            for (int c = 0; c < shown.size(); c++) {
                texts.add(format.value(shown.get(c), row.get(source.index(columns.get(c)).orElseThrow())));
            }
            cells.add(texts);
        }
        float[] widths = widths(pages.text, shown, cells, pages.width);
        float lineHeight = TABLE_SIZE * LEADING;
        List<List<String>> header = wrapRow(pages.text, shown.stream().map(ReportColumn::label).toList(), shown,
            widths, true);
        float headerHeight = rowHeight(header) + lineHeight * 0.4f;
        List<List<List<String>>> wrappedRows = new ArrayList<>();
        for (List<String> row : cells) {
            wrappedRows.add(wrapRow(pages.text, row, shown, widths, false));
        }
        float firstRow = wrappedRows.isEmpty() ? 0 : rowHeight(wrappedRows.getFirst());
        pages.room(headerHeight + firstRow);
        tableHeader(pages, header, shown, widths, headerHeight);
        for (List<List<String>> row : wrappedRows) {
            float height = rowHeight(row);
            if (!pages.fits(height)) {
                pages.newPage();
                tableHeader(pages, header, shown, widths, headerHeight);
            }
            tableRow(pages, row, shown, widths, false);
            pages.y -= height;
        }
        pages.y -= GAP;
    }

    private static void tableHeader(Pages pages, List<List<String>> header, List<ReportColumn> shown, float[] widths,
        float height) throws IOException {
        tableRow(pages, header, shown, widths, true);
        float rule = pages.y - height + TABLE_SIZE * LEADING * 0.25f;
        pages.stream.setLineWidth(0.6f);
        pages.stream.moveTo(MARGIN, rule);
        pages.stream.lineTo(MARGIN + pages.width, rule);
        pages.stream.stroke();
        pages.y -= height;
    }

    /** One table row, each cell's lines from the top, amounts right aligned. */
    private static void tableRow(Pages pages, List<List<String>> row, List<ReportColumn> shown, float[] widths,
        boolean bold) throws IOException {
        float x = MARGIN;
        for (int c = 0; c < shown.size(); c++) {
            float y = pages.y;
            float inner = widths[c] - 2 * CELL_PADDING;
            for (String line : row.get(c)) {
                y -= TABLE_SIZE * LEADING;
                float offset = shown.get(c).numeric() ? inner - pages.text.width(line, bold, TABLE_SIZE) : 0;
                pages.text.draw(pages.stream, line, bold, TABLE_SIZE, x + CELL_PADDING + offset, y);
            }
            x += widths[c];
        }
    }

    private static List<List<String>> wrapRow(PdfReportWriter.Fonts text, List<String> values,
        List<ReportColumn> shown, float[] widths, boolean bold) {
        List<List<String>> row = new ArrayList<>();
        for (int c = 0; c < shown.size(); c++) {
            row.add(wrap(text, values.get(c), bold, TABLE_SIZE, widths[c] - 2 * CELL_PADDING));
        }
        return row;
    }

    private static float rowHeight(List<List<String>> row) {
        int lines = 1;
        for (List<String> cell : row) {
            lines = Math.max(lines, cell.size());
        }
        return lines * TABLE_SIZE * LEADING;
    }

    /**
     * Amounts, numbers, dates and times keep the width of their widest value (they never wrap); the other columns
     * share the rest in proportion to their widest value, at least {@link #MIN_COLUMN} each, and wrap.
     */
    static float[] widths(PdfReportWriter.Fonts text, List<ReportColumn> columns, List<List<String>> cells,
        float available) {
        float[] natural = new float[columns.size()];
        boolean[] fixed = new boolean[columns.size()];
        float fixedTotal = 0;
        float freeTotal = 0;
        for (int c = 0; c < columns.size(); c++) {
            ReportColumn column = columns.get(c);
            fixed[c] = column.numeric() || column.temporal() || column.date();
            float widest = fixed[c] ? text.width(column.label(), true, TABLE_SIZE) : 0;
            for (List<String> row : cells) {
                widest = Math.max(widest, text.width(row.get(c), false, TABLE_SIZE));
            }
            if (!fixed[c]) {
                widest = Math.max(widest, text.width(column.label(), true, TABLE_SIZE));
            }
            natural[c] = Math.max(MIN_COLUMN, widest + 2 * CELL_PADDING + 1);
            if (fixed[c]) {
                fixedTotal += natural[c];
            } else {
                freeTotal += natural[c];
            }
        }
        float total = fixedTotal + freeTotal;
        if (total <= available) {
            // Room to spare goes to the text columns, so that the table spans the page.
            float spare = available - total;
            float[] widths = natural.clone();
            if (freeTotal > 0) {
                for (int c = 0; c < widths.length; c++) {
                    if (!fixed[c]) {
                        widths[c] += spare * natural[c] / freeTotal;
                    }
                }
            }
            return widths;
        }
        float[] widths = natural.clone();
        float left = available - fixedTotal;
        int free = 0;
        for (boolean f : fixed) {
            free += f ? 0 : 1;
        }
        if (free > 0 && left >= free * MIN_COLUMN) {
            for (int c = 0; c < widths.length; c++) {
                if (!fixed[c]) {
                    widths[c] = Math.max(MIN_COLUMN, left * natural[c] / freeTotal);
                }
            }
        }
        // Still too wide (many amount columns): every column shrinks alike, the page edge is never crossed.
        float used = 0;
        for (float w : widths) {
            used += w;
        }
        if (used > available) {
            for (int c = 0; c < widths.length; c++) {
                widths[c] = widths[c] * available / used;
            }
        }
        return widths;
    }

    /** Labelled amounts in the right half of the page, the last one bold under a rule. */
    private static void totals(Pages pages, DocumentContent.Source source, List<String> columns, ReportFormat format)
        throws IOException {
        float width = pages.width / 2;
        float left = MARGIN + pages.width - width;
        float lineHeight = TEXT_SIZE * LEADING;
        pages.room(columns.size() * lineHeight + lineHeight * 0.4f);
        for (int i = 0; i < columns.size(); i++) {
            boolean last = i == columns.size() - 1 && columns.size() > 1;
            ReportColumn column = source.column(columns.get(i));
            String value = format.value(column, source.first(columns.get(i)));
            if (last) {
                pages.stream.setLineWidth(0.6f);
                pages.stream.moveTo(left, pages.y - lineHeight * 0.15f);
                pages.stream.lineTo(left + width, pages.y - lineHeight * 0.15f);
                pages.stream.stroke();
                pages.y -= lineHeight * 0.4f;
            }
            pages.y -= lineHeight;
            String label = wrap(pages.text, column.label(), last, TEXT_SIZE, width * 0.55f).getFirst();
            pages.text.draw(pages.stream, label, last, TEXT_SIZE, left, pages.y);
            pages.text.draw(pages.stream, value, last, TEXT_SIZE,
                left + width - pages.text.width(value, last, TEXT_SIZE), pages.y);
        }
        pages.y -= GAP;
    }

    /** A heading (or none) and the text wrapped to the page; it may continue on the next page. */
    private static void paragraph(Pages pages, String heading, String body) throws IOException {
        float lineHeight = TEXT_SIZE * LEADING;
        List<String> lines = wrap(pages.text, body == null ? "" : body, false, TEXT_SIZE, pages.width);
        if (heading != null) {
            pages.room(LABEL_SIZE * LEADING + lineHeight);
            pages.y -= LABEL_SIZE * LEADING;
            pages.text.draw(pages.stream, heading, true, LABEL_SIZE, MARGIN, pages.y);
        }
        for (String line : lines) {
            if (!pages.fits(lineHeight)) {
                pages.newPage();
            }
            pages.y -= lineHeight;
            pages.text.draw(pages.stream, line, false, TEXT_SIZE, MARGIN, pages.y);
        }
        pages.y -= GAP;
    }

    /** "Number   PREVIEW ... Page n of m" at the foot of every page, written once the pages are counted. */
    private static void footers(PDDocument pdf, PdfReportWriter.Fonts text, DocumentContent content, Labels labels,
        PDRectangle size) throws IOException {
        int pages = pdf.getNumberOfPages();
        float width = size.getWidth() - 2 * MARGIN;
        for (int p = 0; p < pages; p++) {
            PDPage page = pdf.getPage(p);
            try (PDPageContentStream stream = new PDPageContentStream(pdf, page,
                PDPageContentStream.AppendMode.APPEND, true)) {
                float y = MARGIN - FOOTER_SIZE * 2;
                String left = (content.number() == null ? content.title() : content.title() + " " + content.number())
                    + (content.preview() ? "   " + labels.preview() : "");
                String right = labels.page(p + 1, pages);
                List<String> fitted = wrap(text, left, content.preview(), FOOTER_SIZE, width * 0.7f);
                text.draw(stream, fitted.getFirst(), content.preview(), FOOTER_SIZE, MARGIN, y);
                text.draw(stream, right, false, FOOTER_SIZE, MARGIN + width - text.width(right, false, FOOTER_SIZE), y);
                if (content.preview()) {
                    // A preview is not the document: say so where the eye starts as well.
                    float w = text.width(labels.preview(), true, NUMBER_SIZE);
                    text.draw(stream, labels.preview(), true, NUMBER_SIZE, MARGIN + (width - w) / 2,
                        size.getHeight() - MARGIN + NUMBER_SIZE * 0.5f);
                }
            }
        }
    }

    /**
     * The text broken into lines no wider than {@code width}: at the line breaks it has, then between words, and a
     * word wider than a line between characters. Always at least one line.
     */
    static List<String> wrap(PdfReportWriter.Fonts text, String value, boolean bold, float size, float available) {
        // A text measured as exactly the width (a column as wide as its widest text) must fit despite float rounding.
        float width = available + 0.01f;
        List<String> lines = new ArrayList<>();
        for (String paragraph : (value == null ? "" : value).split("\r\n|\r|\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ", -1)) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (text.width(candidate, bold, size) <= width) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (rest.codePointCount(0, rest.length()) > 1 && text.width(rest, bold, size) > width) {
                    // At least one character per line, a surrogate pair whole: a line narrower than one character
                    // still moves on.
                    int least = rest.offsetByCodePoints(0, 1);
                    int end = rest.length();
                    while (end > least && text.width(rest.substring(0, end), bold, size) > width) {
                        end = rest.offsetByCodePoints(end, -1);
                    }
                    lines.add(rest.substring(0, end));
                    rest = rest.substring(end);
                }
                line.append(rest);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static float height(List<Line> lines) {
        float h = 0;
        for (Line line : lines) {
            h += line.size * LEADING;
        }
        return h;
    }

    private record Cell(String text, boolean bold, float offset, float width, boolean right) {}

    private record Line(float size, List<Cell> cells) {}

    /** The pages written so far and where the next block starts on the current one. */
    private static final class Pages {
        final PDDocument pdf;
        final PdfReportWriter.Fonts text;
        final PDRectangle size;
        final float width;
        final float bottom;
        PDPageContentStream stream;
        float y;

        Pages(PDDocument pdf, PdfReportWriter.Fonts text, PDRectangle size) {
            this.pdf = pdf;
            this.text = text;
            this.size = size;
            this.width = size.getWidth() - 2 * MARGIN;
            // Room for the footer.
            this.bottom = MARGIN + FOOTER_SIZE * 2;
        }

        void newPage() throws IOException {
            close();
            PDPage page = new PDPage(size);
            pdf.addPage(page);
            stream = new PDPageContentStream(pdf, page);
            y = size.getHeight() - MARGIN;
        }

        /** The height a page has for blocks. */
        float fullPage() {
            return size.getHeight() - MARGIN - bottom;
        }

        boolean fits(float height) {
            return y - height >= bottom;
        }

        /** A new page unless the current one has the room; a block taller than a page starts at a page's top. */
        void room(float height) throws IOException {
            if (!fits(height) && y < size.getHeight() - MARGIN) {
                newPage();
            }
        }

        void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }
    }
}
