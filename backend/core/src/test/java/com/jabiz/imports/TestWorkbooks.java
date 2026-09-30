package com.jabiz.imports;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Minimal XLSX workbooks written part by part, so that tests control every byte a reader sees. */
public final class TestWorkbooks {

    public static final String CONTENT_TYPES = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
        </Types>""";

    public static final String WORKBOOK = """
        <?xml version="1.0" encoding="UTF-8"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <sheets><sheet name="Data" sheetId="1" r:id="rId1"/><sheet name="Other" sheetId="2" r:id="rId2"/></sheets>
        </workbook>""";

    public static final String RELS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/>
        <Relationship Id="rId2" Type="worksheet" Target="/xl/worksheets/sheet2.xml"/>
        </Relationships>""";

    /** Style 1 shows a date (built-in 14), style 2 a custom date format, style 3 an amount. */
    public static final String STYLES = """
        <?xml version="1.0" encoding="UTF-8"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy\\-mm\\-dd"/><numFmt numFmtId="165" formatCode="&quot;$&quot;#,##0.00"/></numFmts>
        <cellXfs count="4"><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="164"/><xf numFmtId="165"/></cellXfs>
        </styleSheet>""";

    private TestWorkbooks() {}

    /** A sheet part from row XML ({@code <row r="1">...</row>}). */
    public static String sheet(String rows) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
            + rows + "</sheetData></worksheet>";
    }

    public static String shared(String... strings) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        for (String s : strings) {
            xml.append("<si><t>").append(s).append("</t></si>");
        }
        return xml.append("</sst>").toString();
    }

    /** The standard parts plus the given sheets and shared strings. */
    public static Path workbook(Path file, String sheet1, String sheet2, String sharedStrings) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", CONTENT_TYPES);
        parts.put("xl/workbook.xml", WORKBOOK);
        parts.put("xl/_rels/workbook.xml.rels", RELS);
        parts.put("xl/styles.xml", STYLES);
        parts.put("xl/worksheets/sheet1.xml", sheet1);
        parts.put("xl/worksheets/sheet2.xml", sheet2 == null ? sheet("") : sheet2);
        if (sharedStrings != null) {
            parts.put("xl/sharedStrings.xml", sharedStrings);
        }
        return zip(file, parts);
    }

    public static Path zip(Path file, Map<String, String> parts) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }
}
