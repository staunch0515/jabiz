package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.journal.JournalCodes;
import com.jabiz.quizbuks.journal.JournalPeriods;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The journal export within the platform's limits (Q9d review H1, M1, M3 d), lowered here: a read gives at most 1,000
 * rows and a generated file holds at most 20,000 bytes. A month of many postings is exported whole, its detail in
 * several parts each within both limits, every posting once; a transaction that could not fit a part refuses the
 * export instead of losing it. While another database transaction has been open for long, nothing is exported. When
 * keeping a file fails after the voucher's number was drawn, the number goes back with the rest: the next export
 * still gets the first.
 */
@TestPropertySource(properties = {"jabiz.process.max-read-rows=1000", "jabiz.files.generated.max-bytes=20000",
    "quizbuks.journal.max-open-transaction=PT2S"})
class JournalLimitsIT extends JournalItSupport {

    static final int MAX_BYTES = 20_000;
    static final int MAX_ROWS = 1_000;
    static final int PARTIES = 10;
    static final int TRANSACTIONS = 30;

    final List<String> sponsors = new ArrayList<>();

    /**
     * A transaction of {@code parties} sponsors' prepayments moved and moved back (2110, both sides): two detail rows
     * per sponsor, and nothing the reconciliation would see.
     */
    String wide(java.time.Instant bookingTime, int parties, String reference) {
        List<Map<String, Object>> entries = new ArrayList<>();
        while (sponsors.size() < parties) {
            sponsors.add(newSponsor("s-limits-" + sponsors.size()));
        }
        for (int p = 0; p < parties; p++) {
            String party = sponsors.get(p);
            entries.add(body("accountCode", "2110", "direction", "DEBIT", "amount", 1 + p, "dimensions",
                Map.of("party", party)));
            entries.add(body("accountCode", "2110", "direction", "CREDIT", "amount", 1 + p, "dimensions",
                Map.of("party", party)));
        }
        return post(bookingTime, reference, entries);
    }

    @Test
    void aMonthBiggerThanAReadOrAFileIsExportedWholeInParts() throws Exception {
        ZonedDateTime jst = now().atZone(JournalPeriods.ZONE).plusMonths(1).withDayOfMonth(1)
            .truncatedTo(ChronoUnit.DAYS);
        YearMonth first = YearMonth.from(jst);
        JournalPeriods.Period one = JournalPeriods.month(first);
        JournalPeriods.Period two = JournalPeriods.month(first.plusMonths(1));
        clock.set(one.start().plus(Duration.ofDays(2)));
        Set<String> posted = new HashSet<>();
        for (int i = 0; i < TRANSACTIONS; i++) {
            posted.add(wide(one.start().plus(Duration.ofDays(2)).plusSeconds(i), PARTIES, "vol-" + i));
        }
        clock.set(one.end().plus(Duration.ofHours(4)));
        assertThat(reconcile(clock.instant())).isEqualTo("OK");
        mapAll();
        advance(JournalPeriods.SETTLE.plusMinutes(1));

        Map<String, Object> preview = preview();
        int parts = (Integer) preview.get("detailParts");
        assertThat(preview).containsEntry("ready", true).containsEntry("withinLimits", true)
            .containsEntry("nearLimit", false)
            .containsEntry("transactionCount", TRANSACTIONS).containsEntry("detailRows", 2 * PARTIES * TRANSACTIONS);
        // More than one part: the detail (600 rows of some 300 bytes each) is larger than one file.
        assertThat(parts).isGreaterThan(2);

        // Keeping a file fails after the number was drawn (M3 d): nothing stays, the number included.
        execute("CREATE FUNCTION qb_test_fail_archive() RETURNS trigger LANGUAGE plpgsql AS "
            + "'BEGIN RAISE EXCEPTION ''archive failed (test)''; END'");
        execute("CREATE TRIGGER qb_test_fail_archive BEFORE INSERT ON sys_generated_file FOR EACH ROW "
            + "EXECUTE FUNCTION qb_test_fail_archive()");
        try {
            var failed = call("QB_JOURNAL_EXPORT", finance(), body("month", first.toString())).expectBody(MAP)
                .returnResult();
            assertThat(failed.getStatus().is5xxServerError()).as(String.valueOf(failed.getResponseBody())).isTrue();
        } finally {
            execute("DROP TRIGGER qb_test_fail_archive ON sys_generated_file");
            execute("DROP FUNCTION qb_test_fail_archive()");
        }
        assertThat(vouchersDrawn()).isZero();
        assertThat(query("SELECT 1 AS x FROM qb_journal_export_version")).isEmpty();

        // Another transaction open for long (M1): it may have begun a posting before the cut-off that commits after
        // the export's reads, so nothing is exported until it ends.
        try (Connection open = DB.connect(schema()); Statement statement = open.createStatement()) {
            open.setAutoCommit(false);
            statement.execute("SELECT 1");
            Thread.sleep(3_000);
            Map<String, Object> waiting = preview();
            assertThat(waiting).containsEntry("ready", false);
            assertThat((Integer) waiting.get("openTransactions")).isPositive();
            assertThat(refusedExport(first.toString())).containsExactly(JournalCodes.TRANSACTION_OPEN);
            open.rollback();
        }
        assertThat(vouchersDrawn()).isZero();

        Map<String, Object> exported = export(first.toString());
        assertThat(exported).containsEntry("voucherNo", "QBJ-" + first.getYear() + "-00001")
            .containsEntry("transactionCount", TRANSACTIONS).containsEntry("lineCount", 2 * PARTIES);
        assertThat(detailFiles(exported)).hasSize(parts);
        List<List<String>> rows = new ArrayList<>();
        List<String> transactions = new ArrayList<>();
        int partNo = 0;
        for (byte[] part : detailParts(exported)) {
            partNo++;
            assertThat(part.length).as("part " + partNo).isLessThanOrEqualTo(MAX_BYTES);
            List<List<String>> csv = csv(part);
            assertThat(csv.size() - 1).as("part " + partNo).isPositive().isLessThanOrEqualTo(MAX_ROWS);
            List<List<String>> body = csv.subList(1, csv.size());
            assertThat(body).allSatisfy(row -> assertThat(row.getFirst()).isEqualTo(exported.get("voucherNo")));
            rows.addAll(body);
            // A transaction is never split between parts.
            body.stream().map(row -> row.get(2)).distinct().forEach(transactions::add);
        }
        assertThat(rows).hasSize(2 * PARTIES * TRANSACTIONS);
        assertThat(transactions).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(posted);
        assertThat(rows.stream().map(row -> new BigDecimal(row.get(8))).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo(BigDecimal.valueOf(2L * TRANSACTIONS * PARTIES * (PARTIES + 1) / 2));
        assertThat(rows.stream().map(row -> Integer.parseInt(row.get(1))).distinct().toList())
            .hasSize(2 * PARTIES).allSatisfy(lineNo -> assertThat(lineNo).isBetween(1, 2 * PARTIES));
        // The list gives the parts.
        assertThat(items("qb.admin.journal-export-parts", finance(), Map.of("params", Map.of("exportIds",
            List.of(exported.get("exportId")))))).hasSize(parts);
        assertThat(items("qb.admin.journal-exports", finance(), Map.of()).getFirst())
            .containsEntry("detailPartCount", parts);

        // A transaction too large for a part (40 rows, some 12,000 bytes: over half a file): refused, not lost.
        clock.set(two.start().plus(Duration.ofDays(1)));
        wide(clock.instant(), 2 * PARTIES, "too wide");
        clock.set(two.end().plus(Duration.ofHours(4)));
        assertThat(reconcile(clock.instant())).isEqualTo("OK");
        advance(JournalPeriods.SETTLE.plusMinutes(1));
        assertThat(preview()).containsEntry("withinLimits", false).containsEntry("nearLimit", true)
            .containsEntry("ready", false);
        assertThat(refusedExport(two.label())).containsExactly(JournalCodes.TOO_LARGE);
        assertThat(vouchersDrawn()).isEqualTo(1);
        onlyInserted(List.of("qb_journal_export_version", "qb_journal_export_part_version"));
    }
}
