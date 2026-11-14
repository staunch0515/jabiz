package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorTransfers;
import com.jabiz.quizbuks.operator.OperatorWithdrawals;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.WalletCodes;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M4 (docs/quizbuks/plans/impl/F-M.md §7): an operator's lead moves money of the operator's account (2140) to its
 * direct Sponsor's account that pays the platform's fees (2160, a cash lot of the source OPERATOR): up to the balance,
 * the lead only, never to a banned Sponsor, once per idempotency key; the money pays the platform's fees after the
 * granted credit and is never turned into Kudos; the posting, the monthly report and the daily check agree.
 */
class OperatorTransferIT extends PlayItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static boolean switched;

    @BeforeEach
    void switches() {
        if (!switched) {
            param(QbParams.FEE_MONTHLY, "1000");
            param(QbParams.KUDOS_SELL_ENABLED, "true");
            param(QbParams.KUDOS_BUY_ENABLED, "true");
            param(QbParams.OPERATOR_WITHDRAWAL_MIN, "1");
            param(QbParams.OPERATOR_WITHDRAWAL_COOLDOWN_HOURS, "0");
            switched = true;
        }
    }

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "F-M4 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    String finance() {
        return tokenOf(user("fm4-finance"), QbRoles.ADMIN_FINANCE);
    }

    /**
     * Credits the operator's account as a month's settlement does: a CREDITED settlement and its posting (debit 5130 /
     * credit 2140, the settlement its source).
     */
    void creditOperator(String operatorId, long amount, String month) {
        String settlementId = UUID.randomUUID().toString();
        execute("INSERT INTO qb_operator_settlement (settlement_id, operator_id, month, period_start, period_end, "
            + "revenue, platform_share, operator_share, prospecting_cost, carried_in, payable, carried_out, status, "
            + "issued_at, revision, version) VALUES (?, ?, ?, now(), now(), ?, 0.5, ?, 0, 0, ?, 0, 'CREDITED', now(), "
            + "1, 0)", settlementId, operatorId, month, amount * 2, amount, amount);
        run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), body("bookingTime",
            clock.instant().toString(), "description", "Settlement (test)", "sourceEntity", "QbOperatorSettlement",
            "sourceId", settlementId, "entries", List.of(
                body("accountCode", "5130", "direction", "DEBIT", "amount", amount),
                body("accountCode", "2140", "direction", "CREDIT", "amount", amount))));
    }

    /** A transfer's input with a fresh request key. */
    static Map<String, Object> keyed(Object... pairs) {
        Map<String, Object> input = new java.util.LinkedHashMap<>(body(pairs));
        input.put("requestKey", UUID.randomUUID().toString());
        return input;
    }

    /** A transfer whose request key is its Idempotency-Key, as the operator's page sends it. */
    Map<String, Object> transfer(String session, long amount, String key) {
        Map<String, Object> answer = client.post().uri("/api/processes/" + OperatorTransfers.TRANSFER + "/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, session)
            .header("Idempotency-Key", key).bodyValue(body("amount", amount, "note", "For our fees", "requestKey", key))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) answer.get("output");
        return output;
    }

    static List<Map<String, Object>> transfers(String operatorId) {
        return query("SELECT * FROM qb_operator_transfer_version WHERE operator_id = ?", operatorId);
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

    BigDecimal balance(String owner, String kind) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?", owner,
            kind);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    static List<Map<String, Object>> lots(String sponsor) {
        return query("SELECT * FROM qb_cash_lot WHERE sponsor_id = ? ORDER BY arrived_at, lot_id", sponsor);
    }

    /** What the daily check finds about the operator or its Sponsor (none: they agree). */
    List<Map<String, Object>> differences(String... owners) {
        List<String> them = List.of(owners);
        return feeAccountItems(TestTokens.bearer(tokens, "fm4-recon", QbPermissions.WALLET_SYSTEM),
            Map.of()).stream().filter(row -> them.contains(String.valueOf(row.get("sponsorId")))).toList();
    }

    @Test
    void theLeadMovesUpToTheBalanceToItsDirectSponsorOncePerKey() {
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM4 Kanto"), null);
        creditOperator(op.operatorId(), 5_000, "2026-09");
        String lead = memberSession(op.leadId(), op.operatorId());

        // A member who is not the lead cannot; headquarters is no operator.
        String staff = verifiedUser(unique("fm4-staff"));
        run(com.jabiz.quizbuks.operator.OperatorProcesses.MEMBER_ADD, operatorsAdmin(), body("operatorId",
            op.operatorId(), "userId", staff, "lead", false));
        assertThat(codes(refused(OperatorTransfers.TRANSFER, memberSession(staff, op.operatorId()),
            keyed("amount", 100), 422))).containsExactly(OperatorCodes.NOT_LEAD);
        refused(OperatorTransfers.TRANSFER, operatorsAdmin(), keyed("amount", 100), 403);
        // Another operator's lead cannot reach this operator's account.
        NewOperator other = newOperatorWithLead(operatorsAdmin(), unique("FM4 Other"), null);
        assertThat(codes(refused(OperatorTransfers.TRANSFER, memberSession(other.leadId(), op.operatorId()),
            keyed("amount", 100), 422))).containsExactly(OperatorCodes.NOT_MEMBER);
        // More than the balance, or not whole yen: refused, nothing written.
        assertThat(codes(refused(OperatorTransfers.TRANSFER, lead, keyed("amount", 5_001), 422)))
            .containsExactly(OperatorCodes.TRANSFER_AMOUNT);
        assertThat(codes(refused(OperatorTransfers.TRANSFER, lead, keyed("amount", new BigDecimal("10.5")), 422)))
            .containsExactly(OperatorCodes.TRANSFER_AMOUNT);
        assertThat(transfers(op.operatorId())).isEmpty();

        String key = UUID.randomUUID().toString();
        Map<String, Object> done = transfer(lead, 3_000, key);
        assertThat(new BigDecimal(String.valueOf(done.get("balance")))).isEqualByComparingTo("2000");
        // The same key again: the same answer, one transfer.
        assertThat(transfer(lead, 3_000, key)).containsEntry("transferId", done.get("transferId"));
        assertThat(transfers(op.operatorId())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("sponsor_id", op.leadId()).containsEntry("note", "For our fees")
                .containsEntry("requested_by", op.leadId());
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("3000");
        });
        String transferId = String.valueOf(done.get("transferId"));
        assertThat(lines(OperatorTransfers.TRANSFER_ENTITY_NAME, transferId))
            .containsExactly("2140 DEBIT 3000", "2160 CREDIT 3000 @" + op.leadId());
        assertThat(balance(op.leadId(), QbWallets.CASH)).isEqualByComparingTo("3000");
        assertThat(lots(op.leadId())).singleElement().satisfies(lot -> {
            assertThat(lot).containsEntry("source", "OPERATOR").containsEntry("source_entity",
                OperatorTransfers.TRANSFER_ENTITY_NAME).containsEntry("source_id", transferId);
            assertThat((BigDecimal) lot.get("remaining")).isEqualByComparingTo("3000");
        });

        // The operator reads its account and its transfers; the withdrawals' balance is the same.
        assertThat(items("qb.operator.account", lead, Map.of())).singleElement().satisfies(row -> {
            assertThat(new BigDecimal(String.valueOf(row.get("transferred")))).isEqualByComparingTo("3000");
            assertThat(new BigDecimal(String.valueOf(row.get("balance")))).isEqualByComparingTo("2000");
            assertThat(row).containsEntry("hasDirectSponsor", true);
        });
        assertThat(items("qb.operator.transfers", lead, Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("transferId", transferId));
        assertThat(items("qb.operator.transfers", memberSession(other.leadId(), other.operatorId()), Map.of()))
            .isEmpty();
        assertThat(items("qb.system.operator-balance", TestTokens.bearer(tokens, "fm4-balance",
            QbPermissions.OPERATOR_SETTLEMENT_SYSTEM), Map.of("params", Map.of("operatorId", op.operatorId()))))
            .singleElement().satisfies(row ->
                assertThat(new BigDecimal(String.valueOf(row.get("balance")))).isEqualByComparingTo("2000"));
        // The direct Sponsor sees it on its account.
        assertThat(items("qb.sponsor.wallet", sponsorToken(op.leadId()), Map.of()).getFirst())
            .containsEntry("cashBalance", 3000).containsEntry("cashFromOperator", 3000);
        // The rest, and no more.
        transfer(lead, 2_000, UUID.randomUUID().toString());
        assertThat(codes(refused(OperatorTransfers.TRANSFER, lead, keyed("amount", 1), 422)))
            .containsExactly(OperatorCodes.TRANSFER_AMOUNT);

        assertThat(differences(op.operatorId(), op.leadId())).isEmpty();
        // The monthly report: a kind of its own.
        java.time.YearMonth month = java.time.YearMonth.from(clock.instant().atZone(java.time.ZoneOffset.UTC));
        Map<String, Object> moved = items("qb.admin.monthly", finance(), Map.of("params", Map.of(
                "from", month.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString(),
                "to", month.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString()),
            "limit", 100)).stream().filter(row -> "OPERATOR_TRANSFER_TO_DIRECT".equals(row.get("category")))
            .findFirst().orElseThrow();
        assertThat(new BigDecimal(String.valueOf(moved.get("operatorPayable")))).isGreaterThanOrEqualTo(
            new BigDecimal("5000"));
        assertThat(new BigDecimal(String.valueOf(moved.get("sponsorCash")))).isLessThanOrEqualTo(
            new BigDecimal("-5000"));
    }

    @Test
    void nothingGoesToABannedDirectSponsor() {
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM4 Banned"), null);
        creditOperator(op.operatorId(), 1_000, "2026-09");
        String boss = userWithRole(unique("fm4-boss"), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, QbRoles.ADMIN_SUPER), body("userId", op.leadId(), "reason", "Fraud"));
        // The first lead is the direct Sponsor: banned, its own ban stops it first.
        assertThat(codes(refused(OperatorTransfers.TRANSFER, memberSession(op.leadId(), op.operatorId()),
            keyed("amount", 500), 422))).containsExactly("QB_USER_BANNED");
        // Another lead of the operator: the direct Sponsor is banned, nothing goes to it.
        String second = verifiedUser(unique("fm4-second-lead"));
        execute("INSERT INTO qb_operator_member (member_id, operator_id, user_id, lead, operator_active, added_at, "
            + "added_by, version) VALUES (gen_random_uuid()::text, ?, ?::uuid, true, true, now(), 'it', 0)",
            op.operatorId(), second);
        assertThat(codes(refused(OperatorTransfers.TRANSFER, memberSession(second, op.operatorId()),
            keyed("amount", 500), 422))).containsExactly(OperatorCodes.TRANSFER_DIRECT_BANNED);
        assertThat(transfers(op.operatorId())).isEmpty();
        assertThat(balance(op.leadId(), QbWallets.CASH)).isEqualByComparingTo("0");
    }

    @Test
    void theMovedMoneyPaysFeesAfterTheCreditButNeverBuysKudos() {
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM4 Fees"), null);
        String sponsor = op.leadId();
        creditOperator(op.operatorId(), 3_000, "2026-09");
        // Cash of its own first (Kudos credited and sold back: a lot of OTHER), then the operator's.
        credit(sponsor, 1_000);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", 1_000));
        transfer(memberSession(sponsor, op.operatorId()), 3_000, UUID.randomUUID().toString());
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("4000");

        // Buying Kudos takes its own cash only.
        assertThat(codes(refused(KudosExchanges.BUY, sponsorToken(sponsor), body("amount", 1_001), 422)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_FUNDS);
        run(KudosExchanges.BUY, sponsorToken(sponsor), body("amount", 1_000));
        assertThat(lots(sponsor)).extracting(lot -> lot.get("source") + ":"
                + ((BigDecimal) lot.get("remaining")).stripTrailingZeros().toPlainString())
            .containsExactlyInAnyOrder("OTHER:0", "OPERATOR:3000");
        assertThat(codes(refused(KudosExchanges.BUY, sponsorToken(sponsor), body("amount", 1), 422)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_FUNDS);
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("cashBalance", 3000).containsEntry("cashFromOperator", 3000);

        // The month's platform fee (1,000 and its tax): paid from the operator's money.
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeProcesses.MONTHLY_ITEM, body("sponsorId", sponsor,
            "period", FeeRules.period(clock.instant(), TOKYO)))).outcome()).isEqualTo("CHARGED");
        assertThat(query("SELECT sub_account, amount FROM qb_fee_payment_version WHERE sponsor_id = ?", sponsor))
            .singleElement().satisfies(part -> {
                assertThat(part).containsEntry("sub_account", "CASH");
                assertThat((BigDecimal) part.get("amount")).isEqualByComparingTo("1100");
            });
        assertThat(lots(sponsor)).extracting(lot -> lot.get("source") + ":"
                + ((BigDecimal) lot.get("remaining")).stripTrailingZeros().toPlainString())
            .containsExactlyInAnyOrder("OTHER:0", "OPERATOR:1900");
        assertThat(differences(op.operatorId(), sponsor)).isEmpty();
    }

    @Test
    void twoTransfersAtOnceNeverTakeMoreThanTheBalance() throws Exception {
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM4 Race"), null);
        creditOperator(op.operatorId(), 5_000, "2026-09");
        String lead = memberSession(op.leadId(), op.operatorId());
        // One call first: the platform readies its input conversion once (a lock BlockHound would flag if two
        // requests did it at the same instant), as every process's first call does.
        assertThat(status(lead, 6_000)).isEqualTo(422);
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> status(lead, 3_000));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> status(lead, 3_000));
        assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 422);
        assertThat(transfers(op.operatorId())).hasSize(1);
        assertThat(balance(op.leadId(), QbWallets.CASH)).isEqualByComparingTo("3000");
        assertThat(differences(op.operatorId(), op.leadId())).isEmpty();
    }

    private int status(String session, long amount) {
        return client.post().uri("/api/processes/" + OperatorTransfers.TRANSFER + "/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, session)
            .header("Idempotency-Key", UUID.randomUUID().toString()).bodyValue(keyed("amount", amount))
            .exchange().returnResult(String.class).getStatus().value();
    }

    private int withdrawalStatus(String session, long amount) {
        return client.post().uri("/api/processes/" + OperatorWithdrawals.REQUEST + "/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, session)
            .header("Idempotency-Key", UUID.randomUUID().toString()).bodyValue(body("amount", amount))
            .exchange().returnResult(String.class).getStatus().value();
    }

    private static Map<String, Object> bankAccount() {
        return body("bankCode", "0005", "bankName", "MUFG", "branchCode", "001", "branchName", "Head office",
            "accountType", "ORDINARY", "accountNumber", "1234567", "accountHolder", "カ）カントウ");
    }

    @Test
    void aRequestedWithdrawalAndATransferShareTheBalance() {
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM4 Share"), null);
        creditOperator(op.operatorId(), 5_000, "2026-09");
        String lead = memberSession(op.leadId(), op.operatorId());
        run(OperatorWithdrawals.BANK_ACCOUNT_SAVE, lead, bankAccount());
        // A transfer lowers what can be withdrawn.
        transfer(lead, 2_000, UUID.randomUUID().toString());
        assertThat(codes(refused(OperatorWithdrawals.REQUEST, lead, body("amount", 3_001), 422)))
            .containsExactly(OperatorCodes.WITHDRAWAL_AMOUNT);
        // A withdrawal still REQUESTED (not paid yet) lowers what can be transferred.
        assertThat(run(OperatorWithdrawals.REQUEST, lead, body("amount", 2_500)))
            .containsEntry("status", OperatorWithdrawals.REQUESTED);
        assertThat(codes(refused(OperatorTransfers.TRANSFER, lead, keyed("amount", 501), 422)))
            .containsExactly(OperatorCodes.TRANSFER_AMOUNT);
        transfer(lead, 500, UUID.randomUUID().toString());
        assertThat(codes(refused(OperatorTransfers.TRANSFER, lead, keyed("amount", 1), 422)))
            .containsExactly(OperatorCodes.TRANSFER_AMOUNT);
        assertThat(transfers(op.operatorId())).hasSize(2);
        assertThat(differences(op.operatorId(), op.leadId())).isEmpty();
    }

    @Test
    void aTransferAndAWithdrawalAtOnceNeverTakeMoreThanTheBalance() throws Exception {
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM4 Race W"), null);
        creditOperator(op.operatorId(), 5_000, "2026-09");
        String lead = memberSession(op.leadId(), op.operatorId());
        run(OperatorWithdrawals.BANK_ACCOUNT_SAVE, lead, bankAccount());
        // One call of each first (see twoTransfersAtOnceNeverTakeMoreThanTheBalance).
        assertThat(status(lead, 6_000)).isEqualTo(422);
        assertThat(withdrawalStatus(lead, 6_000)).isEqualTo(422);
        CompletableFuture<Integer> moved = CompletableFuture.supplyAsync(() -> status(lead, 3_000));
        CompletableFuture<Integer> withdrawn = CompletableFuture.supplyAsync(() -> withdrawalStatus(lead, 3_000));
        assertThat(List.of(moved.get(), withdrawn.get())).containsExactlyInAnyOrder(200, 422);
        BigDecimal transferred = transfers(op.operatorId()).stream().map(row -> (BigDecimal) row.get("amount"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal requested = query("SELECT amount FROM qb_operator_withdrawal WHERE operator_id = ?",
            op.operatorId()).stream().map(row -> (BigDecimal) row.get("amount")).reduce(BigDecimal.ZERO,
            BigDecimal::add);
        assertThat(transferred.add(requested)).isEqualByComparingTo("3000");
        assertThat(differences(op.operatorId(), op.leadId())).isEmpty();
    }

    @Test
    void everyTransferNeedsARecentSecondFactorAndAKeyUsedOnce() {
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM4 Keys"), null);
        creditOperator(op.operatorId(), 1_000, "2026-09");
        // Without a recent second factor: refused before anything runs.
        com.jabiz.runtime.context.Actor noMfa = new com.jabiz.runtime.context.Actor(op.leadId(), null,
            java.util.Set.of(QbRoles.OPERATOR), java.util.Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()),
            null, null, com.jabiz.quizbuks.operator.QbOperators.ENTRY, true)
            .inScope(new com.jabiz.context.EntryScope(com.jabiz.quizbuks.operator.QbOperators.SCOPE,
                op.operatorId()));
        assertThat(String.valueOf(refused(OperatorTransfers.TRANSFER, "Bearer " + tokens.issue(noMfa).token(),
            keyed("amount", 100), 403))).contains("MFA_REQUIRED");
        String lead = memberSession(op.leadId(), op.operatorId());
        // Without a request key: a validation error.
        assertThat(String.valueOf(refused(OperatorTransfers.TRANSFER, lead, body("amount", 100), 400)))
            .contains("requestKey");
        assertThat(transfers(op.operatorId())).isEmpty();
        // The same request key under another Idempotency-Key (the submission sent again, not replayed): refused.
        String key = UUID.randomUUID().toString();
        transfer(lead, 100, key);
        assertThat(codes(refused(OperatorTransfers.TRANSFER, lead, body("amount", 100, "requestKey", key), 422)))
            .containsExactly(OperatorCodes.TRANSFER_REPEATED);
        assertThat(transfers(op.operatorId())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("request_key", key));
        // Another operator may happen to use the same key.
        NewOperator other = newOperatorWithLead(operatorsAdmin(), unique("FM4 Keys B"), null);
        creditOperator(other.operatorId(), 1_000, "2026-09");
        transfer(memberSession(other.leadId(), other.operatorId()), 100, key);
        assertThat(transfers(other.operatorId())).hasSize(1);
    }

    @Test
    void theCheckFindsATransferWithoutItsPosting() {
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM4 Check"), null);
        creditOperator(op.operatorId(), 1_000, "2026-09");
        assertThat(differences(op.operatorId())).isEmpty();
        // A transfer the process would never write: recorded without its posting.
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT ?::uuid, ?, "
            + "max(process_seq_id) FROM op_process", id, OperatorTransfers.TRANSFER_ENTITY_NAME);
        Timestamp at = Timestamp.from(clock.instant().minusSeconds(60));
        execute("INSERT INTO qb_operator_transfer_version (transfer_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, operator_id, sponsor_id, amount, request_key, requested_by, transferred_at) SELECT "
            + "?::uuid, 1, ?, ?, max(process_seq_id), ?, ?, 400, 'it-key', 'it', ? FROM op_process", id, at, at, op.operatorId(), op.leadId(),
            at);
        assertThat(differences(op.operatorId())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("checkCode", "OPERATOR_ACCOUNT");
            assertThat(new BigDecimal(String.valueOf(row.get("expected")))).isEqualByComparingTo("600");
            assertThat(new BigDecimal(String.valueOf(row.get("actual")))).isEqualByComparingTo("1000");
        });
    }

    /** F-M13b: money moved by the operator's lead pays the Sponsor's unpaid invoice by itself. */
    @Test
    void theMovedMoneyPaysAnUnpaidInvoiceByItself() {
        autoPayOn();
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM13b Auto"), null);
        String sponsor = op.leadId();
        String invoiceId = unpaidInvoice(sponsor, 1100);
        creditOperator(op.operatorId(), 3_000, "2026-09");
        transfer(memberSession(sponsor, op.operatorId()), 3_000, UUID.randomUUID().toString());
        deliverEvents();
        assertThat(invoiceStatus(invoiceId)).isEqualTo("PAID");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("1900");
        assertThat(differences(op.operatorId(), sponsor)).isEmpty();
    }
}
