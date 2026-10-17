package com.jabiz.app.it.security;

import com.jabiz.runtime.process.sponsor.SponsorSignInProcess;
import com.jabiz.runtime.security.PasswordHashingLimit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.Map;

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

        // Another sign-in holds the only permit (its transaction never ends while the test looks).
        Disposable holder = limit.around(SponsorSignInProcess.DEFINITION, Mono.never()).subscribe();
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
            holder.dispose();
        }
        login(name, PASSWORD).expectStatus().isOk();
        assertThat(loginRecords(userId)).hasSize(records + 1);
    }
}
