package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuizBuks's own restore checks (operations.md section 2.4, docs/quizbuks/plans/impl/F-F13-launch-ops.md): the
 * templates listed in {@code DRILL_TEMPLATES} return no rows on a sound ledger and rows once it is not. The bad ledger
 * is made by hand, as a corrupt or inconsistent restore would leave it: one extra debit line on a sponsor's wallet
 * account, past the database's balance trigger.
 */
class RestoreChecksIT extends PlayItSupport {

    /** What operations.md section 2.4 puts in DRILL_TEMPLATES. */
    static final List<String> DRILL_TEMPLATES = List.of("qb.admin.verify-unbalanced-periods",
        "qb.admin.verify-overdrawn-wallets", "qb.system.recon-totals");

    private List<Map<String, Object>> rows(String template) {
        return items(template, admin(), Map.of("limit", 100));
    }

    @Test
    void theChecksFindNothingOnASoundLedgerAndTheProblemsOfABadOne() {
        String sponsor = newSponsor(unique("s-restore"));
        credit(sponsor, 1_000);
        for (String template : DRILL_TEMPLATES) {
            assertThat(rows(template)).as(template).isEmpty();
        }

        // Test set-up only: a debit of 3000 on the sponsor's 2110 line, in the credit's own transaction.
        Map<String, Object> line = query("SELECT e.entry_id, e.process_seq_id FROM ledger_entry_version e"
            + " JOIN ledger_account_version a ON a.account_id = e.account_id"
            + " WHERE a.account_code = '2110' AND e.dimension_1 = ? LIMIT 1", sponsor).getFirst();
        String extra = UUID.randomUUID().toString();
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id)"
            + " SELECT ?::uuid, entity_type, created_seq_id FROM entity_registry WHERE entity_id = ?",
            extra, line.get("entry_id"));
        execute("ALTER TABLE ledger_entry_version DISABLE TRIGGER ledger_entry_version_balanced");
        try {
            execute("INSERT INTO ledger_entry_version (entry_id, version_no, effect_start_time, created_time,"
                + " process_seq_id, is_deleted, transaction_id, account_id, line_no, direction, amount, dimension_1)"
                + " SELECT ?::uuid, 1, effect_start_time, created_time, process_seq_id, false, transaction_id,"
                + " account_id, line_no + 100, 'DEBIT', 3000, dimension_1 FROM ledger_entry_version"
                + " WHERE entry_id = ?", extra, line.get("entry_id"));
        } finally {
            execute("ALTER TABLE ledger_entry_version ENABLE TRIGGER ledger_entry_version_balanced");
        }

        assertThat(rows("qb.admin.verify-unbalanced-periods")).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("debit")).longValue()
                - ((Number) row.get("credit")).longValue()).isEqualTo(3_000));
        assertThat(rows("qb.admin.verify-overdrawn-wallets")).singleElement()
            .satisfies(row -> {
                assertThat(row).containsEntry("accountCode", "2110").containsEntry("partyId", sponsor);
                assertThat(((Number) row.get("overdrawn")).longValue()).isEqualTo(2_000);
            });
        assertThat(rows("qb.system.recon-totals")).extracting(row -> row.get("checkCode"))
            .containsExactlyInAnyOrder("TOTAL_2110", "LEDGER_ZERO");
    }
}
