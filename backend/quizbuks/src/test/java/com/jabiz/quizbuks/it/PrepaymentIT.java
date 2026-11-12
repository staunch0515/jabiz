package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.audience.AudiencePricingProcesses;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.fee.Prepayments;
import com.jabiz.quizbuks.publishing.PublicationPrepayments;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M5 (docs/quizbuks/plans/impl/F-M.md §8): one funds check of a publication's submission against the whole account
 * that pays the platform's fees, the fee set aside in 2180 (today the audience rules' fee, F-M7), charged from it when
 * the publication goes on, given back where it came from when the publication goes back to its draft (withdrawn,
 * rejected, its review expired) or a day after it ends; a shortfall writes nothing; a postpaid Sponsor's credit limit
 * counts what they owe, invoiced or not, and what their other submissions set aside; the daily check agrees.
 */
class PrepaymentIT extends PlayItSupport {

    @Autowired
    JobDefinition<Prepayments.ReleaseRef> qbPrepaymentReleaseJob;

    /** 100 a day for gender over a seven-day run: 700, and 10% tax: 770 set aside. */
    static final BigDecimal SET_ASIDE = new BigDecimal("770");
    private static boolean switched;

    @BeforeEach
    void switches() {
        if (!switched) {
            param(QbParams.AUDIENCE_BILLING_ENABLED, "true");
            param(QbParams.KUDOS_SELL_ENABLED, "true");
            switched = true;
        }
    }

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "F-M5 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    /** A Sponsor of a new operator of its own that prices gender at 100 a day (headquarters set its list). */
    String pricedSponsor(String name) {
        String sponsor = newSponsor(unique(name));
        String operator = newOperator(TestTokens.bearer(tokens, "fm5-hq", QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ), unique(name + " operator"));
        run(com.jabiz.quizbuks.operator.OperatorProcesses.ASSIGN, TestTokens.bearer(tokens, "fm5-hq",
            QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ), body("userId", sponsor, "operatorId",
            operator, "reason", "F-M5"));
        price(operator);
        return sponsor;
    }

    /**
     * A Sponsor of the default operator (whose members review its Sponsors' publications in these tests), which prices
     * gender at 100 a day.
     */
    String reviewedSponsor(String name) {
        String sponsor = newSponsor(unique(name));
        price(String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?", sponsor)
            .getFirst().get("operator_id")));
        return sponsor;
    }

    void price(String operator) {
        run(AudiencePricingProcesses.PRICES_SET, TestTokens.bearer(tokens, "fm5-prices",
            QbPermissions.AUDIENCE_PRICES_WRITE), body("owner", operator, "minimumFee", 0, "prices",
            List.of(body("ruleKey", "gender", "dailyPrice", 100)), "reason", "F-M5"));
    }

    /** A seven-day draft of the Sponsor's that uses the gender rule. */
    String audienceDraft(String sponsor, String name) {
        String token = sponsorToken(sponsor);
        String publicationId = draft(token, version(token, name + " quiz"), name);
        Map<String, Object> gender = new HashMap<>();
        gender.put("key", "gender");
        gender.put("options", List.of("FEMALE"));
        run("QB_PUBLICATION_AUDIENCE_SAVE", token, body("publicationId", publicationId, "criteria",
            new ArrayList<>(List.of(gender))));
        return publicationId;
    }

    /** Fee credit as F-M2 grants it (5140 / 2190, the lot, the wallet's cache), expiring at {@code expiresAt}. */
    String grant(String sponsor, long amount, Instant expiresAt) {
        String grantId = UUID.randomUUID().toString();
        execute("INSERT INTO qb_fee_credit_grant (grant_id, sponsor_id, source, source_key, amount, remaining, "
            + "voided_amount, granted_at, expires_at, version) VALUES (?, ?, 'MANUAL', ?, ?, ?, 0, ?, ?, 1)", grantId,
            sponsor, "test:" + grantId, amount, amount, Timestamp.from(clock.instant()), Timestamp.from(expiresAt));
        run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), body("bookingTime",
            clock.instant().toString(), "description", "Granted credit (test)", "sourceEntity", "QbFeeCreditGrant",
            "sourceId", grantId, "entries", List.of(
                body("accountCode", "5140", "direction", "DEBIT", "amount", amount, "dimensions",
                    Map.of("party", sponsor)),
                body("accountCode", "2190", "direction", "CREDIT", "amount", amount, "dimensions",
                    Map.of("party", sponsor)))));
        execute("INSERT INTO qb_wallet (wallet_id, owner_id, kind, balance, pending_count, pending_amount, pending_out, "
            + "version) VALUES (?, ?, 'FEE_CREDIT', ?, 0, 0, 0, 1) ON CONFLICT (owner_id, kind) DO UPDATE SET "
            + "balance = qb_wallet.balance + EXCLUDED.balance, version = qb_wallet.version + 1",
            UUID.randomUUID().toString(), sponsor, amount);
        return grantId;
    }

    static List<Map<String, Object>> prepayments(String publicationId) {
        return query("SELECT * FROM qb_prepayment WHERE publication_id = ? ORDER BY frozen_at, prepayment_id",
            publicationId);
    }

    static BigDecimal remainingOf(String grantId) {
        return (BigDecimal) query("SELECT remaining FROM qb_fee_credit_grant WHERE grant_id = ?", grantId).getFirst()
            .get("remaining");
    }

    static BigDecimal ledger(String code, String party) {
        Object value = query("SELECT coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), "
            + "0) AS b FROM ledger_entry_version e JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE a.account_code = ? AND e.dimension_1 = ?", code, party).getFirst().get("b");
        return new BigDecimal(String.valueOf(value));
    }

    /** What the daily check finds about the Sponsor in the account that pays the platform's fees (none: agrees). */
    List<Map<String, Object>> differences(String sponsor) {
        return items("qb.system.recon-fee-account", TestTokens.bearer(tokens, "fm5-recon", QbPermissions.WALLET_SYSTEM),
            Map.of("params", Map.of("at", clock.instant().toString()))).stream().filter(row -> sponsor.equals(String.valueOf(row.get("sponsorId")))
                && !"FEE_CREDIT_PROMOTION".equals(row.get("checkCode"))).toList();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> check(String sponsor, String publicationId) {
        return run(PublicationPrepayments.CHECK, sponsorToken(sponsor), body("publicationId", publicationId));
    }

    @Test
    void aNewSponsorWithGrantedCreditOnlySubmitsAndAShortfallWritesNothing() {
        autoApprove(false);
        String sponsor = pricedSponsor("fm5-new");
        String publicationId = audienceDraft(sponsor, "FM5 new");

        // Nothing in the account: the wizard says so, and the submission is refused with every amount.
        Map<String, Object> look = check(sponsor, publicationId);
        assertThat(look).containsEntry("enough", false).containsEntry("billingMode", "PREPAID");
        assertThat(new BigDecimal(String.valueOf(look.get("total")))).isEqualByComparingTo(SET_ASIDE);
        Map<String, Object> refusal = refused("QB_PUBLICATION_SUBMIT", sponsorToken(sponsor),
            body("publicationId", publicationId), 422);
        assertThat(codes(refusal)).containsExactly(FeeCodes.PUBLICATION_FUNDS);
        assertThat(String.valueOf(violations(refusal).getFirst().get("message"))).contains("770");
        assertThat(status(publicationId)).isEqualTo("DRAFT");
        assertThat(prepayments(publicationId)).isEmpty();
        assertThat(query("SELECT 1 FROM sys_approval_request_version WHERE entity_id = ?", publicationId)).isEmpty();
        // M6 of the F-M5 review: nothing at all: no posting, no part, no new version of the publication.
        assertThat(query("SELECT 1 FROM ledger_entry_version WHERE dimension_1 = ?", sponsor)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE sponsor_id = ?", sponsor)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_publication_version WHERE publication_id = CAST(? AS uuid) AND "
            + "billing_operator_id IS NOT NULL", publicationId)).isEmpty();
        long versions = query("SELECT 1 FROM qb_publication_version WHERE publication_id = CAST(? AS uuid)",
            publicationId).size();

        refused("QB_PUBLICATION_SUBMIT", sponsorToken(sponsor), body("publicationId", publicationId), 422);
        assertThat(query("SELECT 1 FROM qb_publication_version WHERE publication_id = CAST(? AS uuid)", publicationId))
            .hasSize((int) versions);

        // Granted credit alone covers it: set aside from the grant, the rest still to spend.
        String grantId = grant(sponsor, 1_000, clock.instant().plus(Duration.ofDays(90)));
        // Refused with a shortfall again: the grant is not touched.
        execute("UPDATE qb_fee_credit_grant SET remaining = 700, version = version + 1 WHERE grant_id = ?", grantId);
        execute("UPDATE qb_wallet SET balance = 700, version = version + 1 WHERE owner_id = ? AND kind = 'FEE_CREDIT'",
            sponsor);
        refused("QB_PUBLICATION_SUBMIT", sponsorToken(sponsor), body("publicationId", publicationId), 422);
        assertThat(remainingOf(grantId)).isEqualByComparingTo("700");
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE sponsor_id = ?", sponsor)).isEmpty();
        execute("UPDATE qb_fee_credit_grant SET remaining = 1000, version = version + 1 WHERE grant_id = ?", grantId);
        execute("UPDATE qb_wallet SET balance = 1000, version = version + 1 WHERE owner_id = ? AND kind = "
            + "'FEE_CREDIT'", sponsor);
        assertThat(check(sponsor, publicationId)).containsEntry("enough", true);
        submit(sponsorToken(sponsor), publicationId);
        assertThat(status(publicationId)).isEqualTo("PENDING_REVIEW");
        assertThat(prepayments(publicationId)).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("purpose", "AUDIENCE").containsEntry("mode", "PREPAID")
                .containsEntry("status", "OPEN");
            assertThat((BigDecimal) p.get("remaining")).isEqualByComparingTo(SET_ASIDE);
            assertThat((BigDecimal) p.get("charge_amount")).isEqualByComparingTo("700");
        });
        assertThat(remainingOf(grantId)).isEqualByComparingTo("230");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo(SET_ASIDE);
        assertThat(row(publicationId).get("billing_operator_id")).isNotNull();
        assertThat((BigDecimal) row(publicationId).get("prepayment_total")).isEqualByComparingTo(SET_ASIDE);
        assertThat(differences(sponsor)).isEmpty();

        // Withdrawn: everything back to the grant it came from.
        run("QB_PUBLICATION_WITHDRAW", sponsorToken(sponsor), body("publicationId", publicationId));
        assertThat(prepayments(publicationId)).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("status", "RELEASED").containsEntry("release_reason", "WITHDRAWN");
            assertThat((BigDecimal) p.get("remaining")).isEqualByComparingTo("0");
        });
        assertThat(remainingOf(grantId)).isEqualByComparingTo("1000");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(row(publicationId).get("prepayment_total")).isNull();
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void rejectedOrExpiredInReviewGivesItBackAndAnExpiredGrantIsVoidedAgain() {
        autoApprove(false);
        String sponsor = reviewedSponsor("fm5-reject");
        String grantId = grant(sponsor, 2_000, clock.instant().plus(Duration.ofDays(90)));
        String rejected = audienceDraft(sponsor, "FM5 rejected");
        String requestId = String.valueOf(submit(sponsorToken(sponsor), rejected).get("approvalRequestId"));
        decide(requestId, "REJECT", "Not this time");
        assertThat(status(rejected)).isEqualTo("DRAFT");
        assertThat(prepayments(rejected)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("release_reason", "REJECTED"));
        assertThat(remainingOf(grantId)).isEqualByComparingTo("2000");

        // A grant that expires while the publication waits: what goes back to it is voided at once.
        String shortGrant = grant(sponsor, 800, clock.instant().plus(Duration.ofHours(30)));
        String expiring = audienceDraft(sponsor, "FM5 expiring");
        submit(sponsorToken(sponsor), expiring);
        assertThat(remainingOf(shortGrant)).isEqualByComparingTo("30");
        clock.set(clock.instant().plus(Duration.ofDays(9)));
        runTick();
        assertThat(status(expiring)).isEqualTo("DRAFT");
        assertThat(prepayments(expiring)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("release_reason", "REVIEW_EXPIRED"));
        Map<String, Object> lot = query("SELECT remaining, voided_amount FROM qb_fee_credit_grant WHERE grant_id = ?",
            shortGrant).getFirst();
        // Expired while it waited: what comes back and what it still held (30) are voided at once.
        assertThat((BigDecimal) lot.get("remaining")).isEqualByComparingTo("0");
        assertThat((BigDecimal) lot.get("voided_amount")).isEqualByComparingTo("800");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void goingOnChargesTheFeeFromItsPrepaymentToTheFrozenOperatorAndTheEndClosesIt() {
        autoApprove(true);
        String sponsor = pricedSponsor("fm5-live");
        grant(sponsor, 1_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = audienceDraft(sponsor, "FM5 live");
        String operator = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
            sponsor).getFirst().get("operator_id"));
        submit(sponsorToken(sponsor), publicationId);
        assertThat(status(publicationId)).isEqualTo("SCHEDULED");
        // M7 of the F-M5 review (§8.8): approved is not on: nothing is charged before it starts.
        assertThat(query("SELECT 1 FROM qb_fee_charge_version WHERE sponsor_id = ?", sponsor)).isEmpty();
        goLive(publicationId);
        Map<String, Object> charge = query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = "
            + "'AUDIENCE'", sponsor).getFirst();
        assertThat(charge).containsEntry("operator_id", operator);
        assertThat((BigDecimal) charge.get("total")).isEqualByComparingTo(SET_ASIDE);
        assertThat(query("SELECT status FROM qb_fee_invoice WHERE charge_id = ?", String.valueOf(charge.get(
            "charge_id")))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "PAID"));
        assertThat(prepayments(publicationId)).singleElement().satisfies(p ->
            assertThat((BigDecimal) p.get("remaining")).isEqualByComparingTo("0"));
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();

        // A day after its end, the daily job closes what is left.
        clock.set(clock.instant().plus(Duration.ofDays(8)));
        runTick();
        assertThat(status(publicationId)).isEqualTo("ENDED");
        systemRun(Prepayments.RELEASE_ITEM, body("publicationId", publicationId, "sponsorId", sponsor));
        assertThat(prepayments(publicationId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "RELEASED")
            .containsEntry("release_reason", "ENDED"));
        assertThat(differences(sponsor)).isEmpty();
    }

    /** Money in the cash account: Kudos credited and sold back (a lot of its own). */
    void cash(String sponsor, long amount) {
        credit(sponsor, amount);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", amount));
    }

    /** Past its start: the minute's job puts the publication on. */
    void goLive(String publicationId) {
        clock.set(clock.instant().plus(Duration.ofDays(1)).plus(Duration.ofMinutes(5)));
        runTick();
        assertThat(status(publicationId)).isEqualTo("LIVE");
    }

    /** Voids an invoice as finance does: recorded by one administrator, approved by another. */
    void voidInvoice(Object invoiceId) {
        Map<String, Object> voiding = run(FeeRequests.RECORD, tokenOf(user("fm5-finance"), QbRoles.ADMIN_FINANCE),
            body("kind", "VOID", "invoiceId", String.valueOf(invoiceId), "reason", "Charged by mistake"));
        run("APPROVAL_DECIDE", tokenOf(user("fm5-content"), QbRoles.ADMIN_CONTENT),
            body("requestId", voiding.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
    }

    /**
     * Every posting so far is of a kind of the monthly report: OTHER holds only the postings of no source (none of
     * this class's processes posts without one).
     */
    void everyPostingHasItsKind() {
        Instant from = Instant.parse("2000-01-01T00:00:00Z");
        Instant to = clock.instant().plus(Duration.ofDays(1));
        Map<String, Object> other = items("qb.admin.monthly", TestTokens.bearer(tokens, "fm5-monthly",
            QbPermissions.ADMIN_FINANCE_READ), Map.of("params", Map.of("from", from.toString(), "to", to.toString()),
            "limit", 100)).getLast();
        assertThat(other).containsEntry("category", "OTHER");
        long noSource = ((Number) query("SELECT count(*) AS n FROM ledger_transaction_version WHERE source_entity "
            + "IS NULL").getFirst().get("n")).longValue();
        assertThat(((Number) other.get("transactionCount")).longValue()).isEqualTo(noSource);
    }

    @Test
    void endedBeforeItStartsChargesNothingAndGivesEverythingBack() {
        // M7 of the F-M5 review: the fee is charged when the publication starts; ended before that, nothing is
        // charged and the daily job gives the whole prepayment back.
        autoApprove(true);
        String sponsor = pricedSponsor("fm5-early-end");
        String grantId = grant(sponsor, 1_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = audienceDraft(sponsor, "FM5 ended early");
        submit(sponsorToken(sponsor), publicationId);
        assertThat(status(publicationId)).isEqualTo("SCHEDULED");
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fm5-content-admin",
            QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", publicationId, "reason", "F-M5 test"));
        assertThat(status(publicationId)).isEqualTo("ENDED");
        assertThat(query("SELECT 1 FROM qb_fee_charge_version WHERE sponsor_id = ?", sponsor)).isEmpty();
        systemRun(Prepayments.RELEASE_ITEM, body("publicationId", publicationId, "sponsorId", sponsor));
        assertThat(prepayments(publicationId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "RELEASED").containsEntry("release_reason", "ENDED");
            assertThat(row.get("charged_at")).isNull();
        });
        assertThat(remainingOf(grantId)).isEqualByComparingTo("1000");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(query("SELECT 1 FROM qb_fee_charge_version WHERE sponsor_id = ?", sponsor)).isEmpty();
        assertThat(differences(sponsor)).isEmpty();
    }

    void billing(String sponsor, String mode, long creditLimit) {
        run(FeeProcesses.BILLING_SET, TestTokens.bearer(tokens, "fm5-hq-billing", QbPermissions.FEE_BILLING),
            body("userId", sponsor, "billingMode", mode, "creditLimit", creditLimit, "reason", "F-M5"));
    }

    /** The month's postpaid invoice as the job's item issues it for the sponsor: its outcome. */
    Object invoiceTheMonth(String sponsor) {
        return ((FeeProcesses.ItemOutput) systemRun(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor, "period",
            FeeRules.period(clock.instant(), java.time.ZoneId.of("Asia/Tokyo"))))).outcome();
    }

    static Map<String, Object> chargeOf(String sponsor) {
        return query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'AUDIENCE'", sponsor)
            .getFirst();
    }

    @Test
    void whatThePrepaymentDoesNotCoverIsPaidFromTheAccount() {
        // M1 of the F-M5 review: the tax rate changed while it waited: the prepayment pays what it holds, the account
        // the rest, in its order, on the charge's one invoice.
        autoApprove(true);
        String sponsor = pricedSponsor("fm5-short");
        String grantId = grant(sponsor, 1_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = audienceDraft(sponsor, "FM5 short");
        submit(sponsorToken(sponsor), publicationId);
        assertThat(remainingOf(grantId)).isEqualByComparingTo("230");
        param(QbParams.FEE_TAX_RATE, "0.2");
        try {
            goLive(publicationId);
        } finally {
            param(QbParams.FEE_TAX_RATE, "0.1");
        }
        Map<String, Object> charge = chargeOf(sponsor);
        assertThat((BigDecimal) charge.get("total")).isEqualByComparingTo("840");
        Map<String, Object> invoice = query("SELECT * FROM qb_fee_invoice WHERE charge_id = ?",
            String.valueOf(charge.get("charge_id"))).getFirst();
        assertThat(invoice).containsEntry("status", "PAID");
        assertThat((BigDecimal) invoice.get("paid_amount")).isEqualByComparingTo("840");
        assertThat(remainingOf(grantId)).isEqualByComparingTo("160");
        assertThat(query("SELECT coalesce(sum(amount), 0) AS n FROM qb_fee_payment_version WHERE target_id = ? AND "
            + "prepayment_id IS NOT NULL", String.valueOf(charge.get("charge_id"))).getFirst().get("n"))
            .satisfies(n -> assertThat((BigDecimal) n).isEqualByComparingTo("770"));
        assertThat(query("SELECT coalesce(sum(amount), 0) AS n FROM qb_fee_payment_version WHERE target_id = ? AND "
            + "prepayment_id IS NULL", String.valueOf(charge.get("charge_id"))).getFirst().get("n"))
            .satisfies(n -> assertThat((BigDecimal) n).isEqualByComparingTo("70"));
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("0");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();

        // Voided: both payments go back where they came from.
        voidInvoice(invoice.get("invoice_id"));
        assertThat(remainingOf(grantId)).isEqualByComparingTo("1000");
        assertThat(ledger("2190", sponsor)).isEqualByComparingTo("1000");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
        everyPostingHasItsKind();
    }

    @Test
    void aPrepaidPrepaymentIsPaidAsPrepaidAfterTheSponsorTurnsPostpaid() {
        // M2 of the F-M5 review: the prepayment's mode decides how its fee is paid, not the sponsor's now; the
        // month's postpaid invoice does not take it again.
        autoApprove(true);
        String sponsor = pricedSponsor("fm5-turned-post");
        grant(sponsor, 1_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = audienceDraft(sponsor, "FM5 turned postpaid");
        submit(sponsorToken(sponsor), publicationId);
        billing(sponsor, "POSTPAID", 5_000);
        goLive(publicationId);
        Map<String, Object> charge = chargeOf(sponsor);
        assertThat(charge).containsEntry("billing_mode", "PREPAID");
        assertThat(query("SELECT status FROM qb_fee_invoice WHERE charge_id = ?",
            String.valueOf(charge.get("charge_id")))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "PAID"));
        assertThat(invoiceTheMonth(sponsor)).isEqualTo("NONE");
        assertThat(query("SELECT 1 FROM qb_fee_invoice WHERE sponsor_id = ? AND charge_id IS NULL", sponsor)).isEmpty();
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void aPostpaidPrepaymentIsOwedAsPostpaidAfterTheSponsorTurnsPrepaid() {
        autoApprove(true);
        String sponsor = pricedSponsor("fm5-turned-pre");
        billing(sponsor, "POSTPAID", 5_000);
        grant(sponsor, 1_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = audienceDraft(sponsor, "FM5 turned prepaid");
        submit(sponsorToken(sponsor), publicationId);
        billing(sponsor, "PREPAID", 0);
        goLive(publicationId);
        Map<String, Object> charge = chargeOf(sponsor);
        assertThat(charge).containsEntry("billing_mode", "POSTPAID");
        assertThat(query("SELECT 1 FROM qb_fee_invoice WHERE charge_id = ?",
            String.valueOf(charge.get("charge_id")))).isEmpty();
        // Owed, not paid from the account: the granted credit is untouched.
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("-770");
        assertThat(ledger("2190", sponsor)).isEqualByComparingTo("1000");
        // The month's invoice takes it, once.
        assertThat(invoiceTheMonth(sponsor)).isEqualTo("ISSUED");
        assertThat(invoiceTheMonth(sponsor)).isEqualTo("NONE");
        assertThat(query("SELECT total FROM qb_fee_invoice WHERE sponsor_id = ? AND charge_id IS NULL", sponsor))
            .singleElement().satisfies(row -> assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("770"));
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void theCheckCountsWhatTheFreezeCanTakeWhenTheCashLotsAreApartFromTheWallet() {
        // M4 of the F-M5 review: the check and the freeze use one order (FeeAllocation); with the lots short of the
        // cash wallet (the daily check CASH_LOTS reports it) the submission is refused with the funds' code, not 500.
        autoApprove(false);
        String sponsor = pricedSponsor("fm5-lots");
        cash(sponsor, 1_000);
        execute("UPDATE qb_cash_lot SET remaining = 100, version = version + 1 WHERE sponsor_id = ?", sponsor);
        String publicationId = audienceDraft(sponsor, "FM5 lots apart");
        Map<String, Object> look = check(sponsor, publicationId);
        assertThat(look).containsEntry("enough", false);
        assertThat(new BigDecimal(String.valueOf(look.get("available")))).isEqualByComparingTo("100");
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorToken(sponsor), body("publicationId", publicationId),
            422))).containsExactly(FeeCodes.PUBLICATION_FUNDS);
        assertThat(prepayments(publicationId)).isEmpty();
        assertThat(differences(sponsor)).extracting(row -> row.get("checkCode")).contains("CASH_LOTS");
    }

    @Test
    void theDailyCheckFindsPrepaymentsNotGivenBackAndPartsThatDoNotAddUp() {
        // M3 of the F-M5 review.
        autoApprove(true);
        String sponsor = pricedSponsor("fm5-stale");
        grant(sponsor, 2_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = audienceDraft(sponsor, "FM5 stale");
        submit(sponsorToken(sponsor), publicationId);
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fm5-content-admin",
            QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", publicationId, "reason", "F-M5 test"));
        // Within qb.prepayment.release.overdue-days (2) of its end: not yet a difference.
        clock.set(clock.instant().plus(Duration.ofDays(1)));
        assertThat(differences(sponsor)).isEmpty();
        // Past it, the daily job not run: reported.
        clock.set(clock.instant().plus(Duration.ofDays(2)));
        assertThat(differences(sponsor)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("checkCode", "PREPAYMENT_STALE");
            assertThat(new BigDecimal(String.valueOf(row.get("actual")))).isEqualByComparingTo(SET_ASIDE);
        });
        systemRun(Prepayments.RELEASE_ITEM, body("publicationId", publicationId, "sponsorId", sponsor));
        assertThat(differences(sponsor)).isEmpty();

        // A prepayment of no publication there.
        execute("INSERT INTO qb_prepayment (prepayment_id, sponsor_id, publication_id, purpose, mode, amount, remaining, "
            + "charge_amount, status, frozen_at, version) VALUES (?, ?, ?, 'AUDIENCE', 'POSTPAID', 10, 10, 0, 'OPEN', ?, "
            + "1)", UUID.randomUUID().toString(), sponsor, UUID.randomUUID().toString(), Timestamp.from(clock.instant()));
        assertThat(differences(sponsor)).extracting(row -> row.get("checkCode")).containsExactly("PREPAYMENT_STALE");
        execute("UPDATE qb_prepayment SET status = 'RELEASED', remaining = 0, released_at = ?, version = version + 1 "
            + "WHERE sponsor_id = ? AND mode = 'POSTPAID'", Timestamp.from(clock.instant()), sponsor);

        // What a prepayment holds against its parts.
        String second = audienceDraft(sponsor, "FM5 parts");
        submit(sponsorToken(sponsor), second);
        assertThat(differences(sponsor)).isEmpty();
        execute("UPDATE qb_prepayment SET remaining = remaining - 1, version = version + 1 WHERE publication_id = ?",
            second);
        assertThat(differences(sponsor)).extracting(row -> row.get("checkCode"))
            .containsExactlyInAnyOrder("PREPAYMENT_PARTS", "PREPAYMENTS");
    }

    /** One row of the monthly report: its count, its amount and what each account moved (debit less credit). */
    record Row(String category, long count, long amount, Map<String, Long> moves) {}

    @Test
    void theMonthlyReportHasEveryPrepaymentMoveInItsRow() {
        // M5 and M6 of the F-M5 review: set aside of the fee credit and the cash together, given back the same way,
        // paid from, and given back to fee credit expired meanwhile (voided again at once): each in its row of a month
        // of their own (no other test posts in it), nothing in OTHER.
        java.time.ZoneId tokyo = java.time.ZoneId.of("Asia/Tokyo");
        Instant from = java.time.LocalDate.of(2031, 3, 1).atStartOfDay(tokyo).toInstant();
        Instant to = java.time.LocalDate.of(2031, 4, 1).atStartOfDay(tokyo).toInstant();
        clock.set(from.plus(Duration.ofDays(1)));
        String sponsor = pricedSponsor("fm5-month");
        String lasting = grant(sponsor, 300, clock.instant().plus(Duration.ofDays(90)));
        cash(sponsor, 1_000);

        // Set aside 300 of the fee credit and 470 of the cash, and given back to them (withdrawn).
        autoApprove(false);
        String withdrawn = audienceDraft(sponsor, "FM5 month withdrawn");
        submit(sponsorToken(sponsor), withdrawn);
        assertThat(query("SELECT sub_account, sum(amount) AS n FROM qb_fee_payment_version WHERE target_id = (SELECT "
            + "prepayment_id FROM qb_prepayment WHERE publication_id = ?) GROUP BY sub_account ORDER BY sub_account",
            withdrawn)).extracting(row -> row.get("sub_account") + " " + ((BigDecimal) row.get("n")).toPlainString())
            .containsExactly("CASH 470", "FEE_CREDIT 300");
        run("QB_PUBLICATION_WITHDRAW", sponsorToken(sponsor), body("publicationId", withdrawn));
        assertThat(remainingOf(lasting)).isEqualByComparingTo("300");
        assertThat(ledger("2160", sponsor)).isEqualByComparingTo("1000");

        // Set aside the same way again and paid from when it starts.
        autoApprove(true);
        String paid = audienceDraft(sponsor, "FM5 month paid");
        submit(sponsorToken(sponsor), paid);
        goLive(paid);

        // Set aside of a grant that expires while it waits: given back, it is voided again at once (770 and the 30
        // the lot still had).
        autoApprove(false);
        String expiring = grant(sponsor, 800, clock.instant().plus(Duration.ofDays(2)));
        String voided = audienceDraft(sponsor, "FM5 month voided");
        submit(sponsorToken(sponsor), voided);
        clock.set(clock.instant().plus(Duration.ofDays(3)));
        run("QB_PUBLICATION_WITHDRAW", sponsorToken(sponsor), body("publicationId", voided));
        assertThat(query("SELECT voided_amount FROM qb_fee_credit_grant WHERE grant_id = ?", expiring).getFirst()
            .get("voided_amount")).satisfies(n -> assertThat((BigDecimal) n).isEqualByComparingTo("800"));
        assertThat(differences(sponsor)).isEmpty();

        List<Row> expected = List.of(
            new Row("FEE_CHARGED", 1, 770, Map.of("1130", 770L, "4120", -700L, "2150", -70L)),
            new Row("FEE_CREDIT_VOIDED", 1, 800, Map.of("2190", 800L, "5140", -800L)),
            new Row("PREPAYMENT_FROZEN", 3, 2_310, Map.of("2190", 1_370L, "2160", 940L, "2180", -2_310L)),
            new Row("PREPAYMENT_USED", 1, 770, Map.of("2180", 770L, "1130", -770L)),
            new Row("PREPAYMENT_RELEASED", 2, 1_540, Map.of("2180", 1_540L, "2190", -1_070L, "2160", -470L)));
        Map<String, String> columns = Map.of("1130", "feesReceivable", "4120", "feeRevenue", "2150", "taxPayable",
            "2190", "feeCredit", "5140", "feeCreditPromotion", "2160", "sponsorCash", "2180", "feePrepayments");
        Map<String, Map<String, Object>> report = new java.util.HashMap<>();
        items("qb.admin.monthly", TestTokens.bearer(tokens, "fm5-monthly", QbPermissions.ADMIN_FINANCE_READ),
            Map.of("params", Map.of("from", from.toString(), "to", to.toString()), "limit", 100))
            .forEach(row -> report.put((String) row.get("category"), row));
        for (Row want : expected) {
            Map<String, Object> got = report.get(want.category());
            assertThat(((Number) got.get("transactionCount")).longValue()).as(want.category() + " count")
                .isEqualTo(want.count());
            assertThat(new BigDecimal(String.valueOf(got.get("amount")))).as(want.category() + " amount")
                .isEqualByComparingTo(BigDecimal.valueOf(want.amount()));
            for (Map.Entry<String, String> column : columns.entrySet()) {
                assertThat(new BigDecimal(String.valueOf(got.get(column.getValue()))))
                    .as(want.category() + " " + column.getKey())
                    .isEqualByComparingTo(BigDecimal.valueOf(want.moves().getOrDefault(column.getKey(), 0L)));
            }
        }
        // OTHER: only the development credit the test's cash came from (a posting of no source).
        assertThat(((Number) report.get("OTHER").get("transactionCount")).longValue()).isEqualTo(((Number) query(
            "SELECT count(*) AS n FROM ledger_transaction_version WHERE booking_time >= ? AND booking_time < ? AND "
                + "source_entity IS NULL", Timestamp.from(from), Timestamp.from(to)).getFirst().get("n")).longValue());
    }

    @Test
    void theDailyJobGivesBackWhatEndedPublicationsHoldOnceAndLeavesTheOthers() {
        // M6 of the F-M5 review: the job as the scheduler runs it, twice; a publication not ended is skipped.
        autoApprove(true);
        String sponsor = pricedSponsor("fm5-job");
        String grantId = grant(sponsor, 2_000, clock.instant().plus(Duration.ofDays(90)));
        String ended = audienceDraft(sponsor, "FM5 job ended");
        submit(sponsorToken(sponsor), ended);
        String running = audienceDraft(sponsor, "FM5 job running");
        submit(sponsorToken(sponsor), running);
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fm5-content-admin",
            QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", ended, "reason", "F-M5 test"));
        assertThat(remainingOf(grantId)).isEqualByComparingTo("460");

        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        JobRunner.JobRun first = jobRunner.execute(qbPrepaymentReleaseJob, clock.instant());
        assertThat(first.items().failed()).isZero();
        assertThat(prepayments(ended)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "RELEASED").containsEntry("release_reason",
                "ENDED"));
        assertThat(prepayments(running)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "OPEN"));
        assertThat(remainingOf(grantId)).isEqualByComparingTo("1230");
        BigDecimal held = ledger("2180", sponsor);
        assertThat(held).isEqualByComparingTo(SET_ASIDE);

        // Again (the next day's run, or a run asked again): nothing more is given back.
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        JobRunner.JobRun second = jobRunner.execute(qbPrepaymentReleaseJob, clock.instant().plus(Duration.ofDays(1)));
        assertThat(second.items().failed()).isZero();
        assertThat(remainingOf(grantId)).isEqualByComparingTo("1230");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo(held);
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE target_id IN (SELECT prepayment_id FROM "
            + "qb_prepayment WHERE publication_id = ?) AND amount < 0", ended)).hasSize(1);
        // The item of a publication not ended does nothing.
        assertThat(((com.jabiz.quizbuks.fee.FeeProcesses.ItemOutput) systemRun(Prepayments.RELEASE_ITEM,
            body("publicationId", running, "sponsorId", sponsor))).outcome()).isEqualTo("SKIPPED");
        assertThat(prepayments(running)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "OPEN"));
        assertThat(differences(sponsor)).isEmpty();
        // The records written once are never changed (the ledger, the charges and the payment parts).
        for (String table : List.of("ledger_transaction_version", "ledger_entry_version", "qb_fee_charge_version",
            "qb_fee_payment_version")) {
            assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
                .as(table).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
        }
    }

    @Test
    void voidingAnInvoicePaidFromItsPrepaymentGivesItBackToTheAccountAndTheBooksAgree() {
        // H1 (F-M5 review): what paid the invoice from the prepayment goes back to the fee credit and the cash it came
        // from, and is posted out of 2180 back to 2190 / 2160.
        autoApprove(true);
        String sponsor = pricedSponsor("fm5-void");
        String grantId = grant(sponsor, 300, clock.instant().plus(Duration.ofDays(90)));
        cash(sponsor, 1_000);
        String publicationId = audienceDraft(sponsor, "FM5 void");
        submit(sponsorToken(sponsor), publicationId);
        goLive(publicationId);
        Map<String, Object> charge = query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = "
            + "'AUDIENCE'", sponsor).getFirst();
        Map<String, Object> invoice = query("SELECT * FROM qb_fee_invoice WHERE charge_id = ?",
            String.valueOf(charge.get("charge_id"))).getFirst();
        assertThat(invoice).containsEntry("status", "PAID");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");

        voidInvoice(invoice.get("invoice_id"));
        assertThat(query("SELECT status FROM qb_fee_invoice WHERE invoice_id = ?", String.valueOf(
            invoice.get("invoice_id"))).getFirst()).containsEntry("status", QbFees.VOID);
        assertThat(remainingOf(grantId)).isEqualByComparingTo("300");
        assertThat(ledger("2190", sponsor)).isEqualByComparingTo("300");
        assertThat(ledger("2160", sponsor)).isEqualByComparingTo("1000");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("0");
        // What came back names the prepayment it had been paid from, as what paid did.
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE sponsor_id = ? AND amount < 0 AND target_entity "
            + "= 'QbFeeCharge' AND prepayment_id IS NULL", sponsor)).isEmpty();
        assertThat(differences(sponsor)).isEmpty();
        everyPostingHasItsKind();
    }

    @Test
    void movedToAnotherOperatorWhileInReviewTheFeeStillBelongsToTheOneFrozenAtTheSubmission() {
        // F-M11: the submission freezes the operator; a move before it goes on does not change whose fee it is.
        autoApprove(false);
        String sponsor = reviewedSponsor("fm11-move");
        grant(sponsor, 2_000, clock.instant().plus(Duration.ofDays(90)));
        String frozen = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
            sponsor).getFirst().get("operator_id"));
        String publicationId = audienceDraft(sponsor, "FM11 moved");
        String requestId = String.valueOf(submit(sponsorToken(sponsor), publicationId).get("approvalRequestId"));
        String hq = TestTokens.bearer(tokens, "fm11-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ,
            "approval.scope.move");
        String other = newOperator(hq, unique("FM11 other operator"));
        price(other);
        run(com.jabiz.quizbuks.operator.OperatorProcesses.ASSIGN, hq, body("userId", sponsor, "operatorId", other,
            "reason", "F-M11"));
        decide(requestId, "APPROVE", null);
        assertThat(status(publicationId)).isEqualTo("SCHEDULED");
        goLive(publicationId);
        assertThat(query("SELECT operator_id FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'AUDIENCE'",
            sponsor)).singleElement().satisfies(row -> assertThat(row).containsEntry("operator_id", frozen));
        assertThat(prepayments(publicationId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("operator_id", frozen));

        // What it submits now belongs to the new operator.
        String later = audienceDraft(sponsor, "FM11 after the move");
        submit(sponsorToken(sponsor), later);
        assertThat(prepayments(later)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("operator_id", other));
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void aPostpaidSponsorsLimitCountsWhatTheyOweAndWhatIsSetAside() {
        autoApprove(false);
        String sponsor = pricedSponsor("fm5-postpaid");
        run(com.jabiz.quizbuks.fee.FeeProcesses.BILLING_SET, TestTokens.bearer(tokens, "fm5-hq-billing",
            QbPermissions.FEE_BILLING), body("userId", sponsor, "billingMode", "POSTPAID", "creditLimit", 1200,
            "reason", "F-M5"));
        String first = audienceDraft(sponsor, "FM5 post 1");
        submit(sponsorToken(sponsor), first);
        assertThat(prepayments(first)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("mode", "POSTPAID"));
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        // 770 counted already: a second 770 is above 1,200.
        String second = audienceDraft(sponsor, "FM5 post 2");
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorToken(sponsor), body("publicationId", second), 422)))
            .containsExactly(FeeCodes.PUBLICATION_FUNDS);
        run("QB_PUBLICATION_WITHDRAW", sponsorToken(sponsor), body("publicationId", first));
        submit(sponsorToken(sponsor), second);
        assertThat(differences(sponsor)).isEmpty();
        // What they owe, invoiced or not (1130 for them), counts too: 500 owed + 770 is above 1,200.
        run("QB_PUBLICATION_WITHDRAW", sponsorToken(sponsor), body("publicationId", second));
        run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), body("bookingTime",
            clock.instant().toString(), "description", "A fee not invoiced (test)", "entries", List.of(
                body("accountCode", "1130", "direction", "DEBIT", "amount", 500, "dimensions",
                    Map.of("party", sponsor)),
                body("accountCode", "4120", "direction", "CREDIT", "amount", 500))));
        assertThat(check(sponsor, second)).containsEntry("enough", false);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorToken(sponsor), body("publicationId", second), 422)))
            .containsExactly(FeeCodes.PUBLICATION_FUNDS);
    }

    @Test
    void twoSubmissionsAtOnceNeverSetAsideMoreThanTheAccountHas() throws Exception {
        autoApprove(false);
        String sponsor = pricedSponsor("fm5-race");
        grant(sponsor, 1_000, clock.instant().plus(Duration.ofDays(90)));
        String one = audienceDraft(sponsor, "FM5 race 1");
        String two = audienceDraft(sponsor, "FM5 race 2");
        // One call first: the platform readies its input conversion once (BlockHound would flag two at once).
        check(sponsor, one);
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> submitStatus(sponsor, one));
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> submitStatus(sponsor, two));
        assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 422);
        assertThat(query("SELECT 1 FROM qb_prepayment WHERE sponsor_id = ? AND status = 'OPEN'", sponsor)).hasSize(1);
        assertThat(differences(sponsor)).isEmpty();
    }

    private int submitStatus(String sponsor, String publicationId) {
        return client.post().uri("/api/processes/QB_PUBLICATION_SUBMIT/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, sponsorToken(sponsor))
            .header("Idempotency-Key", UUID.randomUUID().toString()).bodyValue(body("publicationId", publicationId))
            .exchange().returnResult(String.class).getStatus().value();
    }
}
