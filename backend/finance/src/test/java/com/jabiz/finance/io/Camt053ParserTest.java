package com.jabiz.finance.io;

import com.jabiz.imports.ImportFileException;
import com.jabiz.imports.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ISO 20022 camt.053 statement reader (FIN-BK-003; F5 plan decision D3). */
class Camt053ParserTest {

    @TempDir
    Path dir;

    private static final String SAMPLE = sample();

    private static String sample() {
        try {
            return Files.readString(Path.of("src/test/resources/bank/statement-2026-01.camt053.xml"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private StatementSink parse(String text) throws IOException {
        return parse(text, ParseLimits.DEFAULT);
    }

    private StatementSink parse(String text, ParseLimits limits) throws IOException {
        Path file = dir.resolve("statement.xml");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        StatementSink sink = new StatementSink();
        new Camt053Parser().parse(file, limits, sink);
        return sink;
    }

    @Test
    void theJanuaryStatementReadsAsTenSignedLinesWithItsBalancesAndDays() throws IOException {
        StatementSink sink = parse(SAMPLE);
        assertThat(sink.records).hasSize(10);
        assertThat(sink.cells(StatementColumns.REFERENCE)).startsWith("BNK-0001").endsWith("BNK-0010");
        assertThat(sink.cells(StatementColumns.AMOUNT)).startsWith("-3200.00", "32475.00").endsWith("-300.00");
        assertThat(sink.cells(StatementColumns.DESCRIPTION).getFirst()).isEqualTo("CHECK 1045");
        assertThat(sink.cells(StatementColumns.TYPE)).startsWith("DBT", "DEP");
        assertThat(sink.cells(StatementColumns.ACCOUNT)).containsOnly("6789");
        assertThat(sink.records.getFirst().cells()).containsEntry(StatementColumns.OPENING, "253200.00")
            .containsEntry(StatementColumns.CLOSING, "256555.00").containsEntry(StatementColumns.FROM, "2026-01-01")
            .containsEntry(StatementColumns.TO, "2026-01-31").containsEntry(StatementColumns.CURRENCY, "USD");
    }

    @Test
    void pendingEntriesAreNotStatementLines() throws IOException {
        StatementSink sink = parse(SAMPLE.replaceFirst("<Sts><Cd>BOOK</Cd></Sts>", "<Sts><Cd>PDNG</Cd></Sts>"));
        assertThat(sink.records).hasSize(9);
        assertThat(sink.cells(StatementColumns.REFERENCE)).doesNotContain("BNK-0001");
    }

    @Test
    void withoutFromToTheBalancesGiveTheDaysAndAnIbanNamesTheAccount() throws IOException {
        String text = SAMPLE.replaceAll("<FrToDt>.*</FrToDt>", "")
            .replace("<Othr><Id>000123456789</Id></Othr>", "<IBAN>DE89370400440532013000</IBAN>");
        StatementSink sink = parse(text);
        assertThat(sink.records.getFirst().cells()).containsEntry(StatementColumns.FROM, "2026-01-01")
            .containsEntry(StatementColumns.TO, "2026-01-31").containsEntry(StatementColumns.ACCOUNT, "3000");
    }

    @Test
    void aPreviousClosingBalanceOpensTheStatementOnTheDayAfterAndBookingDatesComeFirst() throws IOException {
        String text = SAMPLE.replaceAll("<FrToDt>.*</FrToDt>", "")
            .replace("<Cd>OPBD</Cd></CdOrPrtry></Tp>\n        <Amt Ccy=\"USD\">253200.00</Amt>\n        "
                + "<CdtDbtInd>CRDT</CdtDbtInd>\n        <Dt><Dt>2026-01-01</Dt></Dt>",
                "<Cd>PRCD</Cd></CdOrPrtry></Tp>\n        <Amt Ccy=\"USD\">253200.00</Amt>\n        "
                + "<CdtDbtInd>CRDT</CdtDbtInd>\n        <Dt><Dt>2025-12-31</Dt></Dt>")
            .replaceFirst("<ValDt><Dt>2026-01-03</Dt></ValDt>", "<ValDt><Dt>2026-02-05</Dt></ValDt>");
        StatementSink sink = parse(text);
        assertThat(sink.records.getFirst().cells()).containsEntry(StatementColumns.FROM, "2026-01-01")
            .containsEntry(StatementColumns.DATE, "2026-01-03");
    }

    @Test
    void aStatementWithoutBookedEntriesIsRefused() {
        assertThatThrownBy(() -> parse(SAMPLE.replace("<Cd>BOOK</Cd>", "<Cd>PDNG</Cd>")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("no booked entries");
    }

    @Test
    void documentsWithADtdOrEntitiesAreRefused() {
        String doctype = SAMPLE.replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE Document [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>")
            .replace("CHECK 1045", "&x;");
        assertThatThrownBy(() -> parse(doctype)).isInstanceOf(ImportFileException.class);
        String bomb = SAMPLE.replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<?xml version=\"1.0\"?>\n<!DOCTYPE Document [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]>");
        assertThatThrownBy(() -> parse(bomb)).isInstanceOf(ImportFileException.class);
    }

    @Test
    void malformedStatementsAreRefusedWhole() {
        assertThatThrownBy(() -> parse("<Document><BkToCstmrStmt>")).isInstanceOf(ImportFileException.class)
            .hasMessageContaining("well-formed");
        assertThatThrownBy(() -> parse(SAMPLE.replace("<Cd>CLBD</Cd>", "<Cd>CLAV</Cd>")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("closing (CLBD)");
        assertThatThrownBy(() -> parse(SAMPLE.replaceFirst("<CdtDbtInd>DBIT</CdtDbtInd>", "<CdtDbtInd>X</CdtDbtInd>")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("CRDT or DBIT");
        assertThatThrownBy(() -> parse(SAMPLE.replaceFirst("3200.00", "-3200.00")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("positive decimal");
        assertThatThrownBy(() -> parse(SAMPLE.replace("<Othr><Id>000123456789</Id></Othr>", "")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("names its account");
        String two = SAMPLE.replace("</BkToCstmrStmt>", "<Stmt><Id>2</Id></Stmt></BkToCstmrStmt>");
        assertThatThrownBy(() -> parse(two)).isInstanceOf(ImportFileException.class)
            .hasMessageContaining("one statement");
        assertThatThrownBy(() -> parse("<Document/>")).isInstanceOf(ImportFileException.class)
            .hasMessageContaining("BkToCstmrStmt/Stmt");
    }

    @Test
    void limitsAreHonoured() {
        assertThatThrownBy(() -> parse(SAMPLE, ParseLimits.DEFAULT.withMaxRecords(3)))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("more than 3 entries");
        assertThatThrownBy(() -> parse(SAMPLE, new ParseLimits(20_000, 256, 32_768, 100)))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("larger than 100 bytes");
        String deep = SAMPLE.replace("<AddtlNtryInf>CHECK 1045</AddtlNtryInf>",
            "<X>".repeat(20) + "</X>".repeat(20));
        assertThatThrownBy(() -> parse(deep)).isInstanceOf(ImportFileException.class)
            .hasMessageContaining("nested too deeply");
        String longName = SAMPLE.replace("<MsgId>LNB-2026-01</MsgId>", "<" + "N".repeat(65) + ">x</" + "N".repeat(65)
            + ">");
        assertThatThrownBy(() -> parse(longName)).isInstanceOf(ImportFileException.class)
            .hasMessageContaining("longer than 64");
        String balances = SAMPLE.replace("<Bal>", "<Bal><Tp><CdOrPrtry><Cd>ITBD</Cd></CdOrPrtry></Tp></Bal>"
            .repeat(11) + "<Bal>");
        assertThatThrownBy(() -> parse(balances)).isInstanceOf(ImportFileException.class)
            .hasMessageContaining("more than 20 balances");
    }
}
