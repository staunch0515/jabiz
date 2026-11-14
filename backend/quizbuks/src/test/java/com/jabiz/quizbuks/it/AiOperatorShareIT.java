package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.content.ai.AiFeeSettlements;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9a-2 (plan §3 last item; #699 review M4): an AI fee in the operators' settlement, through the real path (a charged
 * job, the month's charge from the AI credit with its cost part, the operator's month settled). Out of the list of
 * kinds (the default): all of it stays out of the base; in it: the cost part does; with
 * {@code qb.operator.share.ai-cost-in-base}: none does. The parameters take effect at the clock, which only moves on:
 * the tests run in this order, a month each.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiOperatorShareIT extends AiCreditItSupport {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    @Autowired
    JobDefinition<AiFeeSettlements.SettleRef> qbAiFeeMonthlyJob;

    /** The AI charge of one finished job of a new sponsor, and the operator's settlement of its month. */
    private record Settled(Map<String, Object> charge, Map<String, Object> line) {}

    private Settled aiFeeSettled(String prefix) {
        feeOn();
        List<String> s = charged(prefix);
        Object aiJobId = request(s.get(1), s.get(2), List.of(s.get(3)), 2).get("aiJobId");
        runUntilDone(aiJobId);
        Map<String, Object> done = job(aiJobId);
        assertThat(done).containsEntry("status", QbAi.READY);
        String operatorId = String.valueOf(done.get("fee_operator_id"));
        String month = String.valueOf(done.get("fee_period"));
        Instant at = YearMonth.parse(month).plusMonths(1).atDay(1).atTime(0, 27).atZone(ZONE).toInstant();
        clock.set(at.plusSeconds(60));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobRunner.run(qbAiFeeMonthlyJob, at)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        Map<String, Object> charge = query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'AI'",
            s.get(0)).getFirst();
        assertThat(money(charge.get("share_excluded"))).isPositive().isLessThan(money(charge.get("amount")));
        advance(Duration.ofDays(2));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", month));
        Map<String, Object> settlement = query("SELECT * FROM qb_operator_settlement WHERE operator_id = ? AND "
            + "month = ?", operatorId, month).getFirst();
        Map<String, Object> line = query("SELECT * FROM qb_operator_settlement_line_version WHERE settlement_id = ? "
            + "AND line_kind = 'CHARGE' AND charge_id = ?", String.valueOf(settlement.get("settlement_id")),
            String.valueOf(charge.get("charge_id"))).getFirst();
        return new Settled(charge, line);
    }

    @Test
    @Order(1)
    void anAiFeeOutsideTheListIsAllExcludedFromTheBase() {
        String kinds = latest(QbParams.OPERATOR_SHARE_KINDS);
        assertThat(kinds == null || !kinds.contains("AI")).as("the list in force has no AI: " + kinds).isTrue();
        Settled settled = aiFeeSettled("ai-share-out-");
        assertThat(money(settled.line().get("excluded"))).isEqualByComparingTo(money(settled.charge().get("amount")));
    }

    @Test
    @Order(2)
    void anAiFeeInTheListKeepsItsCostPartOutOfTheBase() {
        param(QbParams.OPERATOR_SHARE_KINDS, "MONTHLY,MANUAL,AUDIENCE,DISTRIBUTION,EXPOSURE,AI");
        Settled settled = aiFeeSettled("ai-share-in-");
        assertThat(money(settled.line().get("excluded")))
            .isEqualByComparingTo(money(settled.charge().get("share_excluded")));
    }

    @Test
    @Order(3)
    void withTheAiCostInTheBaseNothingOfItIsExcluded() {
        param(QbParams.OPERATOR_SHARE_AI_COST_IN_BASE, "true");
        Settled settled = aiFeeSettled("ai-share-base-");
        assertThat(money(settled.line().get("excluded"))).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
