package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.SiteProfileProcesses;
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
 * kind ({@link QbParams#AI_FEE_SITE_LIMIT_KIND}).
 */
class AiSiteReservationIT extends AiCreditItSupport {

    private String sponsorWithCredit(String prefix) {
        String sponsor = newSponsor(prefix + UUID.randomUUID());
        cash(sponsor, 20_000);
        buy(sponsor, 20_000);
        return sponsor;
    }

    private BigDecimal reservation(String sponsor) {
        String token = sponsorFull(sponsor);
        Map<String, Object> requested = run(SiteProfileProcesses.REQUEST, token, body("siteAddress",
            "https://www.aini.example/", "language", "ja", "otherLanguages", List.of("en"), "rightsDeclared", true,
            "aiNoticeVersionNo", noticeVersion()));
        return money(job(requested.get("aiJobId")).get("reserved_yen"));
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
    void publishingTheSiteLimitsLowersTheReservation() {
        feeOn();
        param(QbParams.SITE_PROFILE_ENABLED, "true");
        BigDecimal worst = reservation(sponsorWithCredit("ai-site-worst-"));
        assertThat(worst).isPositive();

        publishLimit(QbParams.AI_FEE_SITE_CALLS, "2");
        publishLimit(QbParams.AI_FEE_SITE_PAGES, "5");
        publishLimit(QbParams.AI_FEE_SITE_OUTPUT, "4000");
        BigDecimal lower = reservation(sponsorWithCredit("ai-site-p95-"));
        assertThat(lower).isPositive().isLessThan(worst);
    }
}
