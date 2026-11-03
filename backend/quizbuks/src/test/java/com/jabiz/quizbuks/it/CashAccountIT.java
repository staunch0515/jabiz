package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchange;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.Reconciliation;
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
 * The cash account (docs/quizbuks/plans/Q87-fee-account.md, D-Q87-1 – D-Q87-3): a top-up into it, the switches of
 * the targets, cash turned into Kudos (which pays the rewards waiting) and Kudos back into cash, and the daily
 * reconciliation of it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CashAccountIT extends TopUpItSupport {

    Map<String, Object> wallet(String sponsor, String kind) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_wallet WHERE owner_id = ? AND kind = ?", sponsor, kind);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    BigDecimal balance(String sponsor, String kind) {
        Object value = wallet(sponsor, kind).get("balance");
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }

    Map<String, Object> myWallet(String sponsor) {
        return items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst();
    }

    static List<String> lines(String sourceEntity, String sourceId) {
        return query("SELECT a.account_code, e.direction, e.amount, e.dimension_1 FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = ? AND t.source_id = ? ORDER BY t.process_seq_id, e.line_no", sourceEntity,
            sourceId).stream().map(p -> p.get("account_code") + " " + p.get("direction") + " "
            + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()
            + (p.get("dimension_1") == null ? "" : " @" + p.get("dimension_1"))).toList();
    }

    @Test
    @Order(1)
    void aTopUpIntoTheCashAccountIsCreditedThereAndPaysNoReward() {
        String sponsor = newSponsor(unique("s-q87-cash"));
        // A reward waits: a cash top-up pays it not, a Kudos one would.
        String topUpId = (String) run("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 3000, "target", "CASH"))
            .get("topUpId");
        assertThat(topUp(topUpId)).containsEntry("target", "CASH");
        runJobs();
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        assertThat(lines("QbTopUp", topUpId)).containsExactly("1110 DEBIT 3000", "2160 CREDIT 3000 @" + sponsor);
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("3000");
        assertThat(wallet(sponsor, QbWallets.SPONSOR)).isEmpty();
        assertThat(outboxEvents(QbWallets.SETTLE_EVENT, "sponsorId", sponsor)).isZero();
        Map<String, Object> mine = myWallet(sponsor);
        assertThat(mine).containsEntry("cashBalance", 3000).containsEntry("balance", 0)
            .containsEntry("billingMode", "PREPAID");
        // The sponsor's top-ups name the target.
        assertThat(items("qb.sponsor.topups", sponsorToken(sponsor), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("target", "CASH"));
        // The settings the page reads: both targets, Kudos by default, buying on, selling off.
        assertThat(items("qb.sponsor.topup-settings", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("targets", "KUDOS,CASH").containsEntry("defaultTarget", "KUDOS")
            .containsEntry("buyEnabled", true).containsEntry("sellEnabled", false);
    }

    @Test
    @Order(2)
    void onlyTheTargetsTheAdministratorsOfferAreTakenAndTheDefaultWhenNoneIsNamed() {
        String sponsor = newSponsor(unique("s-q87-target"));
        param(QbParams.TOPUP_TARGETS, "KUDOS");
        try {
            Map<String, Object> problem = refused("QB_TOPUP_START", sponsorToken(sponsor),
                body("amount", 2000, "target", "CASH"), 422);
            assertThat(codes(problem)).containsExactly(WalletCodes.TOPUP_TARGET);
            String topUpId = (String) run("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 2000)).get("topUpId");
            assertThat(topUp(topUpId)).containsEntry("target", "KUDOS");
        } finally {
            param(QbParams.TOPUP_TARGETS, "KUDOS,CASH");
        }
        param(QbParams.TOPUP_DEFAULT_TARGET, "CASH");
        try {
            String topUpId = (String) run("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 2000)).get("topUpId");
            assertThat(topUp(topUpId)).containsEntry("target", "CASH");
        } finally {
            param(QbParams.TOPUP_DEFAULT_TARGET, "KUDOS");
        }
    }

    @Test
    @Order(3)
    void cashBecomesKudosOneForOneAndPaysTheRewardsWaiting() {
        String sponsor = newSponsor(unique("s-q87-buy"));
        String topUpId = (String) run("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 5000, "target", "CASH"))
            .get("topUpId");
        runJobs();
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        // Nothing can be published yet: the cash is not Kudos.
        assertThat(wallet(sponsor, QbWallets.SPONSOR)).isEmpty();

        Map<String, Object> problem = refused(KudosExchange.BUY, sponsorToken(sponsor), body("amount", 6000), 422);
        assertThat(codes(problem)).containsExactly(WalletCodes.KUDOS_EXCHANGE_SHORT);
        Map<String, Object> bought = run(KudosExchange.BUY, sponsorToken(sponsor), body("amount", 3000));
        assertThat(bought).containsEntry("direction", KudosExchange.BOUGHT).containsEntry("kudosBalance", 3000)
            .containsEntry("cashBalance", 2000);
        String exchangeId = (String) bought.get("exchangeId");
        assertThat(lines(KudosExchange.EXCHANGE, exchangeId))
            .containsExactly("2160 DEBIT 3000 @" + sponsor, "2110 CREDIT 3000 @" + sponsor);
        // Buying Kudos settles rewards waiting, like a top-up (the event is published; nothing waits here).
        assertThat(outboxEvents(QbWallets.SETTLE_EVENT, "sponsorId", sponsor)).isEqualTo(1);
        deliverEvents();
        assertThat(balance(sponsor, QbWallets.SPONSOR)).isEqualByComparingTo("3000");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("2000");
        assertThat(items("qb.sponsor.kudos-exchanges", sponsorToken(sponsor), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("direction", "BUY").containsEntry("amount", 3000));

        // Selling is off by default; on, only what is available goes back.
        assertThat(codes(refused(KudosExchange.SELL, sponsorToken(sponsor), body("amount", 100), 422)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_OFF);
        param(QbParams.KUDOS_SELL_ENABLED, "true");
        try {
            assertThat(codes(refused(KudosExchange.SELL, sponsorToken(sponsor), body("amount", 3001), 422)))
                .containsExactly(WalletCodes.KUDOS_EXCHANGE_SHORT);
            Map<String, Object> sold = run(KudosExchange.SELL, sponsorToken(sponsor), body("amount", 400));
            assertThat(sold).containsEntry("kudosBalance", 2600).containsEntry("cashBalance", 2400);
            assertThat(lines(KudosExchange.EXCHANGE, (String) sold.get("exchangeId")))
                .containsExactly("2110 DEBIT 400 @" + sponsor, "2160 CREDIT 400 @" + sponsor);
        } finally {
            param(QbParams.KUDOS_SELL_ENABLED, "false");
        }
        // Buying is switched off too, by the administrators.
        param(QbParams.KUDOS_BUY_ENABLED, "false");
        try {
            assertThat(codes(refused(KudosExchange.BUY, sponsorToken(sponsor), body("amount", 100), 422)))
                .containsExactly(WalletCodes.KUDOS_EXCHANGE_OFF);
        } finally {
            param(QbParams.KUDOS_BUY_ENABLED, "true");
        }

        // The daily reconciliation finds the cash accounts and the exchanges in order.
        Reconciliation.ReconcileOutput recon = (Reconciliation.ReconcileOutput) systemRun(Reconciliation.RUN,
            body("scheduledTime", clock.instant().toString()));
        assertThat(recon.status()).isEqualTo("OK");
    }

    @Test
    @Order(4)
    void aRefundOfACashTopUpIsTakenBackFromTheCashAccount() {
        String sponsor = newSponsor(unique("s-q87-refund"));
        String topUpId = (String) run("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 4000, "target", "CASH"))
            .get("topUpId");
        runJobs();
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("4000");
        Map<String, Object> charge = new java.util.LinkedHashMap<>();
        charge.put("id", "ch_" + topUpId.replace("-", ""));
        charge.put("object", "charge");
        charge.put("payment_intent", paymentOf(topUpId));
        charge.put("amount", 4000);
        charge.put("amount_refunded", 1000);
        charge.put("currency", "jpy");
        charge.put("refunded", false);
        charge.put("livemode", false);
        deliver(event(eventId(), "charge.refunded", charge));
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("3000");
        assertThat(lines("QbTopUp", topUpId)).contains("2160 DEBIT 1000 @" + sponsor);
        assertThat(topUp(topUpId).get("refunded_amount")).isEqualTo(new BigDecimal("1000"));
    }

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "Q87 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }
}
