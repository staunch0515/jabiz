package com.jabiz.runtime.security;

import com.jabiz.runtime.test.TestAuthenticator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checks of passkey answers (decision D71): a registration and assertions answering the challenge from a
 * configured origin with the user verified hold; another challenge, origin, relying party or user, an unverified user,
 * a counter that went back and a changed signature do not.
 */
class PasskeyVerifierTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID USER = UUID.fromString("0190f1a2-0000-7000-8000-000000000001");
    private static final String CHALLENGE = "c2VydmVyLWNoYWxsZW5nZS0xMjM0NTY3ODkwMTIzNDU2";

    private final PasskeyVerifier verifier = new PasskeyVerifier(new PasskeyProperties("localhost", "Tests",
        List.of(TestAuthenticator.ORIGIN), null, null));
    private final TestAuthenticator authenticator = new TestAuthenticator();

    private Map<String, Object> creation(String challenge) {
        return verifier.creationOptions(challenge, USER, "ann@example.com", "Ann", List.of());
    }

    private static String json(Map<String, Object> value) {
        return JSON.writeValueAsString(value);
    }

    private PasskeyVerifier.Registered registered() {
        return verifier.register(json(authenticator.create(creation(CHALLENGE), TestAuthenticator.Answer.right())),
            CHALLENGE);
    }

    private Map<String, Object> assertion(String challenge, TestAuthenticator.Answer answer) {
        return authenticator.get(verifier.requestOptions(challenge, List.of()), answer);
    }

    @Test
    void theOptionsAskForADiscoverableVerifiedPasskey() {
        Map<String, Object> options = verifier.creationOptions(CHALLENGE, USER, "ann@example.com", null,
            List.of(new PasskeyVerifier.StoredCredential("abc", "internal,hybrid")));
        assertThat(options).containsEntry("challenge", CHALLENGE).containsEntry("attestation", "none")
            .containsEntry("timeout", 300_000L)
            .containsEntry("user", Map.of("id", PasskeyVerifier.userHandle(USER), "name", "ann@example.com",
                "displayName", "ann@example.com"))
            .containsEntry("excludeCredentials", List.of(Map.of("type", "public-key", "id", "abc",
                "transports", List.of("internal", "hybrid"))));
        assertThat(options.get("pubKeyCredParams")).isEqualTo(List.of(Map.of("type", "public-key", "alg", -7L),
            Map.of("type", "public-key", "alg", -8L), Map.of("type", "public-key", "alg", -257L)));
        assertThat(verifier.requestOptions(CHALLENGE, List.of())).containsEntry("rpId", "localhost")
            .containsEntry("userVerification", "required").containsEntry("allowCredentials", List.of());
        assertThat(PasskeyVerifier.userHandle(USER)).hasSize(22);
    }

    @Test
    void aRegistrationAndItsAssertionsHold() {
        PasskeyVerifier.Registered registered = registered();
        assertThat(registered.credentialId()).isEqualTo(authenticator.last().id());
        assertThat(registered.backedUp()).isTrue();
        assertThat(registered.transports()).isEqualTo("hybrid,internal");
        assertThat(registered.aaguid()).isEqualTo("00000000-0000-0000-0000-000000000000");
        assertThat(registered.toString()).doesNotContain(registered.credentialData());

        Map<String, Object> answer = assertion("Y2hhbGxlbmdlLTI", TestAuthenticator.Answer.right());
        assertThat(PasskeyVerifier.claimed(json(answer)))
            .isEqualTo(new PasskeyVerifier.Claimed(registered.credentialId(), "Y2hhbGxlbmdlLTI"));
        PasskeyVerifier.Asserted asserted = verifier.authenticate(json(answer), "Y2hhbGxlbmdlLTI",
            registered.credentialData(), registered.signCount(), USER);
        assertThat(asserted).isEqualTo(new PasskeyVerifier.Asserted(1, true));
        PasskeyVerifier.Asserted next = verifier.authenticate(json(assertion("Y2hhbGxlbmdlLTM",
            TestAuthenticator.Answer.right().deviceBound())), "Y2hhbGxlbmdlLTM", registered.credentialData(), 1, USER);
        assertThat(next).isEqualTo(new PasskeyVerifier.Asserted(2, false));
    }

    @Test
    void registrationsThatDoNotHoldAreRefused() {
        String other = "b3RoZXItY2hhbGxlbmdl";
        for (Map<String, Object> wrong : List.of(
            authenticator.create(creation(CHALLENGE), TestAuthenticator.Answer.right().origin("https://evil.example")),
            authenticator.create(creation(CHALLENGE), TestAuthenticator.Answer.right().unverified()),
            authenticator.create(creation(other), TestAuthenticator.Answer.right()))) {
            assertThatThrownBy(() -> verifier.register(json(wrong), CHALLENGE))
                .isInstanceOf(PasskeyVerifier.InvalidPasskeyException.class);
        }
        Map<String, Object> options = creation(CHALLENGE);
        options.put("rp", Map.of("id", "example.com", "name", "x"));
        assertThatThrownBy(() -> verifier.register(json(authenticator.create(options,
            TestAuthenticator.Answer.right())), CHALLENGE)).isInstanceOf(PasskeyVerifier.InvalidPasskeyException.class);
        for (String garbage : java.util.Arrays.asList(null, "", "[]", "{\"id\":1}", "x".repeat(20_000))) {
            assertThatThrownBy(() -> verifier.register(garbage, CHALLENGE))
                .isInstanceOf(PasskeyVerifier.InvalidPasskeyException.class);
        }
    }

    @Test
    void assertionsThatDoNotHoldAreRefused() {
        PasskeyVerifier.Registered registered = registered();
        String challenge = "Y2hhbGxlbmdlLTQ";
        record Case(String what, Map<String, Object> answer, String challenge, long stored, UUID user) {}
        List<Case> cases = List.of(
            new Case("another origin", assertion(challenge, TestAuthenticator.Answer.right().origin(
                "https://evil.example")), challenge, 0, USER),
            new Case("no user verification", assertion(challenge, TestAuthenticator.Answer.right().unverified()),
                challenge, 0, USER),
            new Case("another challenge", assertion("b3RoZXI", TestAuthenticator.Answer.right()), challenge, 0, USER),
            new Case("another user's handle", assertion(challenge, TestAuthenticator.Answer.right()), challenge, 0,
                UUID.randomUUID()),
            new Case("a counter that went back", assertion(challenge, TestAuthenticator.Answer.right().counter(3)),
                challenge, 5, USER));
        for (Case c : cases) {
            assertThatThrownBy(() -> verifier.authenticate(json(c.answer()), c.challenge(), registered.credentialData(),
                c.stored(), c.user())).as(c.what()).isInstanceOf(PasskeyVerifier.InvalidPasskeyException.class);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> tampered = assertion(challenge, TestAuthenticator.Answer.right());
        @SuppressWarnings("unchecked")
        Map<String, Object> response = new java.util.LinkedHashMap<>((Map<String, Object>) tampered.get("response"));
        response.put("signature", "MEUCIQ" + "A".repeat(60));
        tampered.put("response", response);
        assertThatThrownBy(() -> verifier.authenticate(json(tampered), challenge, registered.credentialData(), 0, USER))
            .isInstanceOf(PasskeyVerifier.InvalidPasskeyException.class);
    }

    @Test
    void claimsAreReadBeforeAnythingIsChecked() {
        assertThat(PasskeyVerifier.canonical("YWJj==".replace("=", ""))).isEqualTo("YWJj");
        for (String garbage : List.of("{}", "{\"id\":\"YQ\",\"type\":\"public-key\"}",
            "{\"id\":\"YQ\",\"type\":\"other\",\"response\":{\"clientDataJSON\":\"e30\"}}",
            "{\"id\":\"YQ\",\"type\":\"public-key\",\"response\":{\"clientDataJSON\":\"e30\"}}",
            "{\"id\":\"%%\",\"type\":\"public-key\",\"response\":{\"clientDataJSON\":\"eyJjaGFsbGVuZ2UiOiJ4In0\"}}",
            "{\"id\":\"YQ\",\"type\":\"public-key\",\"response\":{\"clientDataJSON\":\"!!\"}}")) {
            assertThatThrownBy(() -> PasskeyVerifier.claimed(garbage)).as(garbage)
                .isInstanceOf(PasskeyVerifier.InvalidPasskeyException.class);
        }
    }

    @Test
    void namesAreKeptShortAndPrintable() {
        assertThat(PasskeyProcesses.name(null)).isEqualTo("Passkey");
        assertThat(PasskeyProcesses.name(" \u0007​ ")).isEqualTo("Passkey");
        assertThat(PasskeyProcesses.name("  Ann's ‮phone ")).isEqualTo("Ann's phone");
        assertThat(PasskeyProcesses.name("通".repeat(150))).hasSize(100);
    }
}
