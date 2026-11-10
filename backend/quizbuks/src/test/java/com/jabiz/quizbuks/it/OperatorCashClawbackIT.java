package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorTransfers;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.BankTopUps;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M4, the controller's review (M1): a card top-up of the cash account taken back (a refund or a lost dispute) takes
 * its own lot first, then the Sponsor's other cash by arrival, and the cash its operator moved there (source
 * {@code OPERATOR}, which pays the platform's fees only) last. Otherwise a refund could take the operator's money
 * while cash of the Sponsor's own stayed free to buy Kudos. When it has to take the operator's money, headquarters'
 * finance gets a task: that money went back to the card.
 */
class OperatorCashClawbackIT extends TopUpItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static boolean switched;

    @BeforeEach
    void switches() {
        if (!switched) {
            for (Map.Entry<String, String> param : Map.of(QbParams.TOPUP_TARGETS, "KUDOS,CASH",
                QbParams.KUDOS_BUY_ENABLED, "true", QbParams.BANK_RECEIVING_BANK, "Mizuho Bank (0001)",
                QbParams.BANK_RECEIVING_BRANCH, "Head office (100)", QbParams.BANK_RECEIVING_TYPE, "Ordinary",
                QbParams.BANK_RECEIVING_NUMBER, "1234567", QbParams.BANK_RECEIVING_HOLDER, "クイズブックス（カ",
                QbParams.TOPUP_BANK_MIN, "1000", QbParams.TOPUP_BANK_MAX, "1000000").entrySet()) {
                Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity",
                    "SysParam", "values", Map.of("paramKey", param.getKey(), "value", param.getValue()), "reason",
                    "F-M4 review"));
                run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
            }
            switched = true;
        }
    }

    /** The next thing arrives a minute later: the lots' order by arrival is the order of the steps. */
    void later() {
        clock.set(clock.instant().plusSeconds(60));
    }

    /** A card top-up into the cash account, paid; returns its id. */
    String cardTopUp(String sponsor, long amount) {
        String topUpId = (String) run("QB_TOPUP_START", sponsorToken(sponsor), body("amount", amount, "target",
            "CASH")).get("topUpId");
        runJobs();
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        return topUpId;
    }

    /** A bank transfer into the cash account, recorded by finance and approved by a second person. */
    void bankTopUp(String sponsor, long amount) {
        String topUpId = (String) run(BankTopUps.START, sponsorToken(sponsor), body("amount", amount, "target",
            "CASH")).get("topUpId");
        String requestId = (String) run(BankTopUps.RECORD, tokenOf(user("fm4-bank-finance"), QbRoles.ADMIN_FINANCE),
            body("topUpId", topUpId, "amount", amount, "receivedOn", LocalDate.ofInstant(clock.instant(), TOKYO)
                .toString(), "bankReference", "ST-" + topUpId.substring(0, 8))).get("approvalRequestId");
        run("APPROVAL_DECIDE", tokenOf(user("fm4-bank-content"), QbRoles.ADMIN_CONTENT), body("requestId", requestId,
            "decision", "APPROVE"));
        deliverEvents();
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
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

    /** Credits the operator's account as a settlement does (see OperatorTransferIT), then moves it all. */
    void operatorMoves(NewOperator op, long amount) {
        String settlementId = UUID.randomUUID().toString();
        execute("INSERT INTO qb_operator_settlement (settlement_id, operator_id, month, period_start, period_end, "
            + "revenue, platform_share, operator_share, prospecting_cost, carried_in, payable, carried_out, status, "
            + "issued_at, revision, version) VALUES (?, ?, '2026-09', now(), now(), ?, 0.5, ?, 0, 0, ?, 0, "
            + "'CREDITED', now(), 1, 0)", settlementId, op.operatorId(), amount * 2, amount, amount);
        run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), body("bookingTime",
            clock.instant().toString(), "description", "Settlement (test)", "sourceEntity", "QbOperatorSettlement",
            "sourceId", settlementId, "entries", List.of(
                body("accountCode", "5130", "direction", "DEBIT", "amount", amount),
                body("accountCode", "2140", "direction", "CREDIT", "amount", amount))));
        String key = UUID.randomUUID().toString();
        client.post().uri("/api/processes/" + OperatorTransfers.TRANSFER + "/latest")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, memberSession(op.leadId(), op.operatorId()))
            .header("Idempotency-Key", key).bodyValue(body("amount", amount, "requestKey", key))
            .exchange().expectStatus().isOk();
    }

    /** The Sponsor's cash lots in order of arrival, {@code source:remaining}. */
    static List<String> lots(String sponsor) {
        return query("SELECT source, remaining FROM qb_cash_lot WHERE sponsor_id = ? ORDER BY arrived_at, source",
            sponsor).stream().map(row -> row.get("source") + ":"
            + money(row.get("remaining")).stripTrailingZeros().toPlainString()).toList();
    }

    BigDecimal cash(String sponsor) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = 'CASH'",
            sponsor);
        return rows.isEmpty() ? BigDecimal.ZERO : money(rows.getFirst().get("balance"));
    }

    List<Map<String, Object>> differences(String... owners) {
        List<String> them = List.of(owners);
        return items("qb.system.recon-fee-account", TestTokens.bearer(tokens, "fm4-recon", QbPermissions.WALLET_SYSTEM),
            Map.of()).stream().filter(row -> them.contains(String.valueOf(row.get("sponsorId")))).toList();
    }

    @Test
    void aRefundTakesTheSponsorsOwnCashBeforeTheOperators() {
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM4 Refund A"), null);
        String sponsor = op.leadId();
        // A: a card top-up, spent on Kudos; C: the operator's money; B: a bank transfer of the Sponsor's own.
        String card = cardTopUp(sponsor, 5_000);
        later();
        run("QB_KUDOS_BUY", sponsorToken(sponsor), body("amount", 5_000));
        later();
        operatorMoves(op, 3_000);
        later();
        bankTopUp(sponsor, 2_000);
        assertThat(lots(sponsor)).containsExactly("CARD:0", "OPERATOR:3000", "BANK:2000");

        // A refunded in part: its own lot is empty, the bank transfer goes, the operator's money stays.
        Map<String, Object> refund = refund(card, 5_000, 2_000);
        deliver(refund);
        assertThat(lots(sponsor)).containsExactly("CARD:0", "OPERATOR:3000", "BANK:0");
        assertThat(cash(sponsor)).isEqualByComparingTo("3000");
        assertThat(tasksAbout("stripe-operator-cash:")).filteredOn(task -> card.equals(task.get("subject_id")))
            .isEmpty();
        // What is left is the operator's: it does not buy Kudos.
        assertThat(codes(refused("QB_KUDOS_BUY", sponsorToken(sponsor), body("amount", 1), 422))).isNotEmpty();
        assertThat(differences(op.operatorId(), sponsor)).isEmpty();
    }

    @Test
    void onlyWhenNothingElseIsLeftDoesARefundTakeTheOperatorsMoneyAndFinanceIsTold() {
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM4 Refund B"), null);
        String sponsor = op.leadId();
        String card = cardTopUp(sponsor, 1_000);
        later();
        run("QB_KUDOS_BUY", sponsorToken(sponsor), body("amount", 1_000));
        later();
        operatorMoves(op, 3_000);

        Map<String, Object> refund = refund(card, 1_000, 1_000);
        deliver(refund);
        assertThat(lots(sponsor)).containsExactly("CARD:0", "OPERATOR:2000");
        assertThat(cash(sponsor)).isEqualByComparingTo("2000");
        assertThat(tasksAbout("stripe-operator-cash:" + refund.get("id"))).singleElement().satisfies(task -> {
            assertThat(task).containsEntry("title_key", "task.qb.refund-check.OPERATOR_CASH")
                .containsEntry("subject_id", card);
            assertThat(String.valueOf(task.get("title_params"))).contains("1000");
        });
        assertThat(differences(op.operatorId(), sponsor)).isEmpty();
    }
}
