package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9b (docs/quizbuks/plans/impl/F-M9.md §7): what the Sponsor's AI credit page reads - the credit at a glance
 * (qb.sponsor.ai-credit), its lots (granted credit with its expiry, then cash), the charged requests and the movements
 * - each the caller's own, another Sponsor seeing none of it.
 */
class AiCreditPagesIT extends AiCreditItSupport {

    @Test
    void theAiCreditPageReadsTheSponsorsOwnCreditLotsRequestsAndMovements() {
        feeOn();
        String sponsor = newSponsor("ai-page-" + UUID.randomUUID());
        String grantId = grant(sponsor, 300);
        cash(sponsor, 2_000);
        buy(sponsor, 1_000);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for the page.");
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
        BigDecimal reserved = money(job(aiJobId).get("reserved_yen"));

        // While the request runs: it holds its reservation.
        Map<String, Object> glance = items("qb.sponsor.ai-credit", token, Map.of()).getFirst();
        assertThat(money(glance.get("remaining"))).isEqualByComparingTo("1000");
        assertThat(money(glance.get("bought"))).isEqualByComparingTo("1000");
        assertThat(money(glance.get("reserved"))).isEqualByComparingTo(reserved);
        assertThat(money(glance.get("unbilled"))).isZero();

        // Once it ended: its fee waits for the month's charge.
        runUntilDone(aiJobId);
        BigDecimal fee = money(job(aiJobId).get("fee_yen"));
        glance = items("qb.sponsor.ai-credit", token, Map.of()).getFirst();
        assertThat(money(glance.get("reserved"))).isZero();
        assertThat(money(glance.get("unbilled"))).isEqualByComparingTo(fee);
        assertThat(((Number) glance.get("unbilledJobs")).intValue()).isEqualTo(1);

        // Its lots: the granted credit with its expiry first, then the cash.
        List<Map<String, Object>> lots = items("qb.sponsor.ai-credit-lots", token, Map.of());
        assertThat(lots).extracting(lot -> lot.get("subAccount") + " " + money(lot.get("amount")).toPlainString())
            .containsExactlyInAnyOrder("FEE_CREDIT 300", "CASH 700");
        assertThat(lots).filteredOn(lot -> grantId.equals(lot.get("lotId"))).singleElement()
            .satisfies(lot -> assertThat(lot.get("expiresAt")).isNotNull());

        assertThat(items("qb.sponsor.ai-credit-jobs", token, Map.of())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("aiJobId", String.valueOf(aiJobId)).containsEntry("charged", false);
            assertThat(money(row.get("feeYen"))).isEqualByComparingTo(fee);
        });
        assertThat(items("qb.sponsor.ai-credit-movements", token, Map.of())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "BUY");
            assertThat(money(row.get("amount"))).isEqualByComparingTo("1000");
            assertThat(money(row.get("credit"))).isEqualByComparingTo("300");
            assertThat(money(row.get("cash"))).isEqualByComparingTo("700");
        });

        // Another Sponsor sees nothing of it.
        String other = sponsorFull(newSponsor("ai-page-other-" + UUID.randomUUID()));
        Map<String, Object> theirs = items("qb.sponsor.ai-credit", other, Map.of()).getFirst();
        assertThat(money(theirs.get("remaining"))).isZero();
        assertThat(money(theirs.get("unbilled"))).isZero();
        assertThat(items("qb.sponsor.ai-credit-lots", other, Map.of())).isEmpty();
        assertThat(items("qb.sponsor.ai-credit-jobs", other, Map.of())).isEmpty();
        assertThat(items("qb.sponsor.ai-credit-movements", other, Map.of())).isEmpty();
    }
}
