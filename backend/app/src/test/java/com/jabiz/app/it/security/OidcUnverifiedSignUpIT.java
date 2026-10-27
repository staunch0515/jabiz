package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.runtime.test.TestOidcProvider;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A LINE provider and accounts opened for addresses a provider does not vouch for (docs/design/10-security.md
 * section 12; decision D60; ROADMAP phase 16zk): the ID token keyed with the client secret (HS256) and the client's
 * credentials in the token request's form; the account unverified and pre-claimable, with a verification mail;
 * verifying in the account's own session keeps it and its link, a link used by anybody else takes it over and the link
 * goes. HS256 counts for LINE only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3032", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE, OidcItSupport.STRICT_ACCEPTS,
    OidcItSupport.STRICT_REGISTERS, OidcItSupport.STRICT_GRANTS})
@Import(SignUpItSupport.FixedTimeLimits.class)
class OidcUnverifiedSignUpIT extends OidcItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3032, null, ServerSetup.PROTOCOL_SMTP));

    static final String LINE = "line";
    static final String SECRET = "a-line-channel-secret-thirty-two";
    static final TestOidcProvider IDP = new TestOidcProvider();
    static final TestOidcProvider LINE_IDP = new TestOidcProvider(SECRET);

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        providers(registry, IDP);
        // After the four of OidcItSupport.
        String prefix = "jabiz.security.oidc.providers[4].";
        registry.add(prefix + "id", () -> LINE);
        registry.add(prefix + "kind", () -> "line");
        registry.add(prefix + "issuer", LINE_IDP::issuer);
        registry.add(prefix + "client-id", () -> TestOidcProvider.CLIENT_ID);
        registry.add(prefix + "client-secret", () -> SECRET);
        for (String entry : List.of(PORTAL, LOOSE)) {
            registry.add(prefix + "redirect-uris." + entry, () -> callbackOf(entry));
        }
        registry.add(prefix + "labels.en", () -> "LINE");
        registry.add(prefix + "auto-provision", () -> "true");
        registry.add(prefix + "sign-up-unverified-email", () -> "true");
    }

    @AfterAll
    static void stop() {
        IDP.close();
        LINE_IDP.close();
    }

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    @Override
    TestOidcProvider idp() {
        return IDP;
    }

    /** A token as LINE's web sign-in makes it: HS256 with the channel secret, an address and no email_verified. */
    String lineToken(Request request, String subject, UnaryOperator<JWTClaimsSet.Builder> change) {
        return TestOidcProvider.sign(change.apply(LINE_IDP.claims(subject, request.nonce(), clock.instant())).build(),
            JWSAlgorithm.HS256, SECRET.getBytes(StandardCharsets.UTF_8), false);
    }

    WebTestClient.ResponseSpec lineCallback(Request request, String idToken) {
        Map<String, Object> body = new HashMap<>();
        body.put("state", request.state());
        body.put("code", LINE_IDP.code(request.challenge(), idToken, request.redirectUri()));
        body.put("binder", request.binder());
        return anonymous("/api/auth/oidc/callback", body);
    }

    /** Opens an account through LINE at the portal (which then wants the address verified: 403); its user id. */
    String openThroughLine(String subject, String email) {
        Request request = start(LINE, PORTAL);
        assertThat(refusal(lineCallback(request, lineToken(request, subject, c -> c.claim("email", email)
            .claim("name", "Line User"))), 403)).isEqualTo("EMAIL_NOT_VERIFIED");
        return (String) userOf(email).get("user_id");
    }

    /** Signs in through LINE at the entry that takes unverified addresses; the access token. */
    String signInAtLoose(String subject, String email) {
        Request request = start(LINE, LOOSE);
        return "Bearer " + lineCallback(request, lineToken(request, subject, c -> c.claim("email", email)))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("accessToken");
    }

    @Test
    void anAddressLineDoesNotVouchForOpensAPreClaimableAccountWithAVerificationMail() {
        String subject = unique("sub");
        String email = address("line");
        String userId = openThroughLine(subject, email);

        // Unverified, pre-claimable as a self-registration, no password; linked and with the entry's roles.
        assertThat(userOf(email)).containsEntry("email", email).containsEntry("registered_email", email)
            .containsEntry("verified_email", null).containsEntry("email_verified_at", null)
            .containsEntry("password_hash", null);
        assertThat(linkedUser(LINE, subject)).isEqualTo(userId);
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);
        // LINE took the client's credentials in the form (decision D60 item 1).
        assertThat(LINE_IDP.clientAuthentication()).isEqualTo("post");
        // The verification mail went out, as for a self-registration.
        deliver();
        assertThat(token(VERIFY_LINK, email)).isNotBlank();
        // The next sign-in through LINE finds the link: no second account.
        Request again = start(LINE, PORTAL);
        assertThat(refusal(lineCallback(again, lineToken(again, subject, c -> c.claim("email", email))), 403))
            .isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(usersWith(email)).isEqualTo(1);
    }

    @Test
    void verifyingInTheAccountsOwnSessionKeepsTheAccountAndItsLink() {
        String subject = unique("sub");
        String email = address("own");
        String userId = openThroughLine(subject, email);
        deliver();
        String link = token(VERIFY_LINK, email);
        // Anonymously the link would need a password (the account is pre-claimable); in its own session it does not.
        assertThat(anonymous("/api/auth/verify-email/check", Map.of("token", link)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody()).containsEntry("passwordRequired", true);
        String bearer = signInAtLoose(subject, email);
        assertThat(authorized("POST", "/api/auth/account/verify-email/check", bearer, Map.of("token", link))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody())
            .containsEntry("passwordRequired", false);
        authorized("POST", "/api/auth/account/verify-email/confirm", bearer, Map.of("token", link)).expectStatus().isNoContent();

        assertThat(userOf(email)).containsEntry("verified_email", email).containsEntry("password_hash", null);
        assertThat(linkedUser(LINE, subject)).isEqualTo(userId);
        // Verified: the portal signs it in now.
        Request request = start(LINE, PORTAL);
        lineCallback(request, lineToken(request, subject, c -> c.claim("email", email))).expectStatus().isOk();
    }

    @Test
    void anybodyElseUsingTheLinkTakesTheAccountOverAndTheLinkGoes() {
        String subject = unique("sub");
        String email = address("taken");
        openThroughLine(subject, email);
        deliver();
        String link = token(VERIFY_LINK, email);
        // Without a session and without a password nothing is proven.
        assertThat(refusal(anonymous("/api/auth/verify-email", Map.of("token", link)), 422)).isEqualTo("REQUIRED");
        // Another user's session is not the account's.
        String otherSubject = unique("sub");
        String otherEmail = address("other");
        openThroughLine(otherSubject, otherEmail);
        String other = signInAtLoose(otherSubject, otherEmail);
        assertThat(refusal(authorized("POST", "/api/auth/account/verify-email/confirm", other,
            Map.of("token", link)), 422)).isEqualTo("REQUIRED");
        // The mailbox's owner sets a password: the account is theirs, LINE's link is gone.
        anonymous("/api/auth/verify-email", Map.of("token", link, "newPassword", PASSWORD)).expectStatus()
            .isNoContent();
        assertThat(linkedUser(LINE, subject)).isNull();
        assertThat(userOf(email)).containsEntry("verified_email", email);
        loginTo(email, PASSWORD, PORTAL).expectStatus().isOk();
    }

    @Test
    void anAddressInUseOrNoAddressOpensNothing() {
        String taken = address("in-use");
        register(PORTAL, taken, PASSWORD).expectStatus().isAccepted();
        Request inUse = start(LINE, PORTAL);
        assertThat(refusal(lineCallback(inUse, lineToken(inUse, unique("sub"), c -> c.claim("email", taken))), 403))
            .isEqualTo("OIDC_ACCOUNT_EXISTS");
        assertThat(usersWith(taken)).isEqualTo(1);
        Request without = start(LINE, PORTAL);
        String subject = unique("sub");
        assertThat(refusal(lineCallback(without, lineToken(without, subject, c -> c)), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(linkedUser(LINE, subject)).isNull();
    }

    @Test
    void hs256CountsForLineOnlyAndOtherProvidersStillOpenOnlyForVerifiedAddresses() {
        // A generic provider's HS256 token (keyed with its client secret) is refused.
        Request request = start(OPEN, PORTAL);
        String email = address("hs256");
        String token = TestOidcProvider.sign(IDP.claims(unique("sub"), request.nonce(), clock.instant())
            .claim("email", email).claim("email_verified", true).build(), JWSAlgorithm.HS256,
            "a-generic-secret-of-thirty-two-bytes".getBytes(StandardCharsets.UTF_8), false);
        assertThat(refusal(callback(request, token), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(usersWith(email)).isZero();
        // A provider that does not take unverified addresses still opens nothing for one.
        Request unverified = start(OPEN, PORTAL);
        String other = address("unvouched");
        assertThat(refusal(callback(unverified, idToken(unverified, unique("sub"), c -> c.claim("email", other))),
            401)).isEqualTo("LOGIN_FAILED");
        assertThat(usersWith(other)).isZero();
        // The generic provider kept Basic authentication.
        assertThat(IDP.clientAuthentication()).isEqualTo("basic");
        // A LINE token signed with another secret is refused.
        Request forged = start(LINE, PORTAL);
        String bad = TestOidcProvider.sign(LINE_IDP.claims(unique("sub"), forged.nonce(), clock.instant())
            .claim("email", address("forged")).build(), JWSAlgorithm.HS256,
            "another-secret-of-thirty-two-bytes!".getBytes(StandardCharsets.UTF_8), false);
        assertThat(refusal(lineCallback(forged, bad), 401)).isEqualTo("LOGIN_FAILED");
    }
}
