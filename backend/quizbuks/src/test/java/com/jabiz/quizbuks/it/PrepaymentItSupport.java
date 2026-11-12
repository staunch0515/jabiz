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
 * The fixtures of the F-M5 tests ({@link PrepaymentIT}, {@link SubmitStatementBudgetIT}): Sponsors of an operator that
 * prices the gender rule, drafts using it, granted credit and cash, and the reads that check the books.
 */
abstract class PrepaymentItSupport extends PlayItSupport {

    @Autowired
    JobDefinition<Prepayments.ReleaseRef> qbPrepaymentReleaseJob;

    /** 100 a day for gender over a seven-day run: 700, and 10% tax: 770 set aside. */
    static final BigDecimal SET_ASIDE = new BigDecimal("770");
    /** The classes whose schema has the switches on (each test class has a schema of its own). */
    private static final java.util.Set<Class<?>> SWITCHED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @BeforeEach
    void switches() {
        if (SWITCHED.add(getClass())) {
            param(QbParams.AUDIENCE_BILLING_ENABLED, "true");
            param(QbParams.KUDOS_SELL_ENABLED, "true");
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
            Map.of("params", Map.of("at", clock.instant().toString(), "zone", "Asia/Tokyo"))).stream()
            .filter(row -> sponsor.equals(String.valueOf(row.get("sponsorId")))
                && !"FEE_CREDIT_PROMOTION".equals(row.get("checkCode"))
                && !"DISTRIBUTION_AT_FLOOR".equals(row.get("checkCode"))).toList();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> check(String sponsor, String publicationId) {
        return run(PublicationPrepayments.CHECK, sponsorToken(sponsor), body("publicationId", publicationId));
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
}
