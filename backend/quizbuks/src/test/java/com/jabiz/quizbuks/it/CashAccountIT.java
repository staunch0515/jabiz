package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.WalletCodes;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sponsors' cash accounts (Q87a, docs/quizbuks/plans/Q87-fee-account.md): a top-up goes into the Kudos wallet
 * (2110) or the cash account (2160) as the switches allow; a refund takes back from where it went; cash is turned into
 * Kudos and back one to one under the wallet's lock as the switches allow; the daily reconciliation and the monthly
 * report know the cash accounts.
 */
class CashAccountIT extends TopUpItSupport {

    @Autowired
    @Qualifier("qbReconcileJob")
    JobDefinition<?> reconcileJob;

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "Q87a"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    Map<String, Object> cashAccount(String sponsorId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_wallet WHERE owner_id = ? AND kind = 'CASH'",
            sponsorId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    BigDecimal cash(String sponsorId) {
        Map<String, Object> account = cashAccount(sponsorId);
        return account == null ? BigDecimal.ZERO : (BigDecimal) account.get("balance");
    }

    /** A card top-up to {@code target}, paid; returns its id. */
    String paidTopUp(String sponsor, long amount, String target) {
        String topUpId = (String) run("QB_TOPUP_START", sponsorToken(sponsor), body("amount", amount, "target",
            target)).get("topUpId");
        runJobs();
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID").containsEntry("target", target);
        return topUpId;
    }

    Map<String, Object> refund(String topUpId, long amount, long refunded) {
        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("id", "ch_" + topUpId.replace("-", ""));
        charge.put("object", "charge");
        charge.put("payment_intent", paymentOf(topUpId));
        charge.put("amount", amount);
        charge.put("amount_refunded", refunded);
        charge.put("currency", "jpy");
        charge.put("livemode", false);
        return event(eventId(), "charge.refunded", charge);
    }

    static int count(Map<String, Object> row) {
        return ((Number) row.get("transactionCount")).intValue();
    }

    static List<Map<String, Object>> exchangePostings(String sponsorId) {
        return query("SELECT x.direction AS exchange, a.account_code, e.direction, e.amount, e.dimension_1 "
            + "FROM qb_kudos_exchange x JOIN ledger_transaction_version t ON t.source_entity = 'QbKudosExchange' "
            + "AND t.source_id = x.exchange_id JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id WHERE x.sponsor_id = ? "
            + "ORDER BY t.process_seq_id, e.line_no", sponsorId);
    }

    @Test
    void cashIsToppedUpRefundedAndTurnedIntoKudosAsTheSwitchesAllow() {
        String sponsor = newSponsor("s-q87a-cash");
        String token = sponsorToken(sponsor);

        // Only the Kudos wallet offered: the cash account is refused, the default goes to Kudos as before.
        param(QbParams.TOPUP_TARGETS, "KUDOS");
        assertThat(codes(refused("QB_TOPUP_START", token, body("amount", 5000, "target", "CASH"), 422)))
            .containsExactly(WalletCodes.TOPUP_TARGET);
        String kudos = paidTopUp(sponsor, 2000, "KUDOS");
        assertThat(postingsOf(kudos)).extracting(line -> line.get("account_code")).containsExactly("1110", "2110");
        assertThat(balance(sponsor)).isEqualByComparingTo("2000");

        // Both offered: a top-up into the cash account credits 2160 and the cash account, not the Kudos.
        param(QbParams.TOPUP_TARGETS, "KUDOS,CASH");
        String cash = paidTopUp(sponsor, 5000, "CASH");
        assertThat(postingsOf(cash)).extracting(line -> line.get("account_code") + ":" + line.get("direction"))
            .containsExactly("1110:DEBIT", "2160:CREDIT");
        assertThat(cash(sponsor)).isEqualByComparingTo("5000");
        assertThat(balance(sponsor)).isEqualByComparingTo("2000");
        // The default may be the cash account.
        param(QbParams.TOPUP_DEFAULT_TARGET, "CASH");
        String byDefault = (String) run("QB_TOPUP_START", token, body("amount", 1000)).get("topUpId");
        assertThat(topUp(byDefault)).containsEntry("target", "CASH");

        // A refund takes back from where the top-up went.
        deliver(refund(cash, 5000, 1000));
        assertThat(cash(sponsor)).isEqualByComparingTo("4000");
        assertThat(postingsOf(cash)).filteredOn(line -> "DEBIT".equals(line.get("direction"))
            && "2160".equals(line.get("account_code"))).singleElement()
            .satisfies(line -> assertThat(money(line.get("amount"))).isEqualByComparingTo("1000"));

        // Buying Kudos: switched off, then on; never more than what is available.
        param(QbParams.KUDOS_BUY_ENABLED, "false");
        assertThat(codes(refused("QB_KUDOS_BUY", token, body("amount", 1000), 422)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_OFF);
        param(QbParams.KUDOS_BUY_ENABLED, "true");
        Map<String, Object> bought = run("QB_KUDOS_BUY", token, body("amount", 3000));
        assertThat(money(bought.get("kudosBalance"))).isEqualByComparingTo("5000");
        assertThat(money(bought.get("cashBalance"))).isEqualByComparingTo("1000");
        assertThat(balance(sponsor)).isEqualByComparingTo("5000");
        assertThat(cash(sponsor)).isEqualByComparingTo("1000");
        assertThat(codes(refused("QB_KUDOS_BUY", token, body("amount", 1001), 422)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_FUNDS);

        // Selling Kudos back: off by default (not set), then on.
        assertThat(codes(refused("QB_KUDOS_SELL", token, body("amount", 100), 422)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_OFF);
        param(QbParams.KUDOS_SELL_ENABLED, "true");
        run("QB_KUDOS_SELL", token, body("amount", 500));
        assertThat(balance(sponsor)).isEqualByComparingTo("4500");
        assertThat(cash(sponsor)).isEqualByComparingTo("1500");
        assertThat(exchangePostings(sponsor)).extracting(row -> row.get("exchange") + " " + row.get("account_code")
            + ":" + row.get("direction") + ":" + money(row.get("amount")).stripTrailingZeros().toPlainString())
            .containsExactly("TO_KUDOS 2160:DEBIT:3000", "TO_KUDOS 2110:CREDIT:3000",
                "TO_CASH 2110:DEBIT:500", "TO_CASH 2160:CREDIT:500");
        assertThat(exchangePostings(sponsor)).allSatisfy(row -> assertThat(row).containsEntry("dimension_1", sponsor));

        // The sponsor reads both on the wallet's template, and the page's switches.
        Map<String, Object> wallet = items("qb.sponsor.wallet", token, Map.of()).getFirst();
        assertThat(money(wallet.get("balance"))).isEqualByComparingTo("4500");
        assertThat(money(wallet.get("cashBalance"))).isEqualByComparingTo("1500");
        assertThat(items("qb.sponsor.cash-settings", token, Map.of()).getFirst())
            .containsEntry("targets", "KUDOS,CASH").containsEntry("defaultTarget", "CASH")
            .containsEntry("buyEnabled", true).containsEntry("sellEnabled", true);
        // Nobody else's.
        refused("QB_KUDOS_BUY", sponsorToken(newSponsor("s-q87a-other")), body("amount", 1), 422);
        assertThat(cash(sponsor)).isEqualByComparingTo("1500");
        assertBooksBalance();

        // The reconciliation finds the cash accounts as the ledger has them.
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        Instant at = clock.instant().plusSeconds(60);
        assertThat(jobs.run(reconcileJob, at)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(query("SELECT r.status FROM qb_recon_run_version r WHERE r.scheduled_time = ?",
            java.sql.Timestamp.from(at)).getFirst()).containsEntry("status", "OK");

        // The monthly report classes the cash account's postings.
        YearMonth month = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, Map<String, Object>> monthly = new LinkedHashMap<>();
        items("qb.admin.monthly", tokenOf(user("f-q87a"), QbRoles.ADMIN_FINANCE), Map.of("params",
            Map.of("from", from.toString(), "to", to.toString()), "limit", 100))
            .forEach(row -> monthly.put((String) row.get("category"), row));
        assertThat(count(monthly.get("CASH_TOPUP"))).isEqualTo(1);
        assertThat(money(monthly.get("CASH_TOPUP").get("sponsorCash"))).isEqualByComparingTo("-5000");
        assertThat(count(monthly.get("CASH_REFUND"))).isEqualTo(1);
        assertThat(count(monthly.get("KUDOS_BOUGHT"))).isEqualTo(1);
        assertThat(money(monthly.get("KUDOS_BOUGHT").get("sponsorWallets"))).isEqualByComparingTo("-3000");
        assertThat(count(monthly.get("KUDOS_SOLD"))).isEqualTo(1);
        assertThat(count(monthly.get("OTHER"))).isEqualTo(0);
    }

    /** Q87b: the receipt and the mail of a top-up into the cash account say so, not "Kudos". */
    @Test
    void aCashTopUpsReceiptAndMailNameTheCashAccount() {
        param(QbParams.TOPUP_TARGETS, "KUDOS,CASH");
        param(QbParams.TOPUP_DEFAULT_TARGET, "KUDOS");
        param(QbParams.ISSUER_NAME, "QuizBuks Operations K.K.");
        String sponsor = newSponsor(unique("s-q87b-receipt"));
        String topUpId = (String) run("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 3000, "target", "CASH"))
            .get("topUpId");
        runJobs();
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        assertThat(items("qb.sponsor.topup-receipt", sponsorToken(sponsor), Map.of("params",
            Map.of("topUpId", topUpId, "language", "en"))).getFirst().get("credited").toString())
            .isEqualTo("3,000 (into the cash account)");
        assertThat(run("QB_TOPUP_RECEIPT_ISSUE", sponsorToken(sponsor), body("topUpId", topUpId, "language", "en"))
            .get("runId")).isNotNull();
        deliverEvents();
        assertThat(query("SELECT template FROM sys_mail_message WHERE user_id = ?::uuid", sponsor))
            .extracting(row -> row.get("template")).contains("qb.topup.paid-cash").doesNotContain("qb.topup.paid");
    }
}
