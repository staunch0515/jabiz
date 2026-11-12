package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.DistributionPrices;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M6 (docs/quizbuks/plans/impl/F-M.md §9.4, §9.6; H1 of its review): how many statements a submission sends with the
 * distribution switch off and on, with and without an audience rule, and (N3) that the monthly fee, a fee by hand and
 * the audience fee's charge send as many as before F-M6 (their transaction's counts were measured on the base before
 * F-M6, 13e484c24, with this class's {@link #theChargesSendAsManyAsBefore} alone). Each case runs once before it is
 * counted (warm caches), as {@link SubmitStatementBudgetIT}. A change of these paths changes the numbers on purpose:
 * say why in the change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class DistributionStatementBudgetIT extends PrepaymentItSupport {

    /** Switch off, no audience rule: as F-M5's ({@link SubmitStatementBudgetIT#NOTHING_SET_ASIDE}). */
    static final int OFF_PLAIN = SubmitStatementBudgetIT.NOTHING_SET_ASIDE;
    /** Switch off, an audience rule, prepaid: as F-M5's ({@link SubmitStatementBudgetIT#PREPAID_SET_ASIDE}). */
    static final int OFF_AUDIENCE = SubmitStatementBudgetIT.PREPAID_SET_ASIDE;
    /**
     * Switch on, no audience rule [37]: F-M5's 31, the operator's distribution price (one read; the audience price
     * list's, keyed by nothing, sends none) and the freeze of the deposit: its operation record, its read of the
     * account, the prepayment with its posting's first writes, the lots' read and the posting's parts.
     */
    static final int ON_PLAIN = 37;
    /**
     * Switch on, an audience rule [42]: F-M5's 38; the distribution price joins the audience price list's read (one
     * {@code QueryEntities.all}: nothing more); the second freeze reads the lots again and writes its prepayment and
     * posting (four).
     */
    static final int ON_AUDIENCE = 42;

    /** N3: the monthly fee item of a prepaid sponsor [18, as before F-M6]. */
    static final int MONTHLY_PREPAID = 18;
    /** N3: the monthly fee item of a postpaid sponsor [11, as before: the month's invoice read joins a read]. */
    static final int MONTHLY_POSTPAID = 11;
    /** N3: a fee by hand, charged when its approval is delivered [25, as before F-M6]. */
    static final int MANUAL = 25;
    /** N3: the audience fee charged from its prepayment as the publication goes on [25, as before F-M6]. */
    static final int AUDIENCE_LIVE = 25;

    void distributionPrice(String sponsor) {
        String operator = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
            sponsor).getFirst().get("operator_id"));
        run(DistributionPrices.SET, TestTokens.bearer(tokens, "fm6-budget-hq", QbPermissions.FEE_MANAGE),
            body("operatorId", operator, "rate", "0.05", "unitFloor", "1", "unitCeiling", "20", "minimumDeposit", 500,
                "reason", "F-M6"));
    }

    @Test
    void theSubmissionsStatementsWithTheSwitchOffAndOn() {
        autoApprove(false);
        String sponsor = pricedSponsor("fm6-budget");
        distributionPrice(sponsor);
        grant(sponsor, 100_000, clock.instant().plus(Duration.ofDays(90)));

        param(QbParams.DISTRIBUTION_BILLING_ENABLED, "false");
        submitCounted(sponsor, publication(sponsor, "warm off plain", false));
        List<String> offPlain = submitCounted(sponsor, publication(sponsor, "off plain", false));
        submitCounted(sponsor, publication(sponsor, "warm off audience", true));
        List<String> offAudience = submitCounted(sponsor, publication(sponsor, "off audience", true));

        param(QbParams.DISTRIBUTION_BILLING_ENABLED, "true");
        submitCounted(sponsor, publication(sponsor, "warm on plain", false));
        List<String> onPlain = submitCounted(sponsor, publication(sponsor, "on plain", false));
        submitCounted(sponsor, publication(sponsor, "warm on audience", true));
        List<String> onAudience = submitCounted(sponsor, publication(sponsor, "on audience", true));
        assertThat(query("SELECT purpose FROM qb_prepayment WHERE sponsor_id = ? AND status = 'OPEN'", sponsor))
            .extracting(row -> row.get("purpose")).filteredOn("DISTRIBUTION"::equals).hasSize(4);

        print("submit, switch off, plain", offPlain);
        print("submit, switch off, audience", offAudience);
        print("submit, switch on, plain", onPlain);
        print("submit, switch on, audience", onAudience);
        assertThat(offPlain).as(listing(offPlain)).hasSize(OFF_PLAIN);
        assertThat(offAudience).as(listing(offAudience)).hasSize(OFF_AUDIENCE);
        assertThat(onPlain).as(listing(onPlain)).hasSize(ON_PLAIN);
        assertThat(onAudience).as(listing(onAudience)).hasSize(ON_AUDIENCE);
    }

    @Test
    void theChargesSendAsManyAsBefore() {
        autoApprove(true);
        param(QbParams.FEE_MONTHLY, "1000");
        String period = FeeRules.period(clock.instant(), ZoneId.of("Asia/Tokyo"));

        // The monthly fee: a prepaid sponsor paying from cash, a postpaid one owing it.
        List<String> monthlyPrepaid = null;
        List<String> monthlyPostpaid = null;
        for (int i = 0; i < 2; i++) {
            String prepaid = pricedSponsor("fm6-n3-monthly-" + i);
            cash(prepaid, 2_000);
            monthlyPrepaid = charged(() -> systemRun(FeeProcesses.MONTHLY_ITEM, body("sponsorId", prepaid, "period",
                period)));
            String postpaid = pricedSponsor("fm6-n3-postpaid-" + i);
            billing(postpaid, "POSTPAID", 100_000);
            monthlyPostpaid = charged(() -> systemRun(FeeProcesses.MONTHLY_ITEM, body("sponsorId", postpaid,
                "period", period)));
        }

        // A fee by hand, charged when another administrator's approval is delivered.
        List<String> manual = null;
        for (int i = 0; i < 2; i++) {
            String sponsor = pricedSponsor("fm6-n3-manual-" + i);
            cash(sponsor, 2_000);
            Map<String, Object> requested = run(FeeRequests.RECORD, tokenOf(user("fm6-n3-finance"),
                QbRoles.ADMIN_FINANCE), body("kind", "MANUAL", "sponsorId", sponsor, "amount", 500, "reason", "N3"));
            run("APPROVAL_DECIDE", tokenOf(user("fm6-n3-content"), QbRoles.ADMIN_CONTENT),
                body("requestId", requested.get("approvalRequestId"), "decision", "APPROVE"));
            manual = charged(this::deliverEvents);
        }

        // The audience fee, charged from its prepayment as the publication goes on.
        List<String> audience = null;
        for (int i = 0; i < 2; i++) {
            String sponsor = pricedSponsor("fm6-n3-audience-" + i);
            grant(sponsor, 10_000, clock.instant().plus(Duration.ofDays(90)));
            String publicationId = audienceDraft(sponsor, "N3 audience " + i);
            submit(sponsorToken(sponsor), publicationId);
            audience = charged(() -> goLive(publicationId));
        }

        print("monthly fee, prepaid", monthlyPrepaid);
        print("monthly fee, postpaid", monthlyPostpaid);
        print("fee by hand", manual);
        print("audience fee as it goes on", audience);
        assertThat(monthlyPrepaid).as(listing(monthlyPrepaid)).hasSize(MONTHLY_PREPAID);
        assertThat(monthlyPostpaid).as(listing(monthlyPostpaid)).hasSize(MONTHLY_POSTPAID);
        assertThat(manual).as(listing(manual)).hasSize(MANUAL);
        assertThat(audience).as(listing(audience)).hasSize(AUDIENCE_LIVE);
    }

    /** A complete draft of the sponsor's, with the audience rule or without. */
    String publication(String sponsor, String name, boolean audience) {
        if (audience) {
            return audienceDraft(sponsor, name);
        }
        String token = sponsorToken(sponsor);
        return draft(token, version(token, name + " quiz"), name);
    }

    List<String> submitCounted(String sponsor, String publicationId) {
        SqlStatementLog.clear();
        submit(sponsorToken(sponsor), publicationId);
        List<List<String>> own = SqlStatementLog.transactions().stream()
            .filter(t -> t.stream().anyMatch(sql -> sql.startsWith("INSERT INTO op_process ("))).toList();
        assertThat(own).as("one transaction").hasSize(1);
        return own.getFirst();
    }

    /** The statements of the one transaction {@code action} charged a fee in. */
    static List<String> charged(Runnable action) {
        SqlStatementLog.clear();
        action.run();
        List<List<String>> charging = SqlStatementLog.transactions().stream()
            .filter(t -> t.stream().anyMatch(sql -> sql.contains("INSERT INTO qb_fee_charge_version ("))).toList();
        assertThat(charging).as("one transaction charged a fee of:\n" + SqlStatementLog.transactions().stream()
            .map(DistributionStatementBudgetIT::listing).collect(Collectors.joining("\n----\n"))).hasSize(1);
        return charging.getFirst();
    }

    static void print(String what, List<String> statements) {
        System.out.println("[statement-budget] " + what + ": " + statements.size() + "\n" + listing(statements));
    }

    private static String listing(List<String> statements) {
        return IntStream.range(0, statements.size())
            .mapToObj(i -> String.format("%3d  %s", i + 1, statements.get(i).replaceAll("\\s+", " ")))
            .collect(Collectors.joining("\n"));
    }
}
