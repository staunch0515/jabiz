package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9c (#718 review H1): the reminder's due list reads granted credit held in AI credits once by credit and grant,
 * never a subquery per grant. With a thousand sponsors whose granted credit is all in their AI credit (one real
 * sponsor's rows cloned, no statistics gathered), every one of them is selected well within five seconds.
 */
class FeeCreditRemindDueBudgetIT extends AiCreditItSupport {

    private static final int SPONSORS = 1_000;
    private static final int PAGE = 100;

    /** The columns of a table the rows copied set, each with its type, in order. */
    private static List<Map<String, Object>> columns(String table) {
        // The identity columns (row_id) are left to the database.
        return query("SELECT column_name, udt_name FROM information_schema.columns WHERE table_schema = current_schema() "
            + "AND table_name = ? AND is_identity = 'NO' ORDER BY ordinal_position", table);
    }

    /**
     * Copies the rows of {@code table} matching {@code where} once per row of the mapping {@code m} (the given tables
     * after {@code t}, the row copied), each column as it is but those given an expression.
     */
    private static void cloneRows(String table, String joins, String where, Map<String, String> replaced,
        Object... args) {
        List<Map<String, Object>> cols = columns(table);
        String names = cols.stream().map(c -> (String) c.get("column_name")).collect(Collectors.joining(", "));
        String values = cols.stream().map(c -> {
            String name = (String) c.get("column_name");
            String expression = replaced.get(name);
            return expression == null ? "t." + name : "CAST(" + expression + " AS " + c.get("udt_name") + ")";
        }).collect(Collectors.joining(", "));
        execute("INSERT INTO " + table + " (" + names + ") SELECT " + values + " FROM " + table + " t " + joins
            + " WHERE " + where, args);
    }

    @Test
    void aThousandSponsorsWithGrantedCreditInTheirAiCreditAreSelectedQuickly() {
        feeOn();
        String sponsor = newSponsor("ai-remind-budget-" + UUID.randomUUID());
        String grantId = grant(sponsor, 1_000);
        cash(sponsor, 1_000);
        buy(sponsor, 1_500);
        String prepaymentId = String.valueOf(aiCredit(sponsor).get("prepayment_id"));
        execute("UPDATE qb_fee_credit_grant SET expires_at = ? WHERE grant_id = ?",
            Timestamp.from(clock.instant().plus(Duration.ofDays(10))), grantId);

        execute("CREATE TABLE remind_budget_map AS SELECT n, gen_random_uuid() AS sponsor, gen_random_uuid() AS grant_id, "
            + "gen_random_uuid() AS prepayment_id FROM generate_series(1, " + SPONSORS + ") n");
        try {
            String map = "CROSS JOIN remind_budget_map m";
            cloneRows("qb_fee_credit_grant", map, "t.grant_id = ?", Map.of("grant_id", "m.grant_id",
                "sponsor_id", "m.sponsor", "source_key", "t.source_key || ':' || m.n"), grantId);
            cloneRows("qb_prepayment", map, "t.prepayment_id = ?", Map.of("prepayment_id", "m.prepayment_id",
                "sponsor_id", "m.sponsor"), prepaymentId);
            // Each payment copied gets its own id, registered as the original is (the table's foreign key).
            execute("CREATE TABLE remind_budget_pay AS SELECT m.n, t.payment_id AS old_id, gen_random_uuid() AS new_id "
                + "FROM qb_fee_payment_version t CROSS JOIN remind_budget_map m WHERE t.target_id = ? OR "
                + "t.prepayment_id = ?", prepaymentId, prepaymentId);
            execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT p.new_id, "
                + "r.entity_type, r.created_seq_id FROM remind_budget_pay p JOIN entity_registry r ON r.entity_id = "
                + "p.old_id");
            cloneRows("qb_fee_payment_version", "JOIN remind_budget_pay p ON p.old_id = t.payment_id "
                + "JOIN remind_budget_map m ON m.n = p.n", "TRUE", Map.of(
                "payment_id", "p.new_id",
                "sponsor_id", "m.sponsor",
                "target_id", "CASE WHEN t.target_id = '" + prepaymentId + "' THEN CAST(m.prepayment_id AS text) "
                    + "ELSE t.target_id END",
                "prepayment_id", "CASE WHEN t.prepayment_id = '" + prepaymentId + "' THEN CAST(m.prepayment_id AS text) "
                    + "ELSE t.prepayment_id END",
                "grant_id", "CASE WHEN t.grant_id = '" + grantId + "' THEN CAST(m.grant_id AS text) ELSE t.grant_id END"));
            Set<String> cloned = query("SELECT CAST(sponsor AS text) AS s FROM remind_budget_map").stream()
                .map(row -> String.valueOf(row.get("s"))).collect(Collectors.toSet());

            String system = TestTokens.bearer(tokens, "remind-budget", QbPermissions.FEE_SYSTEM);
            // The first page runs the whole statement (ordered by sponsor): its time is the statement's.
            long started = System.nanoTime();
            List<Map<String, Object>> page = items(FeeCredits.REMIND_DUE_TEMPLATE, system,
                Map.of("params", Map.of("at", clock.instant().toString()), "limit", PAGE, "offset", 0));
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            System.out.println("F-M9c: the reminder's due list of " + (SPONSORS + 1) + " sponsors in " + took.toMillis()
                + " ms");
            assertThat(took).as("the due list of %d sponsors", SPONSORS + 1).isLessThan(Duration.ofSeconds(5));

            Set<String> selected = new java.util.HashSet<>();
            for (int offset = 0; ; offset += PAGE) {
                if (offset > 0) {
                    page = items(FeeCredits.REMIND_DUE_TEMPLATE, system, Map.of("params",
                        Map.of("at", clock.instant().toString()), "limit", PAGE, "offset", offset));
                }
                page.forEach(row -> selected.add(String.valueOf(row.get("sponsorId"))));
                if (page.size() < PAGE) {
                    break;
                }
            }
            assertThat(selected).containsAll(cloned).contains(sponsor);
        } finally {
            execute("DROP TABLE IF EXISTS remind_budget_pay");
            execute("DROP TABLE remind_budget_map");
        }
    }
}
