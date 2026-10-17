package com.jabiz.app.it.security;

import com.jabiz.runtime.process.sponsor.SponsorSignInProcess;
import com.jabiz.runtime.security.PasswordHashingLimit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cap on password hashing through the sign-in endpoint (decision D47 item 2): with every permit taken, a sign-in
 * waits {@code max-wait} before its transaction and is then answered 503 {@code AUTH_BUSY} with {@code Retry-After},
 * having started no process and recorded no attempt (so the lock of the account is not affected); once a permit is
 * free it signs in as usual.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.password-hashing.max-concurrent=1", "jabiz.security.password-hashing.max-wait=PT0.3S"})
class PasswordHashingLimitIT extends SecurityItSupport {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    PasswordHashingLimit limit;

    @Test
    void aSignInWaitingTooLongIsBusyAndRecordsNothing() {
        String name = unique("hash-busy");
        String userId = userWith(name, PASSWORD, "p");
        int records = loginRecords(userId).size();
        long processes = ((Number) query("SELECT count(*) AS n FROM op_process").getFirst().get("n")).longValue();

        // Another sign-in holds the only permit (its transaction does not end while the test looks).
        Sinks.Empty<Void> end = Sinks.empty();
        Disposable holder = limit.around(SponsorSignInProcess.DEFINITION, end.asMono()).subscribe();
        try {
            Map<String, Object> problem = login(name, PASSWORD).expectStatus().isEqualTo(503)
                .expectHeader().value(HttpHeaders.RETRY_AFTER, value -> assertThat(Long.parseLong(value)).isPositive())
                .expectBody(MAP).returnResult().getResponseBody();
            assertThat(ruleCode(problem)).isEqualTo("AUTH_BUSY");
            assertThat(problem).containsKey("retryAfter");
            assertThat(loginRecords(userId)).hasSize(records);
            assertThat(((Number) query("SELECT count(*) AS n FROM op_process").getFirst().get("n")).longValue())
                .isEqualTo(processes);
        } finally {
            end.tryEmitEmpty();
            holder.dispose();
        }
        login(name, PASSWORD).expectStatus().isOk();
        assertThat(loginRecords(userId)).hasSize(records + 1);
    }

    /**
     * The hashing itself is capped too, whoever reaches it: with the only permit of the computation taken (a run the
     * queue before the transaction did not see), a sign-in waits {@code max-wait} inside its transaction and is then
     * 503 {@code AUTH_BUSY}; it rolls back, so neither a login record nor a failure is counted.
     */
    @Test
    void theComputationItselfIsCappedAndABusySignInCountsNoFailure() throws Exception {
        String name = unique("hash-compute");
        String userId = userWith(name, PASSWORD, "p");
        login(name, "wrong password!").expectStatus().isUnauthorized();
        assertThat(loginRecords(userId)).singleElement().satisfies(r ->
            assertThat(((Number) r.get("failure_count")).intValue()).isEqualTo(1));

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            threads.submit(() -> limit.compute(() -> {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                Map<String, Object> problem = login(name, "wrong password!").expectStatus().isEqualTo(503)
                    .expectHeader().value(HttpHeaders.RETRY_AFTER, value -> assertThat(Long.parseLong(value))
                        .isPositive())
                    .expectBody(MAP).returnResult().getResponseBody();
                assertThat(ruleCode(problem)).isEqualTo("AUTH_BUSY");
                assertThat(loginRecords(userId)).hasSize(1);
            } finally {
                release.countDown();
            }
        }
        login(name, "wrong password!").expectStatus().isUnauthorized();
        assertThat(loginRecords(userId)).hasSize(2).last().satisfies(r ->
            assertThat(((Number) r.get("failure_count")).intValue()).isEqualTo(2));
    }
}
