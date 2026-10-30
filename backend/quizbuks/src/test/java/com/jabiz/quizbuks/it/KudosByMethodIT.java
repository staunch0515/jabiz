package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.reward.CashRewardProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The taker's Kudos by reward method (docs/quizbuks/plans/Q58-kudos-by-method.md, template {@code qb.app.kudos}): a
 * row for each of the four methods with what was earned, settled, lapsed and is still owed, in all or in a period.
 */
class KudosByMethodIT extends PayoutItSupport {

    private String methodsAdmin() {
        return TestTokens.bearer(tokens, "q58-admin", QbPermissions.ADMIN_REWARD_METHODS);
    }

    private String cashLike(String sponsor, String name, String method, Map<String, Object> more) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", method);
        overrides.putAll(more);
        return livePublication(sponsor, name, overrides);
    }

    private Map<String, Map<String, Object>> kudos(String token, Instant from, Instant to) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (from != null) {
            params.put("from", from.toString());
        }
        if (to != null) {
            params.put("to", to.toString());
        }
        return items("qb.app.kudos", token, Map.of("params", params, "limit", 10)).stream()
            .collect(Collectors.toMap(row -> (String) row.get("method"), Function.identity()));
    }

    private static void assertRow(Map<String, Object> row, long earned, long settled, long lapsed, long balance,
        long rewards) {
        Map<String, Long> numbers = new LinkedHashMap<>();
        for (String key : List.of("earned", "settled", "lapsed", "balance", "rewards")) {
            numbers.put(key, ((Number) row.get(key)).longValue());
        }
        assertThat(numbers).as(String.valueOf(row.get("method"))).containsExactly(Map.entry("earned", earned),
            Map.entry("settled", settled), Map.entry("lapsed", lapsed), Map.entry("balance", balance),
            Map.entry("rewards", rewards));
    }

    @Test
    void eachMethodSaysWhatWasEarnedSettledAndIsStillOwedInAllAndInAPeriod() {
        String sponsor = newSponsor("s-q58");
        run(RewardMethodProcesses.SET, methodsAdmin(), body("userId", sponsor, "methods", List.of(RewardMethod.PLATFORM,
            RewardMethod.SPONSOR_CASH, RewardMethod.OFFLINE_REDEEM, RewardMethod.DIGITAL_PRIZE), "note", "Q58 test"));
        credit(sponsor, 10_000);
        String platform = livePublication(sponsor, "Q58 platform", Map.of("regions", List.of("GLOBAL")));
        String cash = cashLike(sponsor, "Q58 cash", RewardMethod.SPONSOR_CASH, Map.of());
        String prize = cashLike(sponsor, "Q58 prize", RewardMethod.OFFLINE_REDEEM,
            Map.of("redeemDescription", "A coffee at our counter"));
        String digital = cashLike(sponsor, "Q58 digital", RewardMethod.DIGITAL_PRIZE, Map.of("digitalDelivery",
            "SHARED", "digitalDescription", "A month of Premium", "digitalContent", "https://example.com/p?c=Q58"));
        String takerId = newTaker("t-q58", "JP");
        Instant before = clock.instant();

        // Nothing yet: the four rows, all nought.
        Map<String, Map<String, Object>> rows = kudos(taker(takerId), null, null);
        assertThat(rows.keySet()).containsExactlyInAnyOrder(RewardMethod.PLATFORM, RewardMethod.SPONSOR_CASH,
            RewardMethod.OFFLINE_REDEEM, RewardMethod.DIGITAL_PRIZE);
        rows.values().forEach(row -> assertRow(row, 0, 0, 0, 0, 0));

        clock.advance(Duration.ofMinutes(1));
        assertThat(play(takerId, platform, true, true)).containsEntry("rewardStatus", "PAID");
        assertThat(play(takerId, cash, true, true)).containsEntry("rewardStatus", "CASH");
        assertThat(play(takerId, prize, true, true)).containsEntry("rewardStatus", "REDEEM");
        assertThat(play(takerId, digital, true, true)).containsEntry("rewardStatus", "DIGITAL");
        // The wallet holds what the taker received (the reward less the commission).
        long net = ledger("2120", takerId).longValueExact();
        assertThat(net).isPositive().isLessThanOrEqualTo(100);

        // The sponsor pays the cash, the taker confirms it.
        clock.advance(Duration.ofDays(1));
        Instant settling = clock.instant();
        String cashRewardId = String.valueOf(latest("qb_cash_reward_version", "cash_reward_id",
            "attempt_id IN (SELECT attempt_id FROM qb_attempt WHERE user_id = '" + takerId + "' AND publication_id = '"
                + cash + "')").getFirst().get("cash_reward_id"));
        run(CashRewardProcesses.MARK_PAID, sponsorToken(sponsor), body("cashRewardId", cashRewardId));
        run(CashRewardProcesses.CONFIRM, taker(takerId), body("cashRewardId", cashRewardId));

        rows = kudos(taker(takerId), null, null);
        assertRow(rows.get(RewardMethod.PLATFORM), net, 0, 0, net, 1);
        assertThat(((Number) rows.get(RewardMethod.PLATFORM).get("pendingAmount")).longValue()).isZero();
        assertRow(rows.get(RewardMethod.SPONSOR_CASH), 100, 100, 0, 0, 1);
        assertRow(rows.get(RewardMethod.OFFLINE_REDEEM), 100, 0, 0, 100, 1);
        // A shared digital prize is delivered at once.
        assertRow(rows.get(RewardMethod.DIGITAL_PRIZE), 100, 100, 0, 0, 1);

        // The day of the settling: nothing earned in it, what was settled in it, and the balances as they are now.
        rows = kudos(taker(takerId), settling, settling.plus(Duration.ofDays(1)));
        assertRow(rows.get(RewardMethod.PLATFORM), 0, 0, 0, net, 0);
        assertRow(rows.get(RewardMethod.SPONSOR_CASH), 0, 100, 0, 0, 0);
        assertRow(rows.get(RewardMethod.OFFLINE_REDEEM), 0, 0, 0, 100, 0);
        assertRow(rows.get(RewardMethod.DIGITAL_PRIZE), 0, 0, 0, 0, 0);
        // Before anything happened: nought, the balances still now's.
        rows = kudos(taker(takerId), null, before);
        assertRow(rows.get(RewardMethod.PLATFORM), 0, 0, 0, net, 0);
        assertRow(rows.get(RewardMethod.OFFLINE_REDEEM), 0, 0, 0, 100, 0);

        // The prize lapses unredeemed: lapsed, no longer owed.
        clock.advance(Duration.ofDays(31));
        String redemptionId = String.valueOf(latest("qb_redemption_version", "redemption_id",
            "user_id = '" + takerId + "'").getFirst().get("redemption_id"));
        systemRun("QB_REDEMPTION_EXPIRE", body("redemptionId", redemptionId));
        assertRow(kudos(taker(takerId), null, null).get(RewardMethod.OFFLINE_REDEEM), 100, 0, 100, 0, 1);

        // Only the caller's own: another taker has none of it.
        kudos(taker(newTaker("t-q58-other", "JP")), null, null).values().forEach(row -> assertRow(row, 0, 0, 0, 0, 0));
    }

    @Test
    void theWalletIsSettledByWhatTheTransfersTook() {
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
        String takerId = payee("t-q58-transfer");
        enabledPayee(takerId);
        earn(takerId, 1500);
        Instant before = clock.instant();
        assertRow(kudos(taker(takerId), null, null).get(RewardMethod.PLATFORM), 1500, 0, 0, 1500, 1);

        runTransfers();
        assertThat(transfersOf(takerId)).singleElement().satisfies(row -> assertThat(row).containsEntry("status",
            "SENDING"));
        // Out of the wallet: settled; the reward stays earned.
        assertRow(kudos(taker(takerId), null, null).get(RewardMethod.PLATFORM), 1500, 1500, 0, 0, 1);
        // In the period of the transfer only: settled, nothing earned.
        assertRow(kudos(taker(takerId), before.plusSeconds(1), null).get(RewardMethod.PLATFORM), 0, 1500, 0, 0, 0);
    }
}
