package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.AudienceRefundJob;
import com.jabiz.quizbuks.fee.DistributionPrices;
import com.jabiz.quizbuks.fee.DistributionSettlements;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.operator.OperatorTransfers;
import com.jabiz.quizbuks.operator.OperatorWithdrawals;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14 G13 (docs/quizbuks/plans/impl/F-M14.md §3.2 point 4), the fee side: one of each kind of platform-fee posting,
 * driven through the processes in one linear timeline (the clock only moves forward) in the schema of this class, then
 * the guard of {@link MonthlyKinds}: rows only in the table's categories, every source in the table, and
 * {@code OTHER} holding only the transactions that have no source. The kinds that need Stripe's stub are in
 * {@link MonthlyKindsIT} (the two cannot share a class).
 *
 * <p>Driven here: FEE_CHARGED, FEE_PAID (from the cash account, and the part of a credit note that goes back to it),
 * FEE_VOIDED (a paid invoice voided), FEE_CREDIT_NOTE, KUDOS_SOLD and KUDOS_BOUGHT (the cash to pay the fees),
 * PREPAYMENT_FROZEN, PREPAYMENT_USED and PREPAYMENT_RELEASED (an audience fee set aside, paid from, and given back
 * after the end), FEE_CREDIT_GRANTED and FEE_CREDIT_VOIDED (a lot granted and expired unused), ENTRY_FEE and
 * ENTRY_FEE_SETTLED (a taker pays a quiz's entry fee, the daily job settles it into the sponsor's cash),
 * DISTRIBUTION_DEPOSIT, DISTRIBUTION_CHARGED and DISTRIBUTION_RELEASED (a publication's distribution fee), REWARD
 * (a commission), OPERATOR_SHARE (an operator's month settled on its sponsor's platform fee), OPERATOR_TRANSFER_TO_DIRECT
 * (its lead moves part of it to their cash) and OPERATOR_PAID (a withdrawal paid).
 *
 * <p>Not driven (too costly for a guard; each has its own row-by-row tests): REFERRAL_REWARD ({@code ReferralIT}: needs
 * a tree prerequisite, a claim and a pass) and FEE_CREDIT_RECOVERED ({@code OperatorSettlementGiftIT}: granted credit
 * used, then taken back from the operator's settlement). Their sources are in the table and their rows in the report.
 */
class MonthlyKindsFeeIT extends CreditNoteItSupport {

    @Autowired
    @Qualifier("qbFeeCreditExpireJob")
    JobDefinition<FeeCredits.GrantRef> expireJob;

    @Autowired
    @Qualifier("qbEntryFeeSettleJob")
    JobDefinition<?> settleJob;

    @Autowired
    JobDefinition<DistributionSettlements.SettleRef> qbDistributionMonthlyJob;

    @Autowired
    JobDefinition<DistributionSettlements.SettleRef> qbDistributionFinalJob;

    String hq() {
        return TestTokens.bearer(tokens, "mkf-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    JobRunner.JobRun runJob(JobDefinition<?> job, Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(job, at);
    }

    static String operatorOf(String sponsor) {
        return String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?", sponsor)
            .getFirst().get("operator_id"));
    }

    static Map<String, Object> bankAccount() {
        return body("bankCode", "0005", "bankName", "MUFG", "branchCode", "001", "branchName", "Head office",
            "accountType", "ORDINARY", "accountNumber", "1234567", "accountHolder", "カ）カントウ");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyKindOfFeePostingHereIsInItsCategoryAndOtherHoldsOnlyWhatHasNoSource() {
        param(QbParams.FEE_MONTHLY, "1000");
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "true");
        param(QbParams.KUDOS_BUY_ENABLED, "true");
        param(QbParams.ENTRY_FEE_ENABLED, "true");
        param(QbParams.TRANSFER_HOLD_DAYS, "7");
        param(QbParams.DISTRIBUTION_BILLING_ENABLED, "true");
        param(QbParams.OPERATOR_WITHDRAWAL_MIN, "1");
        param(QbParams.OPERATOR_WITHDRAWAL_COOLDOWN_HOURS, "0");
        autoApprove(true);
        feeRate("0.2");
        clock.set(Instant.parse("2031-05-03T01:00:00Z"));

        // FEE_CHARGED and FEE_PAID (the cash account pays: KUDOS_SOLD to have it); a credit note on it: FEE_CREDIT_NOTE
        // and what it gives back, FEE_PAID negative.
        String paid = newSponsor(unique("s-mkf-paid"));
        cash(paid, 5_000);
        assertThat(monthly(paid)).isEqualTo("CHARGED");
        creditNote(monthlyCharge(paid).get("charge_id"), 400);
        run(KudosExchanges.BUY, sponsorToken(paid), body("amount", 300));
        // FEE_VOIDED: a paid invoice voided.
        String voided = newSponsor(unique("s-mkf-voided"));
        cash(voided, 5_000);
        assertThat(monthly(voided)).isEqualTo("CHARGED");
        voidInvoice(invoiceOf(monthlyCharge(voided).get("charge_id")).get("invoice_id"));
        // A postpaid fee, owed: FEE_CHARGED alone.
        String postpaid = newSponsor(unique("s-mkf-postpaid"));
        billing(postpaid, "POSTPAID", 50_000);
        assertThat(monthly(postpaid)).isEqualTo("CHARGED");

        // PREPAYMENT_FROZEN, PREPAYMENT_USED, PREPAYMENT_RELEASED: an audience fee set aside, paid from, and the rest
        // given back after the end.
        String audience = pricedSponsor("mkf-audience");
        cash(audience, 1_000);
        credit(audience, 10_000);
        String publicationId = oneWinnerAudienceDraft(audience, "MKF audience");
        submit(sponsorToken(audience), publicationId);
        goLive(publicationId);
        clock.set(clock.instant().plus(Duration.ofDays(2)));
        play(womanTaker("t-mkf-audience"), publicationId, true, true);
        Map<String, Object> ref = body("prepaymentId", String.valueOf(prepayments(publicationId).getFirst()
            .get("prepayment_id")), "publicationId", publicationId, "sponsorId", audience, "endedAt",
            clock.instant().toString());
        assertThat(((FeeProcesses.ItemOutput) systemRun(AudienceRefundJob.ITEM, ref)).outcome()).isEqualTo("REFUNDED");

        // FEE_CREDIT_GRANTED and FEE_CREDIT_VOIDED: a lot granted that expires unused.
        String granted = newSponsor(unique("s-mkf-grant"));
        grant(granted, 300, clock.instant().plus(Duration.ofHours(1)));
        clock.set(clock.instant().plus(Duration.ofHours(2)));
        runJob(expireJob, clock.instant());
        assertThat(query("SELECT voided_amount FROM qb_fee_credit_grant WHERE sponsor_id = ?", granted).getFirst()
            .get("voided_amount")).isEqualTo(new java.math.BigDecimal("300"));

        // ENTRY_FEE and ENTRY_FEE_SETTLED: a taker pays a quiz's fee of 30 from a reward past the hold, the job settles it.
        String feeSponsor = newSponsor(unique("s-mkf-entry"));
        credit(feeSponsor, 10_000);
        String feeToken = sponsorToken(feeSponsor);
        Instant now = clock.instant();
        Map<String, Object> save = complete(version(feeToken, "MKF fee quiz"), "MKF fee quiz", now,
            now.plus(Duration.ofDays(60)));
        save.putAll(Map.of("maxWinners", 1000, "regions", List.of("GLOBAL"), "entryFee", 30));
        String feeQuiz = (String) run("QB_PUBLICATION_SAVE", feeToken, save).get("publicationId");
        assertThat(run("QB_PUBLICATION_SUBMIT", feeToken, body("publicationId", feeQuiz)).get("status"))
            .isEqualTo("LIVE");
        String earner = newTaker(unique("t-mkf-earner"), null);
        String rewarding = livePublication(feeSponsor, "MKF rewarding", Map.of("regions", List.of("GLOBAL"), "endAt",
            now.plus(Duration.ofDays(60)).toString()));
        assertThat(play(earner, rewarding, true, true)).containsEntry("rewardStatus", "PAID");
        deliverEvents();
        clock.set(clock.instant().plus(Duration.ofDays(8)));
        run("QB_ATTEMPT_START", taker(earner), body("publicationId", feeQuiz));
        assertThat(runJob(settleJob, clock.instant()).items().failed()).isZero();

        // DISTRIBUTION_DEPOSIT, DISTRIBUTION_CHARGED, DISTRIBUTION_RELEASED: the distribution fee of a publication.
        String distribution = pricedSponsor("mkf-distribution");
        run(DistributionPrices.SET, TestTokens.bearer(tokens, "mkf-fee-manage", QbPermissions.FEE_MANAGE),
            body("operatorId", operatorOf(distribution), "rate", "0.05", "unitFloor", "1", "unitCeiling", "20",
                "minimumDeposit", 500, "reason", "F-M14 test"));
        credit(distribution, 5_000);
        cash(distribution, 1_000);
        String distributed = livePublication(distribution, "MKF distribution",
            Map.of("maxWinners", 30, "regions", List.of("GLOBAL")));
        for (int i = 0; i < 3; i++) {
            start(taker(newTaker(unique("t-mkf-dist-" + i), null)), distributed);
        }
        YearMonth next = YearMonth.parse(FeeRules.period(clock.instant(), TOKYO)).plusMonths(1);
        Instant monthlyAt = next.atDay(2).atTime(0, 37).atZone(TOKYO).toInstant();
        clock.set(monthlyAt);
        assertThat(runJob(qbDistributionMonthlyJob, monthlyAt).outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "mkf-content-admin",
            QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", distributed, "reason", "F-M14 test"));
        Instant endedAt = clock.instant();
        clock.set(endedAt.plus(Duration.ofHours(2)));
        assertThat(runJob(qbDistributionFinalJob, clock.instant()).outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);

        // OPERATOR_SHARE: an operator's month settled; OPERATOR_TRANSFER_TO_DIRECT and OPERATOR_PAID out of its account.
        NewOperator operator = newOperatorWithLead(operatorsAdmin(), unique("MKF Operator"), null);
        String operatorId = operator.operatorId();
        String operatorsLead = operator.leadId();
        String operatorSponsor = newSponsor(unique("s-mkf-op"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", operatorSponsor, "operatorId", operatorId, "reason",
            "F-M14 test"));
        // F-M12: the share's base is the platform fees of the operator's sponsors: one by hand (four eyes).
        Map<String, Object> request = run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_MANUAL,
            "sponsorId", operatorSponsor, "amount", 200, "reason", "F-M14 test"));
        run("APPROVAL_DECIDE", content(), body("requestId", request.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        String month = FeeRules.period(clock.instant(), TOKYO);
        clock.set(YearMonth.parse(month).plusMonths(1).atDay(2).atStartOfDay(TOKYO).toInstant());
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", month));
        assertThat(query("SELECT payable FROM qb_operator_settlement WHERE operator_id = ?", operatorId))
            .singleElement().satisfies(row -> assertThat(new java.math.BigDecimal(String.valueOf(row.get("payable"))))
                .isPositive());
        String lead = memberSession(operatorsLead, operatorId);
        run(OperatorWithdrawals.BANK_ACCOUNT_SAVE, lead, bankAccount());
        String key = UUID.randomUUID().toString();
        client.post().uri("/api/processes/" + OperatorTransfers.TRANSFER + "/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, lead)
            .header("Idempotency-Key", key).bodyValue(body("amount", 5, "note", "For our fees", "requestKey", key))
            .exchange().expectStatus().isOk();
        String withdrawalId = String.valueOf(run(OperatorWithdrawals.REQUEST, lead, body("amount", 3))
            .get("withdrawalId"));
        Map<String, Object> paying = run(OperatorWithdrawals.PAY, finance(), body("withdrawalId", withdrawalId,
            "paidOn", java.time.LocalDate.ofInstant(clock.instant(), TOKYO).toString(), "bankReference", "OP-MKF"));
        run("APPROVAL_DECIDE", content(), body("requestId", paying.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();

        // The guard.
        String reader = TestTokens.bearer(tokens, "mkf-monthly", QbPermissions.ADMIN_FINANCE_READ);
        List<Map<String, Object>> report = items("qb.admin.monthly", reader, Map.of("params",
            Map.of("from", "2000-01-01T00:00:00Z", "to", "2100-01-01T00:00:00Z"), "limit", 100));
        MonthlyKinds.assertGuard(report, List.of("REWARD", "KUDOS_SOLD", "KUDOS_BOUGHT", "FEE_CHARGED", "FEE_PAID",
            "FEE_VOIDED", "FEE_CREDIT_NOTE", "PREPAYMENT_FROZEN", "PREPAYMENT_USED", "PREPAYMENT_RELEASED",
            "FEE_CREDIT_GRANTED", "FEE_CREDIT_VOIDED", "ENTRY_FEE", "ENTRY_FEE_SETTLED", "DISTRIBUTION_DEPOSIT",
            "DISTRIBUTION_CHARGED", "DISTRIBUTION_RELEASED", "OPERATOR_SHARE", "OPERATOR_TRANSFER_TO_DIRECT",
            "OPERATOR_PAID"), query(MonthlyKinds.SOURCES_SQL),
            ((Number) query(MonthlyKinds.NO_SOURCE_SQL).getFirst().get("n")).longValue());
        assertBooksBalance();
    }
}
