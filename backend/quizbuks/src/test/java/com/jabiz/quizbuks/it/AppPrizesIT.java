package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.reward.CashRewardProcesses;
import com.jabiz.quizbuks.reward.DigitalPrizeProcesses;
import com.jabiz.quizbuks.reward.RedemptionProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The App's two new reads (F-F4, docs/quizbuks/plans/impl/F-F4.md section 4): {@code qb.app.prizes}, the taker's
 * prizes of all three kinds with whether each is still open, and the {@code usable} column of {@code qb.app.wallet},
 * which says whether Kudos can be used yet.
 */
class AppPrizesIT extends PayoutItSupport {

    private String methodsAdmin() {
        return TestTokens.bearer(tokens, "ff4-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
    }

    private List<Map<String, Object>> prizes(String token, Map<String, Object> params) {
        return items("qb.app.prizes", token, Map.of("params", params, "limit", 200));
    }

    private String publication(String sponsor, String name, String method, Map<String, Object> more) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", method);
        overrides.putAll(more);
        return livePublication(sponsor, name, overrides);
    }

    /** The taker passes the publication: the reward is made (a prize, a cash reward or a digital prize). */
    private String win(String token, String publicationId, String status) {
        String attemptId = (String) start(token, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        assertThat(submit(token, attemptId, answers(true, true))).containsEntry("rewardStatus", status);
        return attemptId;
    }

    private static Map<String, Object> only(List<Map<String, Object>> rows, String kind) {
        return rows.stream().filter(row -> kind.equals(row.get("kind"))).findFirst().orElseThrow();
    }

    @Test
    void thePrizesOfAllThreeKindsAreOpenUntilTheyAreCollectedConfirmedOrSettled() {
        String sponsor = newSponsor("s-ff4-prizes");
        run(RewardMethodProcesses.SET, methodsAdmin(), body("userId", sponsor, "methods", List.of(RewardMethod.PLATFORM,
            RewardMethod.OFFLINE_REDEEM, RewardMethod.SPONSOR_CASH, RewardMethod.DIGITAL_PRIZE), "note", "F-F4 test"));
        String sponsorToken = sponsorToken(sponsor);
        String coffee = publication(sponsor, "Coffee week", RewardMethod.OFFLINE_REDEEM,
            Map.of("redeemDescription", "A coffee at our counter"));
        String cash = publication(sponsor, "Cash week", RewardMethod.SPONSOR_CASH, Map.of());
        String digital = publication(sponsor, "Premium week", RewardMethod.DIGITAL_PRIZE, Map.of(
            "digitalDelivery", DigitalDelivery.SHARED, "digitalDescription", "A month of Premium",
            "digitalContent", "https://example.com/premium?code=WELCOME"));

        String mei = newTaker("t-ff4-mei", "JP");
        String meiToken = taker(mei);
        String ren = taker(newTaker("t-ff4-ren", "JP"));
        // Nothing yet, for anybody.
        assertThat(prizes(meiToken, Map.of())).isEmpty();

        win(meiToken, coffee, "REDEEM");
        win(meiToken, cash, "CASH");
        win(meiToken, digital, "DIGITAL");
        deliverEvents();

        // All three, open, with what the page shows; the newest first is the order of the sort, not of the kinds.
        List<Map<String, Object>> open = prizes(meiToken, Map.of("open", true));
        assertThat(open).hasSize(3);
        assertThat(prizes(meiToken, Map.of())).hasSize(3);
        assertThat(only(open, "REDEEM")).containsEntry("status", "DUE").containsEntry("publicationName", "Coffee week")
            .containsEntry("description", "A coffee at our counter").containsEntry("amount", 100)
            .containsEntry("sourceKind", null);
        assertThat(only(open, "REDEEM").get("sponsorCompany")).isNotNull();
        assertThat(only(open, "REDEEM").get("expiresAt")).isNotNull();
        assertThat(only(open, "CASH")).containsEntry("status", "DUE").containsEntry("publicationName", "Cash week")
            .containsEntry("expiresAt", null);
        assertThat((String) only(open, "CASH").get("code")).startsWith("C-");
        assertThat(only(open, "DIGITAL")).containsEntry("status", "DELIVERED").containsEntry("code", null)
            .containsEntry("description", "A month of Premium");
        // Only the caller's own.
        assertThat(prizes(ren, Map.of())).isEmpty();
        // The list's own filters.
        assertThat(items("qb.app.prizes", meiToken, Map.of("filters", List.of(Map.of("field", "kind", "op", "eq", "value", "CASH")), "limit", 50)))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("kind", "CASH"));

        // The digital prize confirmed: settled, no longer open, still in the list of all.
        String digitalPrizeId = (String) only(open, "DIGITAL").get("prizeId");
        run(DigitalPrizeProcesses.CONFIRM, meiToken, body("digitalPrizeId", digitalPrizeId));
        assertThat(prizes(meiToken, Map.of("open", true))).extracting(row -> row.get("kind"))
            .containsExactlyInAnyOrder("REDEEM", "CASH");
        assertThat(only(prizes(meiToken, Map.of()), "DIGITAL")).containsEntry("status", "CONFIRMED");

        // The cash reward marked paid is still open (the taker confirms), confirmed it is not.
        String cashRewardId = (String) only(open, "CASH").get("prizeId");
        run(CashRewardProcesses.MARK_PAID, sponsorToken, body("cashRewardId", cashRewardId, "note", "Receipt 1"));
        assertThat(only(prizes(meiToken, Map.of("open", true)), "CASH")).containsEntry("status", "MARKED_PAID");
        run(CashRewardProcesses.CONFIRM, meiToken, body("cashRewardId", cashRewardId));
        assertThat(prizes(meiToken, Map.of("open", true))).extracting(row -> row.get("kind"))
            .containsExactly("REDEEM");

        // The prize collected at the counter by the sponsor's rotating code: settled.
        String code = (String) run(RedemptionProcesses.ISSUE, sponsorToken, body("kind", "ROTATING")).get("redeemKey");
        String redemptionId = (String) only(open, "REDEEM").get("prizeId");
        run(RedemptionProcesses.CLAIM, meiToken, body("scannedKey", code, "redemptionId", redemptionId));
        assertThat(prizes(meiToken, Map.of("open", true))).isEmpty();
        assertThat(prizes(meiToken, Map.of())).hasSize(3).extracting(row -> row.get("status"))
            .containsExactlyInAnyOrder("REDEEMED", "CONFIRMED", "CONFIRMED");
    }

    @Test
    void kudosAreUsableOnlyOnceTransfersOrBankWithdrawalsAreOn() {
        String mei = taker(newTaker("t-ff4-wallet", "JP"));
        // The switches of the first launch: both off.
        param(QbParams.TRANSFER_ENABLED, "false");
        param(QbParams.WITHDRAWAL_BANK_ENABLED, "false");
        assertThat(usable(mei)).isFalse();
        param(QbParams.TRANSFER_ENABLED, "true");
        assertThat(usable(mei)).isTrue();
        param(QbParams.WITHDRAWAL_BANK_ENABLED, "true");
        assertThat(usable(mei)).isTrue();
        param(QbParams.TRANSFER_ENABLED, "false");
        assertThat(usable(mei)).isTrue();
        param(QbParams.WITHDRAWAL_BANK_ENABLED, "false");
        assertThat(usable(mei)).isFalse();
    }

    private boolean usable(String token) {
        Map<String, Object> wallet = items("qb.app.wallet", token, Map.of("params", Map.of("at", now().toString())))
            .getFirst();
        return (Boolean) wallet.get("usable");
    }
}
