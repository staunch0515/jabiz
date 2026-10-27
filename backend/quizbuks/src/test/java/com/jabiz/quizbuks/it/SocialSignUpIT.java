package com.jabiz.quizbuks.it;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestOidcProvider;
import com.nimbusds.jose.JWSAlgorithm;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing up through LINE at the app and at the sponsor console (docs/quizbuks/plans/Q40-social-sign-in.md, D-Q40-2,
 * D-Q40-3; platform decision D61), against a local stand-in for LINE: LINE does not vouch for the address, so the
 * account opens unverified with the entry's role and the verification mail goes out; the person is signed in at once
 * (both entries take unverified addresses), every QuizBuks write waits for the address, and the link verifies it in the
 * account's own session, keeping the account and its LINE link. An address in use opens nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=quizbuks@example.com",
    "jabiz.mail.base-url=https://quizbuks.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3051",
    "jabiz.security.entries.app.self-registration=true", "jabiz.security.entries.sponsor.self-registration=true",
    "jabiz.security.sign-up.rate-limit.per-address=1000", "jabiz.security.sign-up.rate-limit.per-email=100"})
class SocialSignUpIT extends AccountItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3051, null,
        ServerSetup.PROTOCOL_SMTP));

    /** A LINE channel secret: the HS256 key of LINE's ID tokens. */
    static final String SECRET = "0123456789abcdef0123456789abcdef";
    static final TestOidcProvider LINE = new TestOidcProvider(SECRET);
    static final Map<String, String> CALLBACKS = Map.of("app", "http://localhost/taker/login/oidc",
        "sponsor", "http://localhost/sponsor/login/oidc");
    private static final Pattern VERIFY_LINK = Pattern.compile(
        "https://quizbuks\\.example\\.com/(?:sponsor/|taker/)auth/verify-email\\?entry=[a-z]+&token=([A-Za-z0-9_-]+)");

    @Autowired
    Clock clock;

    @DynamicPropertySource
    static void line(DynamicPropertyRegistry registry) {
        String prefix = "jabiz.security.oidc.providers[0].";
        registry.add(prefix + "id", () -> "line");
        registry.add(prefix + "kind", () -> "line");
        registry.add(prefix + "issuer", LINE::issuer);
        registry.add(prefix + "client-id", () -> TestOidcProvider.CLIENT_ID);
        registry.add(prefix + "client-secret", () -> SECRET);
        CALLBACKS.forEach((entry, uri) -> registry.add(prefix + "redirect-uris." + entry, () -> uri));
        registry.add(prefix + "labels.en", () -> "LINE");
        registry.add(prefix + "auto-provision", () -> "true");
        registry.add(prefix + "sign-up-unverified-email", () -> "true");
    }

    @AfterAll
    static void stop() {
        LINE.close();
    }

    @AfterEach
    void onlyInserted() {
        assertAccountsOnlyInserted();
    }

    private WebTestClient.ResponseSpec post(String path, String bearer, Map<String, ?> body) {
        WebTestClient.RequestBodySpec request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.ACCEPT_LANGUAGE, "en");
        if (bearer != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return request.bodyValue(body).exchange();
    }

    /** Signs in through LINE at the entry, as LINE's web sign-in does it: HS256, an address, no email_verified. */
    private WebTestClient.ResponseSpec throughLine(String entry, String subject, String email) {
        Map<String, Object> started = post("/api/auth/oidc/line/start?entry=" + entry, null, Map.of())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        Map<String, String> query = TestOidcProvider.query((String) started.get("authorizationUrl"));
        String idToken = TestOidcProvider.sign(LINE.claims(subject, query.get("nonce"), clock.instant())
            .claim("email", email).claim("name", "Line Person").build(), JWSAlgorithm.HS256,
            SECRET.getBytes(StandardCharsets.UTF_8), false);
        return post("/api/auth/oidc/callback", null, Map.of("state", query.get("state"), "binder",
            started.get("binder"), "code", LINE.code(query.get("code_challenge"), idToken, CALLBACKS.get(entry))));
    }

    private static String address(String prefix) {
        return unique(prefix).toLowerCase() + "@example.com";
    }

    private static Map<String, Object> userOf(String email) {
        return query("SELECT DISTINCT ON (user_id) user_id::text AS user_id, registered_email, verified_email FROM "
            + "sec_user_version WHERE lower(email) = lower(?) ORDER BY user_id, version_no DESC", email).getFirst();
    }

    private static String linkedUser(String subject) {
        List<Map<String, Object>> rows = query("SELECT DISTINCT ON (user_identity_id) user_id::text AS user_id, "
            + "is_deleted FROM sec_user_identity_version WHERE provider = 'line' AND subject = ? "
            + "ORDER BY user_identity_id, version_no DESC", subject);
        return rows.isEmpty() || Boolean.TRUE.equals(rows.getFirst().get("is_deleted")) ? null
            : String.valueOf(rows.getFirst().get("user_id"));
    }

    private static List<String> roleCodesOf(String userId) {
        return query("""
            SELECT r.role_code FROM (SELECT DISTINCT ON (user_role_id) role_id, is_deleted FROM sec_user_role_version
                WHERE user_id = ?::uuid ORDER BY user_role_id, version_no DESC) a
            JOIN (SELECT DISTINCT ON (role_id) role_id, role_code FROM sec_role_version
                ORDER BY role_id, version_no DESC) r ON r.role_id = a.role_id
            WHERE NOT a.is_deleted ORDER BY r.role_code""", userId).stream()
            .map(row -> String.valueOf(row.get("role_code"))).toList();
    }

    /** The token of the latest verification link mailed to the address. */
    private static String verificationToken(String email) {
        List<MimeMessage> mail = Arrays.stream(MAIL.getReceivedMessages()).filter(message -> {
            try {
                return message.getAllRecipients()[0].toString().equalsIgnoreCase(email);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        for (int i = mail.size() - 1; i >= 0; i--) {
            Matcher matcher = VERIFY_LINK.matcher(GreenMailUtil.getBody(mail.get(i)).replace("=\r\n", ""));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        throw new AssertionError("No verification link in the mail to " + email);
    }

    /** Opens an account through LINE at the entry, verifies it in its own session; the renewed session. */
    private Map<String, Object> openAndVerify(String entry, String subject, String email, String role,
        String process, Map<String, Object> input) {
        Map<String, Object> session = throughLine(entry, subject, email).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(session).containsEntry("status", "SIGNED_IN");
        Map<String, Object> user = userOf(email);
        String userId = String.valueOf(user.get("user_id"));
        // Unverified and pre-claimable, with the entry's role and the link.
        assertThat(user).containsEntry("registered_email", email).containsEntry("verified_email", null);
        assertThat(roleCodesOf(userId)).containsExactly(role);
        assertThat(linkedUser(subject)).isEqualTo(userId);
        // Signed in, but nothing QuizBuks keeps is written for an address nobody proved.
        assertThat(refusalCode(post("/api/processes/" + process + "/latest", bearerOf(session), input)
            .expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("EMAIL_NOT_VERIFIED");
        // The mailed link, opened where the account is signed in: verified without a password, LINE's link kept.
        deliverEvents();
        String token = verificationToken(email);
        assertThat(post("/api/auth/account/verify-email/check", bearerOf(session), Map.of("token", token))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody())
            .containsEntry("passwordRequired", false);
        post("/api/auth/account/verify-email/confirm", bearerOf(session), Map.of("token", token)).expectStatus()
            .isNoContent();
        assertThat(userOf(email)).containsEntry("verified_email", email);
        assertThat(linkedUser(subject)).isEqualTo(userId);
        return post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken"), "entry", entry))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    @Test
    void aTakerSignsUpThroughLineAndCompletesTheProfileOnceVerifiedInTheApp() {
        String email = address("line-taker");
        Map<String, Object> profile = body("displayName", "Lin", "countryCode", "JP", "preferredCurrency", "JPY");
        Map<String, Object> renewed = openAndVerify("app", unique("sub"), email, QbRoles.TAKER, "QB_PROFILE_SAVE",
            profile);
        assertThat(run("QB_PROFILE_SAVE", bearerOf(renewed), profile)).containsEntry("created", true);
    }

    @Test
    void aSponsorSignsUpThroughLineIsSignedInUnverifiedAndAppliesOnceVerified() {
        String email = address("line-sponsor");
        Map<String, Object> application = body("displayName", "Lin", "company", "Line Quiz", "acceptTerms", true);
        Map<String, Object> renewed = openAndVerify("sponsor", unique("sub"), email, QbRoles.SPONSOR,
            "QB_SPONSOR_APPLY", application);
        assertThat(run("QB_SPONSOR_APPLY", bearerOf(renewed), application))
            .containsEntry("reviewStatus", SponsorStatus.PENDING);
    }

    @Test
    void anAddressInUseOpensNothingAndSaysHowToGoOn() {
        String email = address("taken");
        post("/api/auth/register", null, Map.of("entry", "app", "email", email, "password", PASSWORD))
            .expectStatus().isAccepted();
        String subject = unique("sub");
        assertThat(refusalCode(throughLine("sponsor", subject, email).expectStatus().isForbidden().expectBody(MAP)
            .returnResult().getResponseBody())).isEqualTo("OIDC_ACCOUNT_EXISTS");
        assertThat(linkedUser(subject)).isNull();
    }
}
