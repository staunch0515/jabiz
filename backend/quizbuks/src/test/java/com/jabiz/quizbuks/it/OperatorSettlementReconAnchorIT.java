package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.quizbuks.wallet.Reconciliation;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 c (review M2): the window the daily run reads the operators' settlements from begins three months before the last
 * clean run that held these checks (its anchor). A run with no clean one before reads all of the history; while the runs
 * stay differing the anchor stays where it was, so a difference is not lost by the time it takes to put right; a clean run
 * written before the checks existed is no anchor. Also the waiting hours' least value and the check's fallback for the
 * fees with no operator. Each test has times of its own; the operators of the tests have no month to wait for (created late).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementReconAnchorIT extends CapItSupport {

    private static Reconciliation.ReconcileOutput run(CapItSupport it, Instant at) {
        return (Reconciliation.ReconcileOutput) it.systemRun("QB_RECONCILE_RUN", it.body("scheduledTime", at.toString()));
    }

    private Reconciliation.ReconcileOutput reconcileAt(Instant at) {
        clock.set(at);
        deliverEvents();
        return run(this, at);
    }

    private static List<Map<String, Object>> items(Object runId) {
        return query("SELECT * FROM qb_recon_item_version WHERE run_id = ?::uuid ORDER BY check_code, reference",
            String.valueOf(runId));
    }

    /** No operator has a month to wait for: the waiting check is tried by itself. */
    private static void quiet() {
        execute("UPDATE qb_operator SET created_at = '2100-01-01T00:00:00Z'");
    }

    private static void tamper(String operatorId, String month, String sql) {
        execute("UPDATE qb_operator_settlement SET " + sql + " WHERE operator_id = ? AND month = ?", operatorId, month);
    }

    @Test
    @Order(2)
    void withNoCleanRunBeforeItAllOfTheHistoryIsRead() {
        String[] who = start(2049);
        manualFee(who[1], 100);
        settle(who[0], "2049-02", "2049-03-02T00:00:00Z");
        quiet();
        // A column of a settlement issued two years before the run is wrong: the first run reads all of it.
        tamper(who[0], "2049-02", "platform_fees = platform_fees + 5");
        Reconciliation.ReconcileOutput first = reconcileAt(Instant.parse("2051-01-10T18:37:00Z"));
        assertThat(first.status()).isEqualTo(ReconChecks.DIFF);
        assertThat(items(first.runId())).anySatisfy(item -> assertThat(item)
            .containsEntry("check_code", ReconChecks.OPERATOR_SETTLEMENT_COVERAGE)
            .containsEntry("reference", "2049-02:platformFees"));
        tamper(who[0], "2049-02", "platform_fees = platform_fees - 5");
    }

    @Test
    @Order(3)
    void theAnchorStaysAtTheLastCleanRunWhileTheRunsDifferAndAnEarlierRunWithoutTheChecksIsNoAnchor() {
        String[] who = start(2052);
        manualFee(who[1], 100);
        settle(who[0], "2052-02", "2052-03-02T00:00:00Z");
        quiet();
        // The first run after it: clean, and the anchor (its window begins on 2052-03-01, before the settlement).
        Reconciliation.ReconcileOutput clean = reconcileAt(Instant.parse("2052-06-01T18:37:00Z"));
        assertThat(clean.status()).isEqualTo(ReconChecks.OK);
        tamper(who[0], "2052-02", "platform_fees = platform_fees + 5");
        // Differing runs: each reads from the clean run's window. A window that followed the last run would have moved
        // past the settlement (issued on March 2nd) by the end of September.
        for (String day : List.of("2052-06-20", "2052-09-30", "2052-10-01")) {
            Reconciliation.ReconcileOutput differing = reconcileAt(Instant.parse(day + "T18:37:00Z"));
            assertThat(differing.status()).as(day).isEqualTo(ReconChecks.DIFF);
            assertThat(items(differing.runId())).as(day).anySatisfy(item -> assertThat(item)
                .containsEntry("reference", "2052-02:platformFees"));
        }
        // A clean-looking run written before the checks existed (no key of them in its summary) is no anchor: this clone of
        // the clean run, with the summary of an older one, would move the window past the settlement if it counted.
        cloneRun(clean.runId(), Instant.parse("2052-08-01T18:37:00Z"), "{\"CASH_ACCOUNT\":0}");
        Reconciliation.ReconcileOutput after = reconcileAt(Instant.parse("2052-10-02T18:37:00Z"));
        assertThat(after.status()).isEqualTo(ReconChecks.DIFF);
        assertThat(items(after.runId())).anySatisfy(item -> assertThat(item)
            .containsEntry("reference", "2052-02:platformFees"));
        tamper(who[0], "2052-02", "platform_fees = platform_fees - 5");
    }

    /** A copy of a run (the registry row too) at another scheduled time, with another summary. */
    private static void cloneRun(String runId, Instant scheduled, String summary) {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT ?::uuid, entity_type, "
            + "created_seq_id FROM entity_registry WHERE entity_id = ?::uuid", id.toString(), runId);
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'qb_recon_run_version' AND column_name <> 'row_id' ORDER BY "
            + "ordinal_position").stream().map(row -> String.valueOf(row.get("column_name"))).toList();
        String values = String.join(", ", columns.stream().map(c -> switch (c) {
            case "run_id" -> "?::uuid";
            case "scheduled_time" -> "?";
            case "summary" -> "?";
            default -> c;
        }).toList());
        execute("INSERT INTO qb_recon_run_version (" + String.join(", ", columns) + ") SELECT " + values
            + " FROM qb_recon_run_version WHERE run_id = ?::uuid", id.toString(), Timestamp.from(scheduled), summary, runId);
    }

    @Test
    @Order(4)
    void theWaitingHoursAreTakenFromThirtyAndAnOutOfRangeProposalIsRefusedOrClamped() {
        clock.set(Instant.parse("2053-01-10T01:00:00Z"));
        String operatorId = newOperator(hq(), unique("Hours"));
        quiet();
        execute("UPDATE qb_operator SET created_at = '2053-01-10T01:00:00Z' WHERE operator_id = ?", operatorId);
        // Five digits do not fit the parameter's kind (4): refused when proposed. Below 30 is allowed to be proposed and
        // is taken as 30 by the check (the platform has no minimum for a parameter).
        Map<String, Object> proposal = body("targetEntity", "SysParam", "values", Map.of("paramKey",
            QbParams.OPERATOR_SETTLEMENT_WAITING_AFTER_HOURS, "value", "10000"), "reason", "range test");
        String proposer = TestTokens.bearer(tokens, "super-admin", "control.propose");
        assertThat(call("CONTROL_CHANGE_PROPOSE", proposer, proposal).expectBody(MAP).returnResult().getStatus()
            .is2xxSuccessful()).isFalse();
        setControlledParam(QbParams.OPERATOR_SETTLEMENT_WAITING_AFTER_HOURS, "29");
        clock.set(Instant.parse("2053-01-11T01:00:00Z"));
        // January ended at Jan 31, 15:00 UTC; 29 hours after it, still nothing; 30 hours after it, reported.
        Reconciliation.ReconcileOutput before = reconcileAt(Instant.parse("2053-02-01T20:30:00Z"));
        assertThat(items(before.runId()).stream().filter(i -> operatorId.equals(i.get("party_id")))).isEmpty();
        Reconciliation.ReconcileOutput later = reconcileAt(Instant.parse("2053-02-01T21:30:00Z"));
        assertThat(items(later.runId())).anySatisfy(item -> assertThat(item)
            .containsEntry("check_code", ReconChecks.OPERATOR_SETTLEMENT_WAITING).containsEntry("party_id", operatorId));
    }
}
