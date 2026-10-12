package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A payment is credited once (docs/quizbuks/plans/Q6-payments.md, D-Q6-3, the four layers): whether Stripe's event
 * comes twice (the platform keeps one), two events report the same checkout, the event and the check of a stale
 * top-up both report it, or deliveries race; and only a payment that fits the record in every respect is credited at
 * all: anything else is a task for the finance administrators, with no posting and the wallet unchanged. What is not
 * signed, signed too long ago or too large is refused and leaves nothing.
 */
class TopUpIdempotencyIT extends TopUpItSupport {

    static long storedEvents(String externalId) {
        return ((Number) query("SELECT count(*) AS n FROM sys_inbound_event WHERE provider = 'stripe' "
            + "AND external_event_id = ?", externalId).getFirst().get("n")).longValue();
    }

    long arrivals(String topUpId) {
        return postingsOf(topUpId).stream().filter(line -> "2110".equals(line.get("account_code"))
            && "CREDIT".equals(line.get("direction"))).count();
    }

    @Test
    void theSameEventTwiceIsStoredAndCreditedOnce() {
        String sponsor = newSponsor("s-idem-dup");
        String topUpId = openTopUp(sponsor, 3000);
        int runs = query("SELECT 1 FROM op_process WHERE process_name = 'QB_TOPUP_CONFIRM'").size();
        Map<String, Object> event = event(eventId(), "checkout.session.completed", completed(topUpId));
        deliver(event);
        deliver(event);
        assertThat(storedEvents((String) event.get("id"))).isEqualTo(1);
        assertThat(arrivals(topUpId)).isEqualTo(1);
        assertThat(balance(sponsor)).isEqualByComparingTo("3000");
        // Another event of the same checkout (another id, the same session): done already.
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(arrivals(topUpId)).isEqualTo(1);
        assertThat(balance(sponsor)).isEqualByComparingTo("3000");
        // The duplicate was not even run: one run for each of the two events.
        assertThat(query("SELECT 1 FROM op_process WHERE process_name = 'QB_TOPUP_CONFIRM'")).hasSize(runs + 2);
        assertBooksBalance();
    }

    @Test
    void theEventAndTheCheckOfAStaleTopUpCreditOnce() {
        String sponsor = newSponsor("s-idem-check");
        String topUpId = openTopUp(sponsor, 2000);
        String session = (String) topUp(topUpId).get("checkout_session_id");
        // The event is received but not yet delivered when the stale job asks Stripe.
        send(event(eventId(), "checkout.session.completed", completed(topUpId)));
        advance(Duration.ofHours(3));
        Map<String, Object> answer = completed(topUpId);
        STRIPE.script(SESSIONS + "/" + session, StubHttpServer.Answer.json(200, json.writeValueAsString(answer)));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobs.execute(context.getBean("qbTopUpStaleJob", com.jabiz.job.JobDefinition.class), now())
            .outcome().name()).isEqualTo("SUCCEEDED");
        assertThat(jobExecutor.runPending().block()).isEqualTo(1);
        // Both reports are due now: deliveries race from four threads.
        deliverConcurrently(4);
        deliverEvents();
        assertThat(arrivals(topUpId)).isEqualTo(1);
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        assertThat(balance(sponsor)).isEqualByComparingTo("2000");
        assertBooksBalance();
    }

    @Test
    void manyEventsOfOneCheckoutDeliveredAtOnceCreditOnce() {
        String sponsor = newSponsor("s-idem-race");
        String topUpId = openTopUp(sponsor, 1500);
        for (int i = 0; i < 4; i++) {
            send(event(eventId(), "checkout.session.completed", completed(topUpId)));
        }
        deliverConcurrently(4);
        deliverEvents();
        assertThat(arrivals(topUpId)).isEqualTo(1);
        assertThat(balance(sponsor)).isEqualByComparingTo("1500");
        assertBooksBalance();
    }

    @Test
    void concurrentRequestsOfOneEventStoreOne() throws Exception {
        String sponsor = newSponsor("s-idem-http");
        String topUpId = openTopUp(sponsor, 1200);
        Map<String, Object> event = event(eventId(), "checkout.session.completed", completed(topUpId));
        byte[] body = json.writeValueAsBytes(event);
        long at = now().getEpochSecond();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                answers.add(pool.submit(() -> sendRaw(body, at, SECRET).expectBody().returnResult().getStatus()
                    .value()));
            }
            for (Future<Integer> answer : answers) {
                assertThat(answer.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        deliverEvents();
        assertThat(storedEvents((String) event.get("id"))).isEqualTo(1);
        assertThat(arrivals(topUpId)).isEqualTo(1);
    }

    @Test
    void whatIsNotSignedInTimeIsRefusedAndLeavesNothing() {
        String sponsor = newSponsor("s-idem-sig");
        String topUpId = openTopUp(sponsor, 1000);
        Map<String, Object> event = event(eventId(), "checkout.session.completed", completed(topUpId));
        byte[] body = json.writeValueAsBytes(event);
        long now = now().getEpochSecond();
        sendRaw(body, now, "whsec_not_the_configured_secret_0000000").expectStatus().isBadRequest();
        byte[] altered = new String(body, StandardCharsets.UTF_8).replace("\"amount_total\":1000",
            "\"amount_total\":1").getBytes(StandardCharsets.UTF_8);
        String signature = new com.jabiz.inbound.StripeSignature().sign(SECRET, now, body)
            .get(com.jabiz.inbound.StripeSignature.HEADER);
        client.post().uri(INBOUND).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(com.jabiz.inbound.StripeSignature.HEADER, signature).bodyValue(altered).exchange()
            .expectStatus().isBadRequest();
        // Replayed later than the tolerance, or signed ahead of it.
        sendRaw(body, now - 301, SECRET).expectStatus().isBadRequest();
        sendRaw(body, now + 301, SECRET).expectStatus().isBadRequest();
        // Unsigned.
        client.post().uri(INBOUND).contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange().expectStatus().isBadRequest();
        // Larger than a provider takes (1 MB), however signed.
        byte[] large = ("{\"id\":\"evt_large\",\"type\":\"checkout.session.completed\",\"pad\":\""
            + "x".repeat(1024 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        sendRaw(large, now, SECRET).expectStatus().isBadRequest();
        deliverEvents();
        assertThat(storedEvents((String) event.get("id"))).isZero();
        assertThat(storedEvents("evt_large")).isZero();
        assertThat(topUp(topUpId)).containsEntry("status", "OPEN");
        assertThat(postingsOf(topUpId)).isEmpty();
    }

    @Test
    void whatDoesNotFitIsATaskNotACredit() {
        String sponsor = newSponsor("s-idem-check-fit");
        String other = newSponsor("s-idem-other");
        record Case(String reason, java.util.function.Function<String, Map<String, Object>> event) {}
        List<Case> cases = List.of(
            new Case("AMOUNT_MISMATCH", id -> event(eventId(), "checkout.session.completed", completed(id,
                s -> s.put("amount_total", 1)))),
            new Case("CURRENCY", id -> event(eventId(), "checkout.session.completed", completed(id,
                s -> s.put("currency", "usd")))),
            new Case("NOT_PAID", id -> event(eventId(), "checkout.session.completed", completed(id,
                s -> s.put("payment_status", "unpaid")))),
            new Case("LIVEMODE", id -> {
                Map<String, Object> e = event(eventId(), "checkout.session.completed", completed(id));
                e.put("livemode", true);
                return e;
            }),
            // The metadata names another sponsor: the record's sponsor and lock are the record's.
            new Case("SESSION_MISMATCH", id -> event(eventId(), "checkout.session.completed", completed(id,
                s -> s.put("metadata", Map.of("topUpId", id, "sponsorId", other))))),
            new Case("SESSION_MISMATCH", id -> event(eventId(), "checkout.session.completed", completed(id,
                s -> s.put("id", "cs_test_forged")))));
        for (Case c : cases) {
            String topUpId = openTopUp(sponsor, 1000);
            Map<String, Object> event = c.event().apply(topUpId);
            deliver(event);
            assertThat(topUp(topUpId)).as(c.reason()).containsEntry("status", "OPEN")
                .containsEntry("check_reason", c.reason());
            assertThat(postingsOf(topUpId)).as(c.reason()).isEmpty();
            assertThat(tasksAbout("stripe:" + event.get("id"))).as(c.reason()).singleElement()
                .satisfies(task -> {
                    assertThat(task).containsEntry("title_key", "task.qb.topup-check." + c.reason())
                        .containsEntry("subject_id", topUpId);
                    assertThat((String) task.get("title_params")).contains(topUpId);
                });
            // Let it expire, so that the next case has room.
            deliver(event(eventId(), "checkout.session.expired", completed(topUpId, s -> {
                s.put("status", "expired");
                s.put("payment_status", "unpaid");
            })));
        }
        // A reference that is not a top-up's, or a forged one.
        for (String reference : List.of("not-a-top-up", "01890000-0000-7000-8000-00000000dead")) {
            Map<String, Object> session = completed(openTopUp(sponsor, 1000));
            session.put("client_reference_id", reference);
            Map<String, Object> event = event(eventId(), "checkout.session.completed", session);
            deliver(event);
            assertThat(tasksAbout("stripe:" + event.get("id"))).singleElement().satisfies(task -> assertThat(task)
                .containsEntry("title_key", "task.qb.topup-check.UNKNOWN_TOPUP")
                .containsEntry("subject_entity", "InboundEvent"));
        }
        // An expired top-up paid after all: not credited.
        String expired = openTopUp(other, 1000);
        deliver(event(eventId(), "checkout.session.expired", completed(expired, s -> {
            s.put("status", "expired");
            s.put("payment_status", "unpaid");
        })));
        assertThat(topUp(expired)).containsEntry("status", "EXPIRED");
        deliver(event(eventId(), "checkout.session.completed", completed(expired)));
        assertThat(topUp(expired)).containsEntry("status", "EXPIRED").containsEntry("check_reason", "STATUS");
        assertThat(postingsOf(expired)).isEmpty();
        assertThat(balance(sponsor)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance(other)).isEqualByComparingTo(BigDecimal.ZERO);
        assertBooksBalance();
    }

    @Test
    void theDatabaseRefusesASecondPostingOfATopUp() {
        String sponsor = newSponsor("s-idem-db");
        String topUpId = openTopUp(sponsor, 1000);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        String posting = (String) topUp(topUpId).get("ledger_transaction_id");
        String other = openTopUp(sponsor, 1000);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> execute(
            "UPDATE qb_topup SET status = 'PAID', ledger_transaction_id = ?, payment_intent_id = 'pi_x' "
                + "WHERE topup_id = ?", posting, other))).isNotNull();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> execute(
            "UPDATE qb_topup SET status = 'PAID' WHERE topup_id = ?", other))).isNotNull();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> execute(
            "UPDATE qb_topup SET payment_intent_id = ? WHERE topup_id = ?", paymentOf(topUpId), other)))
            .isNotNull();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> execute(
            "UPDATE qb_topup SET refunded_amount = 1001 WHERE topup_id = ?", topUpId))).isNotNull();
    }

    /** Delivers the due events from {@code threads} threads at once. */
    void deliverConcurrently(int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                runs.add(pool.submit(() -> deliverer.deliverPending().block()));
            }
            for (Future<?> run : runs) {
                try {
                    run.get();
                } catch (Exception e) {
                    // A delivery that lost the race failed and is retried: what counts is the outcome.
                }
            }
        } finally {
            pool.shutdown();
        }
    }
}
