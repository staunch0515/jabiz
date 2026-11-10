package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.quizbuks.fee.LightGrants;
import com.jabiz.quizbuks.play.SponsorPlay;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S4b (the extra review): a settlement grants a Sponsor several nodes at once, but at most
 * {@code quizbuks.tree-grants.max-per-run} (here 1); the daily job grants the rest on its next run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "quizbuks.tree-grants.max-per-run=1")
class TreeGrantRunLimitIT extends TreeItSupport {

    @Autowired
    @Qualifier("qbTreeGrantRetryJob")
    JobDefinition<FeeCredits.SponsorRef> retryJob;

    private void param(String key, String value, Map<String, Object> kind) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("paramKey", key);
        values.put("value", value);
        if (kind != null) {
            values.put("valueKind", kind);
        }
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", values, "reason", "F-S4b test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    private void pass(String sponsor, String publicationId) {
        String token = sponsorToken(sponsor);
        String attemptId = (String) run(SponsorPlay.START, token, body("publicationId", publicationId))
            .get("attemptId");
        Map<String, Object> input = submission(attemptId);
        input.put("answers", shown(attemptId, answers(true, true)));
        assertThat(run(SponsorPlay.SUBMIT, token, input)).containsEntry("passed", true);
        deliverEvents();
    }

    private String challengeOn(Copy copy, String nodeId) {
        return liveBound(copy.operator().leadId(), unique("FS4b run"), copy.treeId(), nodeId,
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
    }

    private List<Object> keys(String sponsor) {
        return query("SELECT source_key FROM qb_fee_credit_grant WHERE sponsor_id = ? AND source = 'LIGHT'", sponsor)
            .stream().map(row -> row.get("source_key")).toList();
    }

    /** The daily job's next run (a day later: a run of the same time is not run twice). */
    private void runRetry() {
        advance(java.time.Duration.ofDays(1));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.execute(retryJob, clock.instant());
    }

    @Test
    void aSettlementGrantsSeveralNodesUpToItsLimitAndTheDailyJobTheRest() {
        param(QbParams.TREE_GRANT_MAX_PER_NODE, "300", QbParams.FEE_CREDIT_MANUAL_MAX_KIND);
        Map<String, Object> start = node(null, "start", "Getting started", all(passed()));
        start.put("grantAmount", 100);
        Map<String, Object> next = node(null, "next", "Running a quiz", all(lit("start", true), passed()));
        next.put("grantAmount", 150);
        Copy copy = copy(List.of(start, next));
        String sponsor = sponsorOf(copy.operator(), "s-fs4b-run");

        // Both lit while the switch is off: nothing granted yet.
        pass(sponsor, challengeOn(copy, copy.start()));
        pass(sponsor, challengeOn(copy, copy.next()));
        assertThat(keys(sponsor)).isEmpty();

        // On: the first run grants one (its limit), the next the other; then nothing more.
        param(QbParams.FEE_CREDIT_GRANT_ENABLED, "true", null);
        runRetry();
        assertThat(keys(sponsor)).hasSize(1);
        runRetry();
        assertThat(keys(sponsor)).containsExactlyInAnyOrder(LightGrants.sourceKey(copy.templateId(), copy.start()),
            LightGrants.sourceKey(copy.templateId(), copy.next()));
        assertThat(query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = 'FEE_CREDIT'", sponsor)
            .getFirst().get("balance")).satisfies(b -> assertThat((BigDecimal) b).isEqualByComparingTo("250"));
        runRetry();
        assertThat(keys(sponsor)).hasSize(2);
    }
}
