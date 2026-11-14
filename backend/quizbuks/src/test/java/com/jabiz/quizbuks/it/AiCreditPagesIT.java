package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.AiCreditReturns;
import com.jabiz.quizbuks.content.ai.AiCreditStanding;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.setup.QbParams;
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

    /**
     * F-M9b: QB_AI_CREDIT_STANDING answers what a request can use (as its quote reads it), what may be given back (the
     * most a return takes), and "low" only once qb.ai.credit.low-amount is set above what is available.
     */
    @Test
    void theStandingIsWhatTheRequestsAndTheReturnReadAndSaysWhenItIsLow() {
        feeOn();
        String sponsor = newSponsor("ai-standing-" + UUID.randomUUID());
        cash(sponsor, 3_000);
        buy(sponsor, 2_000);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for the standing.");
        request(token, quiz.get(0), List.of(quiz.get(1)), 2);

        Map<String, Object> standing = run(AiCreditStanding.STANDING, token, Map.of());
        assertThat(standing).containsEntry("feeOn", true).containsEntry("low", false);
        BigDecimal available = money(standing.get("available"));
        assertThat(money(standing.get("remaining"))).isEqualByComparingTo("2000");
        assertThat(money(standing.get("reserved"))).isPositive();
        assertThat(available).isEqualByComparingTo(money(standing.get("remaining"))
            .subtract(money(standing.get("reserved"))));
        Map<String, Object> quote = run(AiProcesses.QUOTE, token, requestInput(quiz.get(0), List.of(quiz.get(1)), 2));
        assertThat(money(quote.get("availableYen"))).isEqualByComparingTo(available);

        // The most a return takes is what may be given back.
        BigDecimal returnable = money(standing.get("returnable"));
        assertThat(codes(refused(AiCreditReturns.RETURN, token, body("amount", returnable.add(BigDecimal.ONE),
            "requestKey", UUID.randomUUID().toString()), 422))).containsExactly(FeeCodes.AI_CREDIT_RETURN_ABOVE);

        // Low: never while not set; once set above what is available, low; at or below it, not.
        assertThat(standing.get("lowAmount")).isNull();
        yenParam(QbParams.AI_CREDIT_LOW_AMOUNT, available.add(BigDecimal.ONE).toPlainString());
        assertThat(run(AiCreditStanding.STANDING, token, Map.of())).containsEntry("low", true);
        yenParam(QbParams.AI_CREDIT_LOW_AMOUNT, available.toPlainString());
        assertThat(run(AiCreditStanding.STANDING, token, Map.of())).containsEntry("low", false);
        // The fee off: never low.
        yenParam(QbParams.AI_CREDIT_LOW_AMOUNT, available.add(BigDecimal.ONE).toPlainString());
        param(QbParams.AI_FEE_ENABLED, "false");
        try {
            assertThat(run(AiCreditStanding.STANDING, token, Map.of())).containsEntry("feeOn", false)
                .containsEntry("low", false);
        } finally {
            param(QbParams.AI_FEE_ENABLED, "true");
            yenParam(QbParams.AI_CREDIT_LOW_AMOUNT, "0");
        }
    }
}
