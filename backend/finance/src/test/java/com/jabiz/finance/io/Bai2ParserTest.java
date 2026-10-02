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

/** The BAI2 statement reader (FIN-BK-003; F5 plan decision D3). */
class Bai2ParserTest {

    @TempDir
    Path dir;

    private static final String SAMPLE = sample();

    private static String sample() {
        try {
            return Files.readString(Path.of("src/test/resources/bank/statement-2026-01.bai2"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private StatementSink parse(String text) throws IOException {
        return parse(text, ParseLimits.DEFAULT);
    }

    private StatementSink parse(String text, ParseLimits limits) throws IOException {
        Path file = dir.resolve("statement.bai2");
        Files.write(file, text.getBytes(StandardCharsets.ISO_8859_1));
        StatementSink sink = new StatementSink();
        new Bai2Parser().parse(file, limits, sink);
        return sink;
    }

    @Test
    void theJanuaryStatementReadsAsTenSignedLinesWithItsBalancesAndDays() throws IOException {
        StatementSink sink = parse(SAMPLE);
        assertThat(sink.columns).containsExactlyElementsOf(StatementColumns.ALL);
        assertThat(sink.records).hasSize(10);
        assertThat(sink.cells(StatementColumns.REFERENCE)).startsWith("BNK-0001", "BNK-0002").endsWith("BNK-0010");
        // Debits (type codes 400-699) negative, credits positive, cents as decimals.
        assertThat(sink.cells(StatementColumns.AMOUNT)).startsWith("-3200.00", "32475.00").endsWith("-45.00",
            "-300.00");
        // A BAI2 report is of its as-of date: so are its lines and the statement.
        assertThat(sink.cells(StatementColumns.DATE)).containsOnly("2026-01-31");
        assertThat(sink.cells(StatementColumns.DESCRIPTION).get(2)).isEqualTo("ACH DEBIT BATCH 0001 NORTHWIND AP");
        assertThat(sink.cells(StatementColumns.TYPE).getFirst()).isEqualTo("455");
        // Only the last four characters of the account are handed on.
        assertThat(sink.cells(StatementColumns.ACCOUNT)).containsOnly("6789");
        assertThat(sink.records.getFirst().cells()).containsEntry(StatementColumns.OPENING, "253200.00")
            .containsEntry(StatementColumns.CLOSING, "256555.00").containsEntry(StatementColumns.FROM, "2026-01-31")
            .containsEntry(StatementColumns.TO, "2026-01-31").containsEntry(StatementColumns.CURRENCY, "USD");
        assertThat(sink.header).containsEntry(StatementColumns.OPENING, "253200.00");
    }

    @Test
    void textRunsToTheEndOfTheRecordAndContinuesOnEightyEights() throws IOException {
        String text = SAMPLE.replace("16,455,320000,V,260103,,BNK-0001,,CHECK 1045",
            "16,455,320000,V,260103,,BNK-0001,,CHECK 1045, PAYEE A/B\n88,STOP PAYMENT REVIEW/");
        StatementSink sink = parse(text.replace("49,65940000,12/", "49,65940000,13/"));
        assertThat(sink.cells(StatementColumns.DESCRIPTION).getFirst())
            .isEqualTo("CHECK 1045, PAYEE A/B STOP PAYMENT REVIEW");
    }

    @Test
    void withoutAValueDateTheBanksReferenceFollowsTheFundsType() throws IOException {
        StatementSink sink = parse(SAMPLE.replace("16,699,4500,V,260131,,BNK-0009", "16,699,4500,Z,BNK-0009"));
        assertThat(sink.cells(StatementColumns.REFERENCE).get(8)).isEqualTo("BNK-0009");
    }

    @Test
    void aCustomerReferenceIsNoReferenceButGoesWithTheText() throws IOException {
        // A check number is the customer's reference: next month's check 1045 of another account is another line.
        StatementSink sink = parse(SAMPLE.replace("V,260103,,BNK-0001,,CHECK 1045", "V,260103,,,1045,CHECK"));
        assertThat(sink.cells(StatementColumns.REFERENCE).getFirst()).isEmpty();
        assertThat(sink.cells(StatementColumns.DESCRIPTION).getFirst()).isEqualTo("CHECK 1045");
    }

    @Test
    void groupAndFileTrailersAreChecked() {
        assertThatThrownBy(() -> parse(SAMPLE.replace("98,65940000,1,14/", "98,65940001,1,14/")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("group control total");
        assertThatThrownBy(() -> parse(SAMPLE.replace("99,65940000,1,16/", "99,1,1,16/")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("file control total");
        assertThatThrownBy(() -> parse(SAMPLE.replace("98,65940000,1,14/",
            "98,65940000,1,14/\n02,NORTHWIND,111000025,1,260201,2400,USD,2/\n98,0,0,2/")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("one group");
    }

    @Test
    void impossibleDaysAndAStatementWithoutLinesAreRefused() {
        assertThatThrownBy(() -> parse(SAMPLE.replace("260131,2400", "260231,2400")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("YYMMDD");
        String empty = "01,LAKESIDE,NORTHWIND,260201,0800,1,,,2/\n02,NORTHWIND,111000025,1,260131,2400,USD,2/\n"
            + "03,000123456789,USD,010,100,,Z,015,100,,Z/\n49,200,2/\n98,200,1,4/\n99,200,1,6/\n";
        assertThatThrownBy(() -> parse(empty)).isInstanceOf(ImportFileException.class)
            .hasMessageContaining("no lines");
    }

    @Test
    void summaryAmountsMayContinueOnEightyEights() throws IOException {
        String text = SAMPLE.replace("03,000123456789,USD,010,25320000,,Z,015,25655500,,Z/",
            "03,000123456789,USD,010,25320000,,Z/\n88,015,25655500,,Z/");
        StatementSink sink = parse(text.replace("49,65940000,12/", "49,65940000,13/"));
        assertThat(sink.records.getFirst().cells()).containsEntry(StatementColumns.CLOSING, "256555.00");
    }

    @Test
    void controlTotalsAndRecordCountsAreChecked() {
        assertThatThrownBy(() -> parse(SAMPLE.replace("49,65940000,12/", "49,65940001,12/")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("control total");
        assertThatThrownBy(() -> parse(SAMPLE.replace("49,65940000,12/", "49,65940000,11/")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("counts 11 records");
    }

    @Test
    void malformedFilesAreRefusedWhole() {
        assertThatThrownBy(() -> parse(SAMPLE.replace("99,65940000,1,16/\n", "")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("99 file trailer");
        assertThatThrownBy(() -> parse(SAMPLE.substring(SAMPLE.indexOf('\n') + 1)))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("01 file header");
        assertThatThrownBy(() -> parse(SAMPLE.replace("16,165,3247500", "16,165,32475.00")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("whole number of cents");
        assertThatThrownBy(() -> parse(SAMPLE.replace("16,165,3247500", "16,050,3247500")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("neither a credit");
        assertThatThrownBy(() -> parse(SAMPLE.replace("260105", "261305")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("YYMMDD");
        assertThatThrownBy(() -> parse(SAMPLE.replace("ACME", "ACMÉ")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("ASCII");
        assertThatThrownBy(() -> parse(SAMPLE.replace("ACME", "AC\u0001ME")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("control character");
        assertThatThrownBy(() -> parse(SAMPLE.replace("16,165,3247500", "17,165,3247500")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("Unknown BAI2 record 17");
    }

    @Test
    void aFileOfMoreThanOneAccountIsRefused() {
        String second = "03,000999999999,USD,010,0,,Z,015,0,,Z/\n49,0,2/\n";
        assertThatThrownBy(() -> parse(SAMPLE.replace("98,", second + "98,")))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("one bank account");
    }

    @Test
    void aFileWithoutBalancesIsRefused() {
        String text = SAMPLE.replace("03,000123456789,USD,010,25320000,,Z,015,25655500,,Z/",
            "03,000123456789,USD,010,25320000,,Z/").replace("65940000", "40284500");
        assertThatThrownBy(() -> parse(text)).isInstanceOf(ImportFileException.class)
            .hasMessageContaining("closing (015)");
    }

    @Test
    void limitsAreHonoured() {
        assertThatThrownBy(() -> parse(SAMPLE, ParseLimits.DEFAULT.withMaxRecords(5)))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("more than 5 lines");
        assertThatThrownBy(() -> parse(SAMPLE, new ParseLimits(20_000, 256, 30, 1_000_000)))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("longer than 30");
        assertThatThrownBy(() -> parse(SAMPLE, new ParseLimits(20_000, 256, 32_768, 100)))
            .isInstanceOf(ImportFileException.class).hasMessageContaining("larger than 100 bytes");
    }
}
