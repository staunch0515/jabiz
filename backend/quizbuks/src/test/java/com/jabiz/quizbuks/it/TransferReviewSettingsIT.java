package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbPayouts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transfers above the review limit while no review rule is in effect (docs/quizbuks/plans/Q6-payments.md,
 * D-Q6-12): nothing is written for the taker (no transfer, nothing they see as failed), they are skipped, and finance
 * gets one task a day, not one per taker.
 */
class TransferReviewSettingsIT extends PayoutItSupport {

    /** The transfers' review rule switched off (four eyes), as an administrator might. */
    @BeforeEach
    void withoutTheTransferRule() {
        List<Map<String, Object>> rules = query("SELECT DISTINCT ON (rule_id) rule_id, enabled FROM "
            + "sys_approval_rule_version WHERE rule_code = ? ORDER BY rule_id, version_no DESC", QbPayouts.REVIEW_RULE);
        assertThat(rules).hasSize(1);
        if (Boolean.TRUE.equals(rules.getFirst().get("enabled"))) {
            Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity",
                "SysApprovalRule", "targetId", String.valueOf(rules.getFirst().get("rule_id")), "values",
                Map.of("enabled", false), "reason", "Test"));
            run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
        }
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "1000");
        transfersOn();
    }

    @Test
    void largeTransfersAreSkippedWithOneTaskADay() {
        String first = payee("t-review-missing-1");
        enabledPayee(first);
        earn(first, 1500);
        String second = payee("t-review-missing-2");
        enabledPayee(second);
        earn(second, 2500);
        String small = payee("t-review-missing-small");
        enabledPayee(small);
        earn(small, 1000);

        runTransfers();
        assertThat(transfersOf(first)).isEmpty();
        assertThat(transfersOf(second)).isEmpty();
        assertThat(ledger("2120", first)).isEqualByComparingTo("1500");
        // Not above the limit: sent as usual.
        assertThat(transfersOf(small)).singleElement().satisfies(t -> assertThat(t).containsEntry("status",
            "SENDING"));
        Map<?, ?> output = (Map<?, ?>) json.convertValue(systemRun("QB_TRANSFER_CREATE", body("userId", first)),
            Map.class);
        assertThat(output.get("reason")).isEqualTo("REVIEW_NOT_CONFIGURED");
        assertThat(output.get("transferId")).isNull();
        String day = now().atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString();
        assertThat(tasksAbout("transfer-review-missing:")).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("title_key", "task.qb.transfer-check.REVIEW_NOT_CONFIGURED")
            .containsEntry("source_key", "transfer-review-missing:" + day));

        // The next day: one more task.
        advance(Duration.ofDays(1));
        runTransfers();
        assertThat(tasksAbout("transfer-review-missing:")).hasSize(2);
        assertThat(transfersOf(first)).isEmpty();
    }
}
