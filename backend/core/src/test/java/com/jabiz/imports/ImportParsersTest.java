package com.jabiz.imports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportParsersTest {

    @TempDir
    Path dir;

    private Path text(String content) throws IOException {
        return Files.writeString(dir.resolve("f" + System.nanoTime()), content, StandardCharsets.UTF_8);
    }

    private ParsedFile parse(ImportFormat format, Path file) throws IOException {
        return ImportParsers.parse(format, file, ParseLimits.DEFAULT);
    }

    @Test
    void readsCsvWithQuotesLineBreaksAndBom() throws IOException {
        ParsedFile file = parse(ImportFormat.csv(), text("﻿SKU, Price ,Note\r\n"
            + "A-1,\"1,200.50\",\"said \"\"hi\"\"\"\r\n"
            + "\r\n"
            + "A-2, 3 ,\"two\nlines\"\n"
            + "A-3,4"));
        assertThat(file.columns()).containsExactly("SKU", "Price", "Note");
        assertThat(file.records()).hasSize(3);
        assertThat(file.records().get(0).cells()).containsEntry("Price", "1,200.50").containsEntry("Note",
            "said \"hi\"");
        assertThat(file.records().get(0).location()).isEqualTo("line 2");
        assertThat(file.records().get(1).cells()).containsEntry("Price", "3").containsEntry("Note", "two\nlines");
        assertThat(file.records().get(1).location()).isEqualTo("line 4");
        assertThat(file.records().get(2).location()).isEqualTo("line 6");
        assertThat(file.records().get(2).cells()).doesNotContainKey("Note");
        assertThat(file.records().get(2).number()).isEqualTo(3);
    }

    @Test
    void readsCsvWithoutHeaderOtherDelimiterAndSkippedLines() throws IOException {
        ImportFormat.Csv format = ImportFormat.csv().delimiter(';').header(false).skipLines(1);
        ParsedFile file = parse(format, text("Bank export 2026\nx;1\ny;2;;\n"));
        assertThat(file.columns()).containsExactly("1", "2");
        assertThat(file.records()).extracting(r -> r.cells().get("2")).containsExactly("1", "2");
        assertThat(file.records().get(1).problem()).isNull();
        ParsedFile adjusted = parse(ImportFormat.csv().adjusted(new ImportFormat.Options(';', null, 1, null, null,
            null)), text("title\na;b\n1;2\n"));
        assertThat(adjusted.columns()).containsExactly("a", "b");
    }

    @Test
    void readsCsvInWindows1252() throws IOException {
        Path file = Files.write(dir.resolve("w.csv"), "name\nCafé\n".getBytes(Charset.forName("windows-1252")));
        assertThatThrownBy(() -> parse(ImportFormat.csv(), file)).isInstanceOf(ImportFileException.class)
            .hasMessageContaining("not valid text");
        ParsedFile read = parse(ImportFormat.csv().charset(ImportFormat.charset("WINDOWS-1252")), file);
        assertThat(read.records().getFirst().cells()).containsEntry("name", "Café");
        assertThatThrownBy(() -> ImportFormat.charset("UTF-16")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void flagsExtraCellsAndRefusesBrokenCsv() throws IOException {
        ParsedFile file = parse(ImportFormat.csv(), text("a,b\n1,2,3\n"));
        assertThat(file.records().getFirst().problem()).contains("3 cells");
        assertThatThrownBy(() -> parse(ImportFormat.csv(), text("a,b\n\"open,2\n")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("not closed");
        assertThatThrownBy(() -> parse(ImportFormat.csv(), text("a,A\n1,2\n")))
            .hasMessageContaining("appears twice");
        assertThatThrownBy(() -> parse(ImportFormat.csv(), text("a,b\n1,\u0000\n")))
            .hasMessageContaining("control character");
        assertThat(parse(ImportFormat.csv(), text("")).records()).isEmpty();
        assertThatThrownBy(() -> new ImportFormat.Csv(',', ',', true, 0, StandardCharsets.UTF_8))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesLimits() throws IOException {
        ParseLimits small = new ParseLimits(2, 3, 5, 1024);
        assertThatThrownBy(() -> ImportParsers.parse(ImportFormat.csv(), text("a\n1\n2\n3\n"), small))
            .isInstanceOfSatisfying(ImportFileException.class, e -> {
                assertThat(e.code()).isEqualTo(ImportCodes.TOO_MANY_ROWS);
                assertThat(e.params()).containsEntry("max", 2);
            });
        assertThatThrownBy(() -> ImportParsers.parse(ImportFormat.csv(), text("a,b,c,d,e\n"), small))
            .hasMessageContaining("more than 3 cells");
        assertThatThrownBy(() -> ImportParsers.parse(ImportFormat.csv(), text("a\n123456\n"), small))
            .hasMessageContaining("longer than 5");
        assertThat(ImportParsers.parse(ImportFormat.csv(), text("a\n1\n\n2\n"), small).records()).hasSize(2);
        assertThat(ParseLimits.DEFAULT.withMaxRecords(7).maxRecords()).isEqualTo(7);
    }

    @Test
    void readsFixedWidth() throws IOException {
        ImportFormat.FixedWidth format = new ImportFormat.FixedWidth(List.of(
            new ImportFormat.FixedWidth.Slice("account", 1, 6),
            new ImportFormat.FixedWidth.Slice("amount", 7, 10)), 1, StandardCharsets.UTF_8);
        ParsedFile file = parse(format, text("HEADER\n1001      125.00\n\n1002  7\n"));
        assertThat(file.columns()).containsExactly("account", "amount");
        assertThat(file.records()).extracting(r -> r.cells().get("amount")).containsExactly("125.00", "7");
        assertThat(file.records().get(1).location()).isEqualTo("line 4");
        assertThatThrownBy(() -> parse(ImportFormat.fixedWidth(new ImportFormat.FixedWidth.Slice("a", 1, 2)),
            text("a\u0001\n"))).hasMessageContaining("control character");
        assertThatThrownBy(() -> ImportFormat.fixedWidth(new ImportFormat.FixedWidth.Slice("a", 1, 2),
            new ImportFormat.FixedWidth.Slice("a", 3, 2))).hasMessageContaining("unique");
    }

    @Test
    void readsXmlRecordsAttributesAndHeader() throws IOException {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("opening", "Document/Stmt/Bal/Amt");
        header.put("account", "Document/Stmt/Acct/@id");
        ImportFormat.Xml format = new ImportFormat.Xml("Document/Stmt/Ntry",
            Map.of("amount", "Amt", "currency", "Amt/@Ccy", "ref", "Refs/EndToEndId", "kind", "@kind"), header);
        ParsedFile file = parse(format, text("""
            <?xml version="1.0"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
              <Stmt><Acct id="ACC-1"/><Bal><Amt>100.00</Amt></Bal>
                <Ntry kind="credit"><Amt Ccy="USD">10.50</Amt><Refs><EndToEndId>E1</EndToEndId></Refs></Ntry>
                <Ntry kind="debit"><Amt Ccy="USD"><![CDATA[2.00]]></Amt><Refs><EndToEndId>E2</EndToEndId><EndToEndId>X</EndToEndId></Refs></Ntry>
              </Stmt>
            </Document>
            """));
        assertThat(file.header()).containsEntry("opening", "100.00").containsEntry("account", "ACC-1");
        assertThat(file.records()).hasSize(2);
        assertThat(file.records().get(0).cells()).containsEntry("amount", "10.50").containsEntry("currency", "USD")
            .containsEntry("ref", "E1").containsEntry("kind", "credit");
        assertThat(file.records().get(1).cells()).containsEntry("amount", "2.00").containsEntry("ref", "E2");
        assertThat(file.records().get(1).location()).isEqualTo("record 2");
    }

    @Test
    void refusesDtdsAndExternalEntities() throws IOException {
        ImportFormat.Xml format = ImportFormat.xml("r/i", "v", "v");
        Path xxe = text("""
            <?xml version="1.0"?>
            <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <r><i><v>&x;</v></i></r>
            """);
        assertThatThrownBy(() -> parse(format, xxe)).isInstanceOf(ImportFileException.class);
        Path laughs = text("""
            <?xml version="1.0"?>
            <!DOCTYPE r [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>
            <r><i><v>&b;</v></i></r>
            """);
        assertThatThrownBy(() -> parse(format, laughs)).isInstanceOf(ImportFileException.class);
        assertThatThrownBy(() -> parse(format, text("<r><i><v>1</v></i>"))).isInstanceOf(ImportFileException.class)
            .hasMessageContaining("well-formed");
        assertThatThrownBy(() -> ImportFormat.xml("/r", "v", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImportFormat.xml("r", "v")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsWorkbookValues() throws IOException {
        String rows = """
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="inlineStr"><is><t>Paid</t></is></c><c r="E1" t="s"><v>3</v></c></row>
            <row r="2"><c r="A2" t="s"><v>4</v></c><c r="B2" s="3"><v>1234.5</v></c><c r="C2" s="1"><v>46053</v></c><c r="D2" t="b"><v>1</v></c><c r="E2"><f>B2*2</f><v>0.30000000000000004</v></c></row>
            <row r="4"><c r="A4" t="str"><v>X-2</v></c><c r="B4"><v>1E-3</v></c><c r="C4" s="2"><v>46053.5</v></c><c r="D4" t="b"><v>0</v></c></row>
            <row r="5"><c r="A5" t="s"><v>5</v></c></row>
            """;
        Path file = TestWorkbooks.workbook(dir.resolve("w.xlsx"), TestWorkbooks.sheet(rows), null,
            TestWorkbooks.shared("SKU", "Price", "Date", "Calc", "X-1", " "));
        ParsedFile parsed = parse(ImportFormat.xlsx(), file);
        assertThat(parsed.columns()).containsExactly("SKU", "Price", "Date", "Paid", "Calc");
        assertThat(parsed.records()).hasSize(2);
        RawRecord first = parsed.records().get(0);
        assertThat(first.cells()).containsEntry("SKU", "X-1").containsEntry("Price", "1234.5")
            .containsEntry("Date", "2026-01-31").containsEntry("Paid", "TRUE").containsEntry("Calc", "0.3");
        assertThat(first.formulas()).containsExactly("Calc");
        assertThat(first.location()).isEqualTo("Data row 2");
        RawRecord second = parsed.records().get(1);
        assertThat(second.cells()).containsEntry("Price", "0.001").containsEntry("Date", "2026-01-31T12:00")
            .containsEntry("Paid", "FALSE");
        assertThat(second.location()).isEqualTo("Data row 4");
        assertThat(second.number()).isEqualTo(2);
    }

    @Test
    void readsOtherSheetsWithoutHeaderAndFlagsErrors() throws IOException {
        String other = TestWorkbooks.sheet("""
            <row r="1"><c r="A1" t="inlineStr"><is><t>ignored title</t></is></c></row>
            <row r="2"><c r="B2" t="inlineStr"><is><t>b</t></is></c><c r="AA2"><v>3</v></c></row>
            <row r="3"><c r="A3" t="e"><v>#N/A</v></c></row>
            """);
        Path file = TestWorkbooks.workbook(dir.resolve("o.xlsx"), TestWorkbooks.sheet(""), other, null);
        ParsedFile parsed = parse(new ImportFormat.Xlsx("Other", false, 1), file);
        assertThat(parsed.records()).hasSize(2);
        assertThat(parsed.records().get(0).cells()).containsEntry("B", "b").containsEntry("AA", "3");
        assertThat(parsed.records().get(1).problem()).contains("#N/A");
        assertThat(parse(ImportFormat.xlsx().adjusted(new ImportFormat.Options(null, null, null, null, "Other", 2)),
            file).columns()).containsExactly("b", "3");
        assertThatThrownBy(() -> parse(ImportFormat.xlsx().sheet("Missing"), file))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("no worksheet 'Missing'");
    }

    @Test
    void refusesWorkbookBombsAndBrokenParts() throws IOException {
        String big = "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>" + "x".repeat(4000) + "</t></is></c></row>";
        Path file = TestWorkbooks.workbook(dir.resolve("big.xlsx"), TestWorkbooks.sheet(big), null, null);
        assertThatThrownBy(() -> ImportParsers.parse(ImportFormat.xlsx(), file, new ParseLimits(10, 10, 10_000, 2000)))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("uncompressed");
        Path dtd = TestWorkbooks.workbook(dir.resolve("dtd.xlsx"),
            "<?xml version=\"1.0\"?><!DOCTYPE w [<!ENTITY x \"y\">]><worksheet><sheetData/></worksheet>", null,
            null);
        assertThatThrownBy(() -> parse(ImportFormat.xlsx(), dtd)).isInstanceOf(ImportFileException.class);
        Map<String, String> noSheet = new LinkedHashMap<>();
        noSheet.put("[Content_Types].xml", TestWorkbooks.CONTENT_TYPES);
        noSheet.put("xl/workbook.xml", "<workbook><sheets/></workbook>");
        assertThatThrownBy(() -> parse(ImportFormat.xlsx(), TestWorkbooks.zip(dir.resolve("n.xlsx"), noSheet)))
            .hasMessageContaining("no worksheet");
        Path wide = TestWorkbooks.workbook(dir.resolve("wide.xlsx"),
            TestWorkbooks.sheet("<row r=\"1\"><c r=\"Z1\"><v>1</v></c></row>"), null, null);
        assertThatThrownBy(() -> ImportParsers.parse(ImportFormat.xlsx(), wide, new ParseLimits(10, 5, 100, 10_000)))
            .hasMessageContaining("more than 5 columns");
    }

    @Test
    void helpersOfTheWorkbookReader() {
        assertThat(XlsxParser.columnIndex("A1")).isEqualTo(1);
        assertThat(XlsxParser.columnIndex("AB7")).isEqualTo(28);
        assertThat(XlsxParser.columnName(28)).isEqualTo("AB");
        assertThat(XlsxParser.columnName(703)).isEqualTo("AAA");
        assertThat(XlsxParser.isDateFormat("mm/dd/yyyy")).isTrue();
        assertThat(XlsxParser.isDateFormat("\"$\"#,##0.00")).isFalse();
        assertThat(XlsxParser.isDateFormat("[$-409]mmm-yy")).isTrue();
        assertThat(XlsxParser.isDateFormat("h:mm:ss")).isFalse();
        assertThat(XlsxParser.isDateFormat("0.00")).isFalse();
        assertThat(XlsxParser.isDateFormat(null)).isFalse();
        assertThat(XlsxParser.excelDate(new java.math.BigDecimal("61"), false, "x")).isEqualTo("1900-03-01");
        assertThat(XlsxParser.excelDate(new java.math.BigDecimal("1"), true, "x")).isEqualTo("1904-01-02");
        assertThatThrownBy(() -> XlsxParser.excelDate(new java.math.BigDecimal("-1"), false, "x"))
            .isInstanceOf(ImportFileException.class);
        assertThatThrownBy(() -> XlsxParser.columnIndex("12")).isInstanceOf(ImportFileException.class);
    }

    @Test
    void customParsersFailingUnexpectedlyRefuseTheFile() throws IOException {
        ImportFormat custom = ImportFormat.custom("bai2", (file, limits, sink) -> {
            sink.columns(List.of("a"));
            sink.header("opening", "5");
            sink.record(new RawRecord(1, "record 1", Map.of("a", "1")));
            throw new IllegalStateException("boom");
        });
        assertThatThrownBy(() -> parse(custom, text("x"))).isInstanceOf(ImportFileException.class)
            .hasMessageContaining("boom");
        ImportFormat ok = ImportFormat.custom("bai2", (file, limits, sink) -> {
            sink.columns(List.of("a"));
            sink.columns(List.of("ignored"));
            sink.header("opening", "5");
            sink.record(new RawRecord(1, "record 1", Map.of("a", " ")));
            sink.record(new RawRecord(2, "record 2", Map.of("a", "1")));
        });
        ParsedFile parsed = parse(ok, text("x"));
        assertThat(parsed.columns()).containsExactly("a");
        assertThat(parsed.header()).containsEntry("opening", "5");
        assertThat(parsed.records()).hasSize(1);
        assertThat(ok.adjustable()).isFalse();
        assertThat(ok.adjusted(ImportFormat.Options.NONE)).isSameAs(ok);
    }
}
