package com.jabiz.app.it.security;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.security.SecurityPermissions;
import com.jabiz.runtime.security.rotation.KeyRotationProcesses;
import com.jabiz.runtime.security.rotation.MfaReenrollment;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.security.Base32;
import com.jabiz.security.Totp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Everyone sets up a new second factor" (decision D78; ROADMAP 17zw): one administrator asks, another approves within
 * a day; then the job takes back every second factor confirmed before the approval and ends its sessions, and the next
 * sign-in asks for a new one; a second factor set up after the approval stays.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class MfaReenrollmentIT extends SecurityItSupport {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    ProcessExecutor processes;

    private String administrator(String actor) {
        return TestTokens.bearer(tokens, actor, SecurityPermissions.MFA_RESET);
    }

    private KeyRotationProcesses.RekeyOutput rekey() {
        return processes.run(KeyRotationProcesses.REKEY, new KeyRotationProcesses.RekeyInput(null),
                ExecutionOptions.NONE)
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it-reenroll")))
            .block().output();
    }

    private String code(String secret) {
        return Totp.code(Base32.decode(secret), Totp.step(clock.instant()));
    }

    private Map<String, Object> process(String name, String authorization, Map<String, Object> input, int status) {
        return post("/api/processes/" + name + "/latest", authorization, input).expectStatus().isEqualTo(status)
            .expectBody(MAP).returnResult().getResponseBody();
    }

    @Test
    void anotherAdministratorApprovesAndTheNextSignInAsksForANewSecondFactor() {
        String name = unique("reenrol");
        userWith(name, PASSWORD, "p");
        String bearer = bearerOf(signIn(name, PASSWORD));
        String secret = (String) post("/api/auth/mfa/enroll", bearer, Map.of()).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("secret");
        post("/api/auth/mfa/enroll/confirm", bearer, Map.of("code", code(secret))).expectStatus().isOk();
        clock.advance(Duration.ofSeconds(30));
        Map<String, Object> challenged = signIn(name, PASSWORD);
        assertThat(challenged).containsEntry("status", "MFA_REQUIRED");
        Map<String, Object> session = post("/api/auth/challenge/verify", null, Map.of("challenge",
            challenged.get("challenge"), "code", code(secret))).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        clock.advance(Duration.ofMinutes(1));

        // One asks, with a reason; only one request waits at a time.
        String requestId = (String) ((Map<?, ?>) process(MfaReenrollment.REQUEST, administrator("it-admin-a"),
            Map.of("reason", "The MFA key leaked with a database copy"), 200).get("output")).get("requestId");
        assertThat(ruleCode(process(MfaReenrollment.REQUEST, administrator("it-admin-b"), Map.of("reason", "again"),
            422))).isEqualTo("MFA_REENROLLMENT_OPEN");
        // The asker cannot approve; another administrator does, once.
        assertThat(ruleCode(process(MfaReenrollment.APPROVE, administrator("it-admin-a"), Map.of("requestId",
            requestId), 422))).isEqualTo("APPROVAL_OWN_REQUEST");
        assertThat(rekey().secondFactorsTakenBack()).as("nothing before the approval").isZero();
        process(MfaReenrollment.APPROVE, administrator("it-admin-b"), Map.of("requestId", requestId), 200);
        assertThat(ruleCode(process(MfaReenrollment.APPROVE, administrator("it-admin-c"), Map.of("requestId",
            requestId), 422))).isEqualTo("MFA_REENROLLMENT_NOT_PENDING");

        // The job takes the second factor back and ends the user's sessions.
        clock.advance(Duration.ofMinutes(1));
        assertThat(rekey().secondFactorsTakenBack()).isEqualTo(1);
        post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken"))).expectStatus()
            .isUnauthorized();
        // The old code no longer signs in: the sign-in asks for a new second factor first.
        Map<String, Object> asked = signIn(name, PASSWORD);
        assertThat(asked).containsEntry("status", "MFA_ENROLLMENT_REQUIRED").containsEntry("accessToken", null);
        String challenge = (String) asked.get("challenge");
        String renewed = (String) post("/api/auth/challenge/enroll", null, Map.of("challenge", challenge))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("secret");
        assertThat(renewed).isNotEqualTo(secret);
        post("/api/auth/challenge/enroll/confirm", null, Map.of("challenge", challenge, "code", code(renewed)))
            .expectStatus().isOk();
        clock.advance(Duration.ofSeconds(30));
        // Set up after the approval: it stays, and the sign-in is as before.
        assertThat(rekey().secondFactorsTakenBack()).isZero();
        assertThat(signIn(name, PASSWORD)).containsEntry("status", "MFA_REQUIRED");

        // A request nobody approves within a day expires.
        String late = (String) ((Map<?, ?>) process(MfaReenrollment.REQUEST, administrator("it-admin-a"),
            Map.of("reason", "Another one"), 200).get("output")).get("requestId");
        clock.advance(MfaReenrollment.EXPIRY.plusMinutes(1));
        assertThat(ruleCode(process(MfaReenrollment.APPROVE, administrator("it-admin-b"), Map.of("requestId", late),
            422))).isEqualTo("MFA_REENROLLMENT_NOT_PENDING");
    }
}
