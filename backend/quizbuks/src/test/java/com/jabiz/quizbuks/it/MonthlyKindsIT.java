package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.BankTopUps;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.quizbuks.wallet.ManualCredits;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14 G13 (docs/quizbuks/plans/impl/F-M14.md §3.2 point 4), the part that needs Stripe's stub: one of each kind of
 * money posting, driven through the processes in one month of this class's own schema (the plan's list; the fee side's
 * kinds are in {@link MonthlyKindsFeeIT}, which cannot share a class with Stripe's stub), then the guard of
 * {@link MonthlyKinds}: the report has rows only in the table's categories, every source of a posting is in the table,
 * and {@code OTHER} holds only the transactions that have no source (the development credit, the rewards given by
 * hand to a taker's balance).
 *
 * <p>Driven here: TOPUP, STRIPE_FEE, REFUND, DISPUTE (card, into the Kudos wallet), CASH_TOPUP, CASH_REFUND,
 * CASH_DISPUTE (card, into the cash account), BANK_TOPUP, CASH_BANK_TOPUP (bank transfer, approved), MANUAL_TOPUP, GRANT
 * (finance's hand-made credits, approved), KUDOS_BOUGHT, KUDOS_SOLD (cash to Kudos and back), REWARD, TRANSFER_SENT,
 * TRANSFER_SETTLED, TRANSFER_REVERSED, TRANSFER_FAILED, WITHDRAWAL_PAID (a bank withdrawal approved and paid), PAYOUT and
 * PAYOUT_FAILED.
 *
 * <p>Not driven in this class (too costly for a guard; each has its own row-by-row test): REFERRAL_REWARD
 * ({@code ReferralIT}: needs a tree's prerequisite, a claim and a pass), OPERATOR_SHARE, OPERATOR_PAID
 * ({@code OperatorSettlementIT}, fixed months and the settlement job), OPERATOR_TRANSFER_TO_DIRECT
 * ({@code OperatorTransferIT}), ENTRY_FEE (needs a taker holding transferable rewards after the hold period) and
 * ENTRY_FEE_SETTLED; the fee side's kinds are in {@link MonthlyKindsFeeIT}. Their rows are in the report all the same
 * (the guard asserts the report has every category of the table), and their sources are in the table.
 */
class MonthlyKindsIT extends PayoutItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    static final Instant ALL_FROM = Instant.parse("2000-01-01T00:00:00Z");
    static final Instant ALL_TO = Instant.parse("2100-01-01T00:00:00Z");

    String finance() {
        return tokenOf(user("mk-finance"), QbRoles.ADMIN_FINANCE);
    }

    String content() {
        return tokenOf(user("mk-content"), QbRoles.ADMIN_CONTENT);
    }

    String today() {
        return LocalDate.ofInstant(clock.instant(), TOKYO).toString();
    }

    void approve(String requestId) {
        run("APPROVAL_DECIDE", content(), body("requestId", requestId, "decision", "APPROVE"));
        deliverEvents();
    }

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

    /** A dispute of a top-up's charge, as Stripe reports it. */
    static Map<String, Object> dispute(String id, String topUpId, long amount, String status) {
        Map<String, Object> dispute = new LinkedHashMap<>();
        dispute.put("id", id);
        dispute.put("object", "dispute");
        dispute.put("charge", "ch_" + topUpId.replace("-", ""));
        dispute.put("payment_intent", paymentOf(topUpId));
        dispute.put("amount", amount);
        dispute.put("currency", "jpy");
        dispute.put("status", status);
        dispute.put("livemode", false);
        return dispute;
    }

    Map<String, Object> reversed(String transferId, long amountReversed) {
        Map<String, Object> transfer = new LinkedHashMap<>(StripeSim.TRANSFERS.get("qb-" + transferId));
        transfer.put("amount_reversed", amountReversed);
        transfer.put("reversed", amountReversed > 0);
        return event(eventId(), "transfer.reversed", transfer);
    }

    void bankSetUp() {
        for (Map.Entry<String, String> param : Map.of(QbParams.BANK_RECEIVING_BANK, "Mizuho Bank (0001)",
            QbParams.BANK_RECEIVING_BRANCH, "Head office (100)", QbParams.BANK_RECEIVING_TYPE, "Ordinary",
            QbParams.BANK_RECEIVING_NUMBER, "1234567", QbParams.BANK_RECEIVING_HOLDER, "クイズブックス（カ",
            QbParams.TOPUP_BANK_MIN, "1000", QbParams.TOPUP_BANK_MAX, "1000000").entrySet()) {
            param(param.getKey(), param.getValue());
        }
    }

    /** A bank transfer asked for, recorded by finance and approved by another administrator. */
    String bankTopUp(String sponsor, long amount, String target, String reference) {
        String topUpId = (String) run(BankTopUps.START, sponsorToken(sponsor), body("amount", amount, "target",
            target)).get("topUpId");
        String requestId = (String) run(BankTopUps.RECORD, finance(), body("topUpId", topUpId, "amount", amount,
            "receivedOn", today(), "bankReference", reference)).get("approvalRequestId");
        approve(requestId);
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        return topUpId;
    }

    @Test
    void everyKindOfMoneyPostingHereIsInItsCategoryAndOtherHoldsOnlyWhatHasNoSource() {
        feeRate("0.2");
        param(QbParams.TRANSFER_FEE, "100");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        param(QbParams.TOPUP_TARGETS, "KUDOS,CASH");
        param(QbParams.KUDOS_BUY_ENABLED, "true");
        param(QbParams.KUDOS_SELL_ENABLED, "true");
        bankSetUp();
        transfersOn();
        String sponsor = newSponsor(unique("s-mk"));
        // Of no source: the development credit (and, below, the rewards given to takers by hand).
        credit(sponsor, 700);
        ZonedDateTime month = now().atZone(ZoneOffset.UTC).plusMonths(1).withDayOfMonth(1)
            .truncatedTo(ChronoUnit.DAYS);
        clock.set(month.toInstant().plus(Duration.ofHours(1)));

        // TOPUP with Stripe's fee (STRIPE_FEE) and a refund (REFUND).
        String topUpId = openTopUp(sponsor, 10_000);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        STRIPE.script("/v1/payment_intents/" + paymentOf(topUpId), StubHttpServer.Answer.json(200, """
            {"id": "%s", "latest_charge": {"id": "ch_mk", "balance_transaction": {"id": "txn_mk", "amount": 10000,
             "fee": 360, "net": 9640, "currency": "jpy"}}}""".formatted(paymentOf(topUpId))));
        runJobs();
        assertThat(money(topUp(topUpId).get("stripe_fee"))).isEqualByComparingTo("360");
        deliver(event(eventId(), "charge.refunded", Map.of("id", "ch_mk", "payment_intent", paymentOf(topUpId),
            "amount", 10_000, "amount_refunded", 1_000, "currency", "jpy", "livemode", false)));
        assertThat(money(topUp(topUpId).get("refunded_amount"))).isEqualByComparingTo("1000");
        // DISPUTE: a top-up whose dispute is lost.
        String disputed = openTopUp(sponsor, 2_000);
        deliver(event(eventId(), "checkout.session.completed", completed(disputed)));
        deliver(event(eventId(), "charge.dispute.created", dispute("dp_mkcard", disputed, 2_000, "needs_response")));
        deliver(event(eventId(), "charge.dispute.closed", dispute("dp_mkcard", disputed, 2_000, "lost")));
        assertThat(money(topUp(disputed).get("dispute_clawback"))).isEqualByComparingTo("2000");

        // CASH_TOPUP, CASH_REFUND, CASH_DISPUTE, then KUDOS_BOUGHT and KUDOS_SOLD with the cash.
        String cashSponsor = newSponsor(unique("s-mk-cash"));
        String cashTopUp = paidTopUp(cashSponsor, 5_000, "CASH");
        deliver(refund(cashTopUp, 5_000, 1_000));
        String cashDisputed = paidTopUp(cashSponsor, 2_000, "CASH");
        deliver(event(eventId(), "charge.dispute.created", dispute("dp_mkcash", cashDisputed, 2_000,
            "needs_response")));
        deliver(event(eventId(), "charge.dispute.closed", dispute("dp_mkcash", cashDisputed, 2_000, "lost")));
        run(KudosExchanges.BUY, sponsorToken(cashSponsor), body("amount", 1_500));
        run(KudosExchanges.SELL, sponsorToken(cashSponsor), body("amount", 500));

        // BANK_TOPUP and CASH_BANK_TOPUP (bank transfers recorded and approved).
        String bankSponsor = newSponsor(unique("s-mk-bank"));
        bankTopUp(bankSponsor, 50_000, "KUDOS", "ST-MK-1");
        bankTopUp(bankSponsor, 20_000, "CASH", "ST-MK-2");

        // MANUAL_TOPUP (money received outside, credited by hand) and GRANT (Kudos given by hand), both approved.
        String manualSponsor = newSponsor(unique("s-mk-manual"));
        approve((String) run(ManualCredits.RECORD, finance(), body("sponsorId", manualSponsor, "kind", "PAID_OUTSIDE",
            "amount", 30_000, "reason", "Paid by cash at the office", "receivedOn", today(), "bankReference",
            "CASH-MK")).get("approvalRequestId"));
        approve((String) run(ManualCredits.RECORD, finance(), body("sponsorId", manualSponsor, "kind", "GRANT",
            "amount", 4_000, "reason", "Launch campaign")).get("approvalRequestId"));

        // REWARD: 100 of marks with a commission of 20.
        String publicationId = livePublication(sponsor, "MK capitals", Map.of("regions", List.of("GLOBAL")));
        assertThat(play(u1(), publicationId, true, true)).containsEntry("rewardStatus", "PAID");

        // TRANSFER_SENT and TRANSFER_SETTLED, then 500 of it reversed by Stripe (TRANSFER_REVERSED).
        String first = payee(unique("t-mk-1"));
        enabledPayee(first);
        earn(first, 2_000);
        runTransfers();
        String sent = (String) transfersOf(first).getLast().get("transfer_id");
        runJobs();
        assertThat(transfer(sent)).containsEntry("status", "SENT");
        deliver(reversed(sent, 500));
        assertThat(money(transfer(sent).get("reversed_amount"))).isEqualByComparingTo("500");

        // TRANSFER_FAILED: a transfer that Stripe refuses, its sending reversed.
        String second = payee(unique("t-mk-2"));
        enabledPayee(second);
        earn(second, 1_500);
        runTransfers();
        String refused = (String) transfersOf(second).getLast().get("transfer_id");
        StripeSim.target = "qb-" + refused;
        StripeSim.REFUSE.set(1);
        runJobs();
        assertThat(transfer(refused)).containsEntry("status", "FAILED");

        // PAYOUT, PAYOUT_FAILED.
        deliver(event(eventId(), "payout.paid", AdminFinanceIT.payout("po_mka", 5_000, "paid")));
        deliver(event(eventId(), "payout.paid", AdminFinanceIT.payout("po_mkb", 1_000, "paid")));
        deliver(event(eventId(), "payout.failed", AdminFinanceIT.payout("po_mkb", 1_000, "failed")));

        // WITHDRAWAL_PAID: a bank withdrawal approved and paid by finance.
        param(QbParams.WITHDRAWAL_BANK_ENABLED, "true");
        param(QbParams.WITHDRAWAL_BANK_FEE, "200");
        param(QbParams.TRANSFER_THRESHOLD, "1000");
        String banked = payee(unique("t-mk-bank"));
        run("QB_BANK_ACCOUNT_SAVE", taker(banked), body("bankCode", "０００１", "bankName", "みずほ銀行",
            "branchCode", "001", "branchName", "東京営業部", "accountType", "ORDINARY", "bankAccountNumber", "7654321",
            "accountHolder", "ﾔﾏﾀﾞ ﾀﾛｳ"));
        run("QB_PAYOUT_METHOD_SET", taker(banked), body("method", "BANK"));
        advance(Duration.ofHours(73));
        earn(banked, 5_000);
        String withdrawalId = (String) run("QB_WITHDRAWAL_REQUEST", taker(banked), body("amount", 3_000))
            .get("transferId");
        runTransfers();
        run("APPROVAL_DECIDE", transferReviewer(), body("requestId", transfer(withdrawalId).get("approval_request_id"),
            "decision", "APPROVE"));
        deliverEvents();
        run("QB_WITHDRAWAL_PAID", tokenOf(user("mk-payer"), QbRoles.ADMIN_FINANCE), body("transferId", withdrawalId,
            "paidOn", today(), "paidReference", "PAY-MK"));

        // The guard.
        String reader = TestTokens.bearer(tokens, "mk-monthly", QbPermissions.ADMIN_FINANCE_READ);
        List<Map<String, Object>> report = items("qb.admin.monthly", reader, Map.of("params",
            Map.of("from", ALL_FROM.toString(), "to", ALL_TO.toString()), "limit", 100));
        MonthlyKinds.assertGuard(report, List.of("TOPUP", "STRIPE_FEE", "REFUND", "DISPUTE", "CASH_TOPUP",
            "CASH_REFUND", "CASH_DISPUTE", "BANK_TOPUP", "CASH_BANK_TOPUP", "MANUAL_TOPUP", "GRANT", "KUDOS_BOUGHT",
            "KUDOS_SOLD", "REWARD", "TRANSFER_SENT", "TRANSFER_SETTLED", "TRANSFER_REVERSED", "TRANSFER_FAILED",
            "WITHDRAWAL_PAID", "PAYOUT", "PAYOUT_FAILED"), query(MonthlyKinds.SOURCES_SQL),
            ((Number) query(MonthlyKinds.NO_SOURCE_SQL).getFirst().get("n")).longValue());
        assertBooksBalance();
    }
}
