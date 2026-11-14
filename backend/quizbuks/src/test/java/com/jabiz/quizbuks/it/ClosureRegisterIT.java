package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.MoneyClose;
import com.jabiz.quizbuks.fee.QbSponsorClosures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.it.SyntheticRows.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M15 b (docs/quizbuks/plans/impl/F-M15.md §4.1, §5.1a, M1): an erased sponsor who had the platform fee account
 * (any one row of a cash or granted credit wallet, a lot of granted credit, an invoice, a prepayment) gets one wind-up
 * record, waiting; a taker and a sponsor without the account get none; a replay registers nothing more. The record is
 * written by a subscription of its own to the platform's event.
 */
class ClosureRegisterIT extends CreditNoteItSupport {

    @DynamicPropertySource
    static void maintenance(DynamicPropertyRegistry registry) {
        // The tests' login is the maintenance login too (a member of jabiz_maintenance, granted below), for the purge.
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
    }

    @BeforeEach
    void maintenanceRole() {
        execute("DO $$ BEGIN CREATE ROLE jabiz_maintenance; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        execute("DO $$ BEGIN EXECUTE 'GRANT jabiz_maintenance TO ' || quote_ident(current_user);"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
    }

    @Test
    void anErasedSponsorWithCashInTheAccountIsRegisteredByTheEvent() {
        String sponsor = newSponsor(unique("s-fm15b-erased"));
        cash(sponsor, 4000);
        assertThat(closures(sponsor)).isEmpty();

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", sponsor, "requestedBy", "it-support"));
        deliverEvents();

        assertThat(closures(sponsor)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", QbSponsorClosures.WAITING);
            // The registration asks for the wind-up at once; the switch is off here (declared off), so it only says so.
            assertThat(row).containsEntry("reason_code", QbSponsorClosures.SWITCH_OFF);
            assertThat(row.get("closed_at")).isNull();
            assertThat(row.get("erased_at")).isNotNull();
        });
    }

    @Test
    void eachOfTheEightKindsAloneRegistersTheSweepFindsTheSameAndAReplayRegistersNothingMore() {
        // A zero balance counts: the account was there.
        String wallet = newSponsor(unique("s-fm15b-wallet"));
        SyntheticRows.rows("qb_wallet", null, 1, null, n -> Map.of("owner_id", text(wallet), "kind", "'FEE_CREDIT'",
            "balance", "0", "held", "0", "pending_count", "0", "pending_amount", "0", "pending_out", "0"));
        String lot = newSponsor(unique("s-fm15b-lot"));
        SyntheticRows.rows("qb_fee_credit_grant", null, 1, null, n -> Map.of("sponsor_id", text(lot),
            "source", "'MANUAL'", "amount", "1000", "remaining", "1000", "voided_amount", "0", "expires_at", "TIMESTAMPTZ '2026-04-01T00:00:00Z'"));
        String invoice = newSponsor(unique("s-fm15b-invoice"));
        SyntheticRows.rows("qb_fee_invoice", null, 1, null, n -> Map.of("sponsor_id", text(invoice),
            "billing_mode", "'PREPAID'", "amount", "1000", "tax", "0", "total", "1000", "paid_amount", "0",
            "credit_applied", "0", "status", "'VOID'", "period", "'2026-01'"));
        String prepayment = newSponsor(unique("s-fm15b-prepayment"));
        SyntheticRows.rows("qb_prepayment", null, 1, null, n -> Map.of("sponsor_id", text(prepayment),
            "purpose", "'DISTRIBUTION'", "mode", "'PREPAID'", "amount", "2000", "remaining", "0",
            "status", "'RELEASED'", "released_at", "TIMESTAMPTZ '2026-01-02T00:00:00Z'"));
        String cash = newSponsor(unique("s-fm15b-cash"));
        cash(cash, 1);
        // The coordinator's decision (2026-11-14): a postpaid fee not invoiced yet.
        String postpaid = newSponsor(unique("s-fm15b-postpaid"));
        billing(postpaid, "POSTPAID", 50_000);
        manualFee(postpaid, 300);
        // Review M2: a manual credit or a fee request under review, nothing else.
        String credit = newSponsor(unique("s-fm15b-credit"));
        run(com.jabiz.quizbuks.wallet.ManualCredits.RECORD, finance(), Map.of("sponsorId", credit, "kind",
            "PAID_OUTSIDE", "amount", 3000, "reason", "Paid in cash", "receivedOn",
            java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneId.of("Asia/Tokyo")).toString(),
            "bankReference", "R-" + java.util.UUID.randomUUID().toString().substring(0, 8)));
        String request = newSponsor(unique("s-fm15b-request"));
        run(com.jabiz.quizbuks.fee.FeeRequests.RECORD, finance(), Map.of("kind",
            com.jabiz.quizbuks.fee.QbFees.REQUEST_MANUAL, "sponsorId", request, "amount", 500, "reason", "Custom report"));
        List<String> all = List.of(wallet, lot, invoice, prepayment, cash, postpaid, credit, request);

        // Erased with the events left undelivered: the daily sweep's template finds exactly these of them.
        for (String sponsor : all) {
            runAsSystem("ACCOUNT_ERASE", Map.of("userId", sponsor, "requestedBy", "it-support"));
        }
        assertThat(unregistered()).containsAll(all);
        for (String sponsor : all) {
            assertThat(register(sponsor)).as(sponsor).isTrue();
            assertThat(register(sponsor)).as("replayed " + sponsor).isFalse();
            assertThat(closures(sponsor)).as(sponsor).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", QbSponsorClosures.WAITING));
        }
        assertThat(unregistered()).doesNotContainAnyElementsOf(all);
    }

    @Test
    void aTakerAndASponsorWithoutTheAccountAreNotRegistered() {
        String taker = newTaker(unique("t-fm15b-none"), null);
        String sponsor = newSponsor(unique("s-fm15b-none"));
        for (String user : List.of(taker, sponsor)) {
            runAsSystem("ACCOUNT_ERASE", Map.of("userId", user, "requestedBy", "it-support"));
            assertThat(unregistered()).as(user).doesNotContain(user);
            assertThat(register(user)).as(user).isFalse();
            assertThat(closures(user)).as(user).isEmpty();
        }
    }

    private List<Object> unregistered() {
        return items(MoneyClose.UNREGISTERED_TEMPLATE, com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm15b-job",
            "qb.fee.system"), Map.of("limit", 1000)).stream().map(row -> row.get("userId")).toList();
    }

    private boolean register(String userId) {
        return ((MoneyClose.RegisterOutput) systemRun(MoneyClose.REGISTER, Map.of("userId", userId))).registered();
    }

    static List<Map<String, Object>> closures(String sponsor) {
        return query("SELECT * FROM qb_sponsor_closure WHERE sponsor_id = ?", sponsor);
    }
}
