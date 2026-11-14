package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiCost;
import com.jabiz.quizbuks.content.ai.ClaudeWebResearch;
import com.jabiz.quizbuks.content.ai.SiteProfileJob;
import com.jabiz.quizbuks.content.ai.SiteProfileProcesses;
import com.jabiz.quizbuks.fee.AiFeeRules;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9 (#692 re-review L-a): a website draft reserves its job's own limits (the worst case) until the three controlled
 * limits of {@code qb.ai.fee.site-profile.*} are first published, and less after: the first change gives each its
 * kind ({@link QbParams#AI_FEE_SITE_LIMIT_KIND}). #702 review L7: the amounts themselves, from the job's frozen values
 * (the web reader's calls at the job's input and output, or the limits below them; its attempts; the markup, the rate
 * and the tax rounded up), and a draft the credit cannot hold refused for that amount.
 */
class AiSiteReservationIT extends AiCreditItSupport {

    private String sponsorWithCredit(String prefix) {
        String sponsor = newSponsor(prefix + UUID.randomUUID());
        cash(sponsor, 20_000);
        buy(sponsor, 20_000);
        return sponsor;
    }

    private Map<String, Object> request(String token) {
        return run(SiteProfileProcesses.REQUEST, token, siteInput());
    }

    private static Map<String, Object> siteInput() {
        return body("siteAddress", "https://www.aini.example/", "language", "ja", "otherLanguages", List.of("en"),
            "rightsDeclared", true, "aiNoticeVersionNo", noticeVersion());
    }

    private BigDecimal reservation(String sponsor) {
        return money(job(request(sponsorFull(sponsor)).get("aiJobId")).get("reserved_yen"));
    }

    /** The tax rate the fee is reserved at (qb.fee.tax-rate in effect). */
    private static BigDecimal taxRate() {
        return money(query("SELECT param_value AS value FROM sys_param_version WHERE param_key = ? "
            + "ORDER BY effect_start_time DESC, version_no DESC LIMIT 1", QbParams.FEE_TAX_RATE).getFirst().get("value"));
    }

    /**
     * What a website draft of these limits (null: the job's own) reserves, computed here from the job's frozen input and
     * output: every call of each attempt at most the pages' input and the output, on the model and its fallback.
     */
    private static BigDecimal expected(Object aiJobId, Integer calls, Integer pages, Integer output) {
        Map<String, Object> row = query("SELECT model, fee_markup, fee_usd_jpy, "
            + "(params::jsonb->>'reservedInputTokens')::bigint AS input, (params::jsonb->>'maxOutputTokens')::int AS output "
            + "FROM qb_ai_job WHERE ai_job_id = ?", String.valueOf(aiJobId)).getFirst();
        long input = ((Number) row.get("input")).longValue();
        int out = ((Number) row.get("output")).intValue();
        if (pages != null) {
            input = Math.min(input, SiteProfileJob.reservedInput(pages));
        }
        if (output != null) {
            out = Math.min(out, output);
        }
        String model = (String) row.get("model");
        BigDecimal maxUsd = AiCost.maxOf(model, ClaudeWebResearch.attempt(model, input, out,
            calls == null ? ClaudeWebResearch.MAX_CALLS : calls), SiteProfileJob.ATTEMPTS, 0, null);
        assertThat(maxUsd).as("the model's price").isNotNull();
        return AiFeeRules.reservation(maxUsd, money(row.get("fee_markup")), money(row.get("fee_usd_jpy")), taxRate());
    }

    private void publishLimit(String key, String value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paramKey", key);
        values.put("value", value);
        values.put("valueKind", QbParams.AI_FEE_SITE_LIMIT_KIND);
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", values, "reason", "F-M9 L-a"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    @Test
    void publishingTheSiteLimitsLowersTheReservationToWhatTheyAllow() {
        feeOn();
        param(QbParams.SITE_PROFILE_ENABLED, "true");
        // Not set: the job's own limits (the worst case), to the yen.
        Map<String, Object> worstJob = job(request(sponsorFull(sponsorWithCredit("ai-site-worst-"))).get("aiJobId"));
        BigDecimal worst = money(worstJob.get("reserved_yen"));
        assertThat(worst).isPositive()
            .isEqualByComparingTo(expected(worstJob.get("ai_job_id"), null, null, null));

        // A draft the credit cannot hold is refused for that amount, and nothing is recorded.
        String poor = newSponsor("ai-site-poor-" + UUID.randomUUID());
        cash(poor, 1_000);
        buy(poor, 1);
        String poorToken = sponsorFull(poor);
        Map<String, Object> problem = refused(SiteProfileProcesses.REQUEST, poorToken, siteInput(), 422);
        assertThat(codes(problem)).containsExactly(AiCodes.CREDIT_SHORT);
        assertThat(String.valueOf(problem)).contains("may cost up to " + worst.toPlainString() + " yen");
        assertThat(query("SELECT 1 FROM qb_ai_job WHERE sponsor_id = ?", poor)).isEmpty();

        // Set: the limits below the job's, to the yen.
        publishLimit(QbParams.AI_FEE_SITE_CALLS, "2");
        publishLimit(QbParams.AI_FEE_SITE_PAGES, "5");
        publishLimit(QbParams.AI_FEE_SITE_OUTPUT, "4000");
        Map<String, Object> lowerJob = job(request(sponsorFull(sponsorWithCredit("ai-site-p95-"))).get("aiJobId"));
        BigDecimal lower = money(lowerJob.get("reserved_yen"));
        assertThat(lower).isPositive().isLessThan(worst)
            .isEqualByComparingTo(expected(lowerJob.get("ai_job_id"), 2, 5, 4000));
    }
}
