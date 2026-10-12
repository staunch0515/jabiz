package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Top-ups that are not paid (docs/quizbuks/plans/Q6-payments.md, D-Q6-2, D-Q6-4): a checkout Stripe reports expired
 * expires the top-up; one whose events were lost is asked about at Stripe an hour after its expiry by the hourly job
 * and settled as the answer says (paid: credited once; expired: expired; still open: left, and not asked again for
 * six hours); a checkout that cannot be created fails the top-up.
 */
class TopUpExpiryIT extends TopUpItSupport {

    @Autowired
    @Qualifier("qbTopUpStaleJob")
    JobDefinition<?> staleJob;

    JobRunner.JobRun runStale() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobs.execute(staleJob, now());
    }

    static Map<String, Object> expiredSession(Map<String, Object> session) {
        session.put("status", "expired");
        session.put("payment_status", "unpaid");
        session.put("payment_intent", null);
        return session;
    }

    @Test
    void stripesExpiryExpiresTheTopUp() {
        String sponsor = newSponsor("s-expiry-event");
        String topUpId = openTopUp(sponsor, 1000);
        deliver(event(eventId(), "checkout.session.expired", expiredSession(completed(topUpId))));
        assertThat(topUp(topUpId)).containsEntry("status", "EXPIRED");
        assertThat(postingsOf(topUpId)).isEmpty();
        // An expired top-up no longer counts against the sponsor's unfinished ones, nor offers its checkout.
        assertThat(items("qb.sponsor.topups", sponsorToken(sponsor), Map.of()).getFirst().get("checkoutUrl"))
            .isNull();
    }

    @Test
    void lostEventsAreAskedAboutAtStripe() {
        String sponsor = newSponsor("s-expiry-stale");
        String paid = openTopUp(sponsor, 1000);
        String expired = openTopUp(sponsor, 2000);
        String open = openTopUp(sponsor, 3000);
        // Not yet an hour after the checkouts' expiry: nothing is asked.
        advance(Duration.ofMinutes(90));
        assertThat(runStale().items().selected()).isZero();
        advance(Duration.ofMinutes(31));
        STRIPE.script(SESSIONS + "/" + topUp(paid).get("checkout_session_id"),
            StubHttpServer.Answer.json(200, json.writeValueAsString(completed(paid))));
        STRIPE.script(SESSIONS + "/" + topUp(expired).get("checkout_session_id"),
            StubHttpServer.Answer.json(200, json.writeValueAsString(expiredSession(completed(expired)))));
        Map<String, Object> stillOpen = completed(open);
        stillOpen.put("status", "open");
        stillOpen.put("payment_status", "unpaid");
        STRIPE.script(SESSIONS + "/" + topUp(open).get("checkout_session_id"),
            StubHttpServer.Answer.json(200, json.writeValueAsString(stillOpen)));
        JobRunner.JobRun run = runStale();
        assertThat(run.items().succeeded()).isEqualTo(3);
        runJobs();
        assertThat(STRIPE.requests()).filteredOn(r -> r.path().startsWith(SESSIONS + "/")).hasSize(3)
            .allSatisfy(r -> assertThat(r.method()).isEqualTo("GET"));
        assertThat(topUp(paid)).containsEntry("status", "PAID");
        assertThat((String) topUp(paid).get("confirmed_by")).startsWith("job:");
        assertThat(postingsOf(paid)).hasSize(2);
        assertThat(balance(sponsor)).isEqualByComparingTo("1000");
        assertThat(topUp(expired)).containsEntry("status", "EXPIRED");
        assertThat(topUp(open)).containsEntry("status", "OPEN");
        assertThat(topUp(open).get("check_requested_at")).isNotNull();
        // Asked within six hours: not again; afterwards, again.
        STRIPE.reset();
        advance(Duration.ofHours(1));
        assertThat(runStale().items().selected()).isZero();
        advance(Duration.ofHours(6));
        STRIPE.script(SESSIONS + "/" + topUp(open).get("checkout_session_id"),
            StubHttpServer.Answer.json(200, json.writeValueAsString(expiredSession(completed(open)))));
        assertThat(runStale().items().succeeded()).isEqualTo(1);
        runJobs();
        assertThat(topUp(open)).containsEntry("status", "EXPIRED");
        // A late event of the paid one changes nothing.
        deliver(event(eventId(), "checkout.session.completed", completed(paid)));
        assertThat(postingsOf(paid)).hasSize(2);
        assertBooksBalance();
    }

    @Test
    void aCheckOfStripeThatFailsIsATask() {
        String sponsor = newSponsor("s-expiry-check-fails");
        String topUpId = openTopUp(sponsor, 1000);
        advance(Duration.ofHours(3));
        STRIPE.script(SESSIONS + "/" + topUp(topUpId).get("checkout_session_id"),
            StubHttpServer.Answer.json(401, "{\"error\":{\"type\":\"invalid_request_error\"}}"));
        runStale();
        runJobs();
        assertThat(topUp(topUpId)).containsEntry("status", "OPEN");
        assertThat(tasksAbout("topup:" + topUpId)).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("title_key", "task.qb.topup-check.CHECK_FAILED").containsEntry("subject_id", topUpId));
    }

    @Test
    void aCheckoutStripeRefusesFailsTheTopUp() {
        String sponsor = newSponsor("s-expiry-refused");
        String topUpId = startTopUp(sponsor, 1000);
        STRIPE.script(SESSIONS, StubHttpServer.Answer.json(400, "{\"error\":{\"type\":\"invalid_request_error\","
            + "\"code\":\"parameter_invalid_integer\"}}"));
        runJobs();
        assertThat(topUp(topUpId)).containsEntry("status", "FAILED").containsEntry("failure_code", "STRIPE_DECLINED");
        // Stripe being down is tried again: the next attempt succeeds.
        String retried = startTopUp(sponsor, 1000);
        STRIPE.script(SESSIONS, StubHttpServer.Answer.json(503, "{}"));
        runJobs();
        assertThat(topUp(retried)).containsEntry("status", "OPENING");
        advance(Duration.ofMinutes(1));
        runJobs();
        assertThat(topUp(retried)).containsEntry("status", "OPEN");
        assertThat(STRIPE.requests(SESSIONS)).hasSize(3).allSatisfy(r -> assertThat(r.header("Idempotency-Key"))
            .startsWith("qb-checkout-"));
    }

    @Test
    void aCheckoutThatDoesNotFitTheRecordFailsTheTopUp() {
        String sponsor = newSponsor("s-expiry-mismatch");
        String topUpId = startTopUp(sponsor, 1000);
        STRIPE.script(SESSIONS, StubHttpServer.Answer.json(200, """
            {"id": "cs_test_elsewhere", "status": "open", "mode": "payment", "currency": "jpy", "amount_total": 1000,
             "client_reference_id": "%s", "metadata": {"topUpId": "%s", "sponsorId": "%s"}, "livemode": false,
             "url": "https://evil.example.com/pay", "expires_at": 1999999999}""".formatted(topUpId, topUpId,
            sponsor)));
        runJobs();
        assertThat(topUp(topUpId)).containsEntry("status", "FAILED").containsEntry("failure_code", "OPEN_MISMATCH")
            .containsEntry("checkout_url", null);
        assertThat(tasksAbout("topup:" + topUpId)).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("title_key", "task.qb.topup-check.OPEN_MISMATCH"));
    }
}
