package com.jabiz.imports;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads one worksheet of an XLSX workbook with the JDK alone (ZIP and StAX), so that what is read and how much is
 * under the platform's control: every part is read through a byte counter ({@link ParseLimits#maxUncompressedBytes})
 * whatever its ZIP header claims, and XML parts through {@link SafeXml}.
 *
 * <p>Values: shared and inline strings as they are; numbers to 15 significant digits (Excel's precision, so that
 * {@code 0.1 + 0.2} reads {@code 0.3}); numbers formatted as dates as ISO dates or date-times; booleans as
 * {@code TRUE}/{@code FALSE}; formulas as their cached value, marked; error values make the record's problem.
 */
final class XlsxParser implements ImportParser {

    private static final MathContext EXCEL_DIGITS = new MathContext(15);
    private static final Set<Integer> BUILT_IN_DATE_FORMATS = Set.of(14, 15, 16, 17, 18, 19, 20, 21, 22, 45, 46, 47);
    private static final int MAX_SHARED_STRINGS = 1_000_000;

    private final ImportFormat.Xlsx format;

    XlsxParser(ImportFormat.Xlsx format) {
        this.format = format;
    }

    @Override
    public void parse(Path file, ParseLimits limits, Sink sink) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Workbook workbook = workbook(zip, limits);
            List<String> shared = sharedStrings(zip, limits);
            Set<Integer> dateStyles = dateStyles(zip, limits);
            readSheet(zip, workbook, shared, dateStyles, limits, sink);
        } catch (XMLStreamException e) {
            throw new ImportFileException(null, "The workbook is not well-formed: " + e.getMessage());
        } catch (java.util.zip.ZipException e) {
            throw new ImportFileException(null, "The workbook is not a readable ZIP archive");
        }
    }

    private record Workbook(String sheetName, String sheetPart, boolean date1904) {}

    private Workbook workbook(ZipFile zip, ParseLimits limits) throws IOException, XMLStreamException {
        Map<String, String> sheets = new LinkedHashMap<>();
        boolean date1904 = false;
        try (InputStream in = part(zip, "xl/workbook.xml", limits)) {
            XMLStreamReader reader = SafeXml.open(in);
            while (reader.hasNext()) {
                if (SafeXml.next(reader, "workbook") != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                if (reader.getLocalName().equals("workbookPr")) {
                    String value = attribute(reader, "date1904");
                    date1904 = "1".equals(value) || "true".equalsIgnoreCase(value);
                } else if (reader.getLocalName().equals("sheet")) {
                    sheets.put(attribute(reader, "name"), attribute(reader, "id"));
                }
            }
        }
        if (sheets.isEmpty()) {
            throw new ImportFileException(null, "The workbook has no worksheet");
        }
        String name = format.sheet() == null ? sheets.keySet().iterator().next() : format.sheet();
        String relation = sheets.get(name);
        if (relation == null) {
            throw new ImportFileException(ImportCodes.FILE_INVALID, null, "The workbook has no worksheet '" + name
                + "'", Map.of("sheet", name));
        }
        String target = null;
        try (InputStream in = part(zip, "xl/_rels/workbook.xml.rels", limits)) {
            XMLStreamReader reader = SafeXml.open(in);
            while (reader.hasNext()) {
                if (SafeXml.next(reader, "workbook") == XMLStreamConstants.START_ELEMENT
                    && reader.getLocalName().equals("Relationship") && relation.equals(attribute(reader, "Id"))) {
                    target = attribute(reader, "Target");
                }
            }
        }
        if (target == null) {
            throw new ImportFileException(null, "The workbook does not say where worksheet '" + name + "' is");
        }
        String partName = target.startsWith("/") ? target.substring(1) : "xl/" + target;
        return new Workbook(name, partName, date1904);
    }

    private static List<String> sharedStrings(ZipFile zip, ParseLimits limits) throws IOException, XMLStreamException {
        List<String> strings = new ArrayList<>();
        if (zip.getEntry("xl/sharedStrings.xml") == null) {
            return strings;
        }
        try (InputStream in = part(zip, "xl/sharedStrings.xml", limits)) {
            XMLStreamReader reader = SafeXml.open(in);
            StringBuilder text = null;
            boolean inText = false;
            boolean inPhonetic = false;
            while (reader.hasNext()) {
                int event = SafeXml.next(reader, "shared strings");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "si" -> text = new StringBuilder();
                        case "t" -> inText = !inPhonetic;
                        case "rPh" -> inPhonetic = true;
                        default -> { }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && inText && text != null) {
                    if (text.length() + reader.getTextLength() > limits.maxCellLength()) {
                        throw new ImportFileException(null, "A cell is longer than " + limits.maxCellLength()
                            + " characters");
                    }
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "si" -> {
                            if (strings.size() >= MAX_SHARED_STRINGS) {
                                throw new ImportFileException(null, "The workbook has too many strings");
                            }
                            strings.add(text == null ? "" : text.toString());
                            text = null;
                        }
                        case "t" -> inText = false;
                        case "rPh" -> inPhonetic = false;
                        default -> { }
                    }
                }
            }
        }
        return strings;
    }

    /** Indexes of the cell formats ({@code cellXfs}) whose number format shows a date. */
    private static Set<Integer> dateStyles(ZipFile zip, ParseLimits limits) throws IOException, XMLStreamException {
        Set<Integer> styles = new HashSet<>();
        if (zip.getEntry("xl/styles.xml") == null) {
            return styles;
        }
        Set<Integer> customDates = new HashSet<>();
        try (InputStream in = part(zip, "xl/styles.xml", limits)) {
            XMLStreamReader reader = SafeXml.open(in);
            boolean inCellXfs = false;
            int index = 0;
            while (reader.hasNext()) {
                int event = SafeXml.next(reader, "styles");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "numFmt" -> {
                            if (isDateFormat(attribute(reader, "formatCode"))) {
                                customDates.add(Integer.parseInt(attribute(reader, "numFmtId")));
                            }
                        }
                        case "cellXfs" -> inCellXfs = true;
                        case "xf" -> {
                            if (inCellXfs) {
                                String id = attribute(reader, "numFmtId");
                                int numFmt = id == null ? 0 : Integer.parseInt(id);
                                if (BUILT_IN_DATE_FORMATS.contains(numFmt) || customDates.contains(numFmt)) {
                                    styles.add(index);
                                }
                                index++;
                            }
                        }
                        default -> { }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("cellXfs")) {
                    inCellXfs = false;
                }
            }
        } catch (NumberFormatException e) {
            throw new ImportFileException(null, "The workbook's styles are not readable");
        }
        return styles;
    }

    /** A number format showing a date: day, month or year codes outside quoted text and brackets. */
    static boolean isDateFormat(String code) {
        if (code == null) {
            return false;
        }
        String plain = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^]]*]", "").replaceAll("\\\\.", "")
            .toLowerCase(Locale.ROOT);
        return plain.matches(".*[dy].*") || plain.matches(".*m.*") && !plain.matches(".*[h:s].*");
    }

    private void readSheet(ZipFile zip, Workbook workbook, List<String> shared, Set<Integer> dateStyles,
        ParseLimits limits, Sink sink) throws IOException, XMLStreamException {
        List<String> columns = null;
        Map<Integer, String> names = new HashMap<>();
        int records = 0;
        try (InputStream in = part(zip, workbook.sheetPart(), limits)) {
            XMLStreamReader reader = SafeXml.open(in);
            int rowNumber = 0;
            TreeMap<Integer, String> cells = null;
            Set<Integer> formulas = new HashSet<>();
            String problem = null;
            int column = 0;
            String type = null;
            boolean date = false;
            StringBuilder value = new StringBuilder();
            boolean inValue = false;
            boolean formula = false;
            while (reader.hasNext()) {
                int event = SafeXml.next(reader, workbook.sheetName() + " row " + rowNumber);
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "row" -> {
                            String r = attribute(reader, "r");
                            rowNumber = r == null ? rowNumber + 1 : parseInt(r, workbook, rowNumber);
                            cells = new TreeMap<>();
                            formulas = new HashSet<>();
                            problem = null;
                            column = 0;
                        }
                        case "c" -> {
                            String ref = attribute(reader, "r");
                            column = ref == null ? column + 1 : columnIndex(ref);
                            if (column > limits.maxColumns()) {
                                throw new ImportFileException(workbook.sheetName() + " row " + rowNumber,
                                    "A row has more than " + limits.maxColumns() + " columns");
                            }
                            type = attribute(reader, "t");
                            String style = attribute(reader, "s");
                            date = style != null && dateStyles.contains(parseInt(style, workbook, rowNumber));
                            value.setLength(0);
                            formula = false;
                        }
                        case "v", "t" -> inValue = true;
                        case "f" -> formula = true;
                        default -> { }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && inValue) {
                    if (value.length() + reader.getTextLength() > limits.maxCellLength()) {
                        throw new ImportFileException(workbook.sheetName() + " row " + rowNumber,
                            "A cell is longer than " + limits.maxCellLength() + " characters");
                    }
                    value.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "v", "t" -> inValue = false;
                        case "c" -> {
                            if (cells != null) {
                                String location = workbook.sheetName() + " row " + rowNumber;
                                if ("e".equals(type)) {
                                    problem = "Cell " + columnName(column) + " holds the error " + value;
                                } else {
                                    cells.put(column, cellValue(type, date, value.toString(), shared,
                                        workbook.date1904(), location));
                                }
                                if (formula) {
                                    formulas.add(column);
                                }
                            }
                        }
                        case "row" -> {
                            if (cells == null) {
                                break;
                            }
                            boolean before = rowNumber < format.headerRow();
                            boolean isHeader = rowNumber == format.headerRow();
                            if (isHeader && format.header()) {
                                columns = header(cells, names, rowNumber, workbook);
                                sink.columns(columns);
                            } else if (!before && !isHeader) {
                                if (columns == null) {
                                    columns = new ArrayList<>();
                                    sink.columns(columns);
                                }
                                boolean blank = cells.values().stream().allMatch(String::isBlank) && problem == null;
                                if (!blank) {
                                    if (++records > limits.maxRecords()) {
                                        throw CsvParser.tooMany(limits);
                                    }
                                    sink.record(record(records, workbook.sheetName() + " row " + rowNumber, cells,
                                        formulas, problem, names));
                                }
                            }
                            cells = null;
                        }
                        default -> { }
                    }
                }
            }
        }
        if (columns == null) {
            sink.columns(List.of());
        }
    }

    private List<String> header(TreeMap<Integer, String> cells, Map<Integer, String> names, int rowNumber,
        Workbook workbook) {
        List<String> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<Integer, String> cell : cells.entrySet()) {
            String name = cell.getValue().strip();
            if (name.isEmpty()) {
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new ImportFileException(workbook.sheetName() + " row " + rowNumber, "Column '" + name
                    + "' appears twice in the header");
            }
            names.put(cell.getKey(), name);
            columns.add(name);
        }
        return columns;
    }

    private RawRecord record(int number, String location, TreeMap<Integer, String> cells, Set<Integer> formulas,
        String problem, Map<Integer, String> names) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> formulaColumns = new HashSet<>();
        for (Map.Entry<Integer, String> cell : cells.entrySet()) {
            String name = format.header() ? names.get(cell.getKey()) : columnName(cell.getKey());
            if (name == null) {
                if (!cell.getValue().isBlank() && problem == null) {
                    problem = "Cell " + columnName(cell.getKey()) + " has a value but its column has no name";
                }
                continue;
            }
            values.put(name, cell.getValue());
            if (formulas.contains(cell.getKey())) {
                formulaColumns.add(name);
            }
        }
        return new RawRecord(number, location, values, formulaColumns, problem);
    }

    private static String cellValue(String type, boolean date, String raw, List<String> shared, boolean date1904,
        String location) {
        if (type == null || type.equals("n")) {
            if (raw.isBlank()) {
                return "";
            }
            BigDecimal number;
            try {
                number = new BigDecimal(raw.strip());
            } catch (NumberFormatException e) {
                throw new ImportFileException(location, "A number cell holds '" + raw + "'");
            }
            return date ? excelDate(number, date1904, location) : plain(number.round(EXCEL_DIGITS));
        }
        return switch (type) {
            case "s" -> {
                try {
                    yield shared.get(Integer.parseInt(raw.strip()));
                } catch (RuntimeException e) {
                    throw new ImportFileException(location, "A cell refers to a missing shared string");
                }
            }
            case "b" -> "1".equals(raw.strip()) ? "TRUE" : "FALSE";
            default -> raw;  // str, inlineStr, d (ISO date text)
        };
    }

    private static String plain(BigDecimal number) {
        BigDecimal stripped = number.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    /** Serial day number to an ISO date, or date-time when it has a time of day (1900 system with its leap bug). */
    static String excelDate(BigDecimal serial, boolean date1904, String location) {
        if (serial.signum() < 0 || serial.compareTo(BigDecimal.valueOf(2_958_466)) >= 0) {
            throw new ImportFileException(location, "A date cell holds " + serial.toPlainString());
        }
        long days = serial.longValue();
        BigDecimal fraction = serial.subtract(BigDecimal.valueOf(days));
        LocalDate day = date1904 ? LocalDate.of(1904, 1, 1).plusDays(days)
            : LocalDate.of(1899, 12, 31).plusDays(days > 59 ? days - 1 : days);
        if (fraction.signum() == 0) {
            return day.toString();
        }
        long seconds = fraction.multiply(BigDecimal.valueOf(86_400)).setScale(0, java.math.RoundingMode.HALF_UP)
            .longValue();
        return LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT).plus(seconds, ChronoUnit.SECONDS).toString();
    }

    /** 1-based column of a cell reference such as {@code AB12}. */
    static int columnIndex(String ref) {
        int column = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) {
            column = column * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            if (column > 16_384) {
                throw new ImportFileException(null, "A cell reference is out of range: " + ref);
            }
        }
        if (column == 0) {
            throw new ImportFileException(null, "A cell reference is not readable: " + ref);
        }
        return column;
    }

    static String columnName(int column) {
        StringBuilder name = new StringBuilder();
        for (int c = column; c > 0; c = (c - 1) / 26) {
            name.insert(0, (char) ('A' + (c - 1) % 26));
        }
        return name.toString();
    }

    private static int parseInt(String value, Workbook workbook, int rowNumber) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ImportFileException(workbook.sheetName() + " row " + rowNumber, "'" + value
                + "' is not a number");
        }
    }

    private static String attribute(XMLStreamReader reader, String localName) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (reader.getAttributeLocalName(i).equals(localName)) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /** A part of the archive, read through a counter that stops at the limit whatever the ZIP header says. */
    private static InputStream part(ZipFile zip, String name, ParseLimits limits) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new ImportFileException(null, "The workbook has no part " + name);
        }
        return new Bounded(zip.getInputStream(entry), limits.maxUncompressedBytes(), name);
    }

    private static final class Bounded extends FilterInputStream {
        private final long max;
        private final String name;
        private long count;

        Bounded(InputStream in, long max, String name) {
            super(in);
            this.max = max;
            this.name = name;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(long n) {
            count += n;
            if (count > max) {
                throw new ImportFileException(null, "Part " + name + " of the workbook is larger than "
                    + max + " bytes uncompressed");
            }
        }
    }
}
