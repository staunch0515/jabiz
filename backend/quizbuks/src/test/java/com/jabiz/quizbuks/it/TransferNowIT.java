package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.TransferRules;
import com.jabiz.quizbuks.wallet.WalletCodes;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The administrators' payouts (Q14): finance sees why a taker would or would not be transferred to now
 * ({@code QB_TRANSFER_PREVIEW}, which writes nothing) and transfers at once ({@code QB_TRANSFER_NOW}) with the daily
 * run's own checks; never to oneself. The takers' Kudos at a glance ({@code qb.admin.taker-kudos}).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransferNowIT extends PayoutItSupport {

    String finance() {
        return tokenOf(user("q14-finance"), QbRoles.ADMIN_FINANCE);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> preview(String userId) {
        return run("QB_TRANSFER_PREVIEW", finance(), body("userId", userId));
    }

    @Test
    @Order(1)
    void thePreviewSaysWhyNotAndWritesNothing() {
        String taker = payee("t-q14-why");
        // Switched off (the default).
        assertThat(preview(taker)).containsEntry("transfers", false).containsEntry("skip", TransferRules.DISABLED);
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
        // No payout account, then below the threshold.
        assertThat(preview(taker)).containsEntry("skip", TransferRules.NO_ACCOUNT);
        enabledPayee(taker);
        earn(taker, 400);
        Map<String, Object> below = preview(taker);
        assertThat(below).containsEntry("skip", TransferRules.BELOW_THRESHOLD);
        assertThat(new BigDecimal(String.valueOf(below.get("balance")))).isEqualByComparingTo("400");
        earn(taker, 800);
        Map<String, Object> ready = preview(taker);
        assertThat(ready).containsEntry("transfers", true).containsEntry("review", false);
        assertThat(new BigDecimal(String.valueOf(ready.get("amount")))).isEqualByComparingTo("1200");
        assertThat(transfersOf(taker)).isEmpty();
    }

    @Test
    @Order(2)
    void financeTransfersAtOnceWithTheDailyRunsChecks() {
        String taker = payee("t-q14-now");
        enabledPayee(taker);
        earn(taker, 1500);
        Map<String, Object> output = run("QB_TRANSFER_NOW", finance(), body("userId", taker));
        assertThat(output).containsEntry("status", "SENDING");
        runJobs();
        assertThat(transfersOf(taker)).singleElement().satisfies(t -> assertThat(t).containsEntry("status", "SENT"));
        assertThat(ledger("2120", taker)).isEqualByComparingTo("0");
        assertTakerCacheIsTheLedger(taker);

        // Nothing left: the reason comes back, nothing is written.
        assertThat(run("QB_TRANSFER_NOW", finance(), body("userId", taker))).containsEntry("reason",
            TransferRules.BELOW_THRESHOLD).containsEntry("transferId", null);
        assertThat(transfersOf(taker)).hasSize(1);
        assertBooksBalance();
    }

    @Test
    @Order(3)
    void notToOneselfNorWithoutThePermission() {
        String self = user("q14-finance");
        assertThat(codes(refused("QB_TRANSFER_NOW", finance(), body("userId", self), 422)))
            .containsExactly(WalletCodes.TRANSFER_NOW_OWN);
        String taker = payee("t-q14-refused");
        refused("QB_TRANSFER_NOW", tokenOf(user("q14-content"), QbRoles.ADMIN_CONTENT), body("userId", taker), 403);
        refused("QB_TRANSFER_PREVIEW", tokenOf(user("q14-content"), QbRoles.ADMIN_CONTENT), body("userId", taker),
            403);
    }

    @Test
    @Order(4)
    void aLargeOneWaitsForAReview() {
        param(QbParams.TRANSFER_REVIEW_ABOVE, "1000");
        try {
            String taker = payee("t-q14-review");
            enabledPayee(taker);
            earn(taker, 5000);
            assertThat(preview(taker)).containsEntry("transfers", true).containsEntry("review", true);
            Map<String, Object> output = run("QB_TRANSFER_NOW", finance(), body("userId", taker));
            assertThat(output).containsEntry("status", "REVIEW");
            assertThat(output.get("approvalRequestId")).isNotNull();
        } finally {
            param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        }
    }

    @Test
    @Order(5)
    void theTakersKudosAtAGlance() {
        credit(sponsorA(), 1_000);
        String publicationId = livePublication(sponsorA(), "Q14 glance", Map.of("regions", List.of("GLOBAL")));
        String taker = newTaker("t-q14-glance", null);
        play(taker, publicationId, true, true);
        Map<String, Object> row = items("qb.admin.taker-kudos", finance(), Map.of("filters", List.of(Map.of(
            "field", "userId", "op", "in", "values", List.of(taker))))).getFirst();
        assertThat(row).containsEntry("userId", taker);
        assertThat(((Number) row.get("attempts")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("completed")).longValue()).isEqualTo(1);
        assertThat(new BigDecimal(String.valueOf(row.get("earned")))).isEqualByComparingTo("100");
        // Not for the content administrators: money is finance's.
        client.post().uri("/api/queries/qb.admin.taker-kudos").contentType(org.springframework.http.MediaType
                .APPLICATION_JSON).header(org.springframework.http.HttpHeaders.AUTHORIZATION,
                tokenOf(user("q14-content"), QbRoles.ADMIN_CONTENT))
            .bodyValue(Map.of()).exchange().expectStatus().isForbidden();
    }
}
