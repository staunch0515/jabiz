package com.jabiz.quizbuks.it;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.setup.QbRoles;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registration at the three entries against a local SMTP server (U-02, U-06, U-07, M-01, M-02; docs/quizbuks/plans/
 * Q2-accounts.md, D-Q2-1, D-Q2-7): the platform registers, verifies and joins; QuizBuks's entries decide who signs in
 * where, every QuizBuks write needs a verified address, and an address somebody registered for another person leaves
 * no QuizBuks data. QuizBuks's own mail (welcome, onboarding) goes out through the platform's mail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=quizbuks@example.com",
    "jabiz.mail.base-url=https://quizbuks.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3041",
    "jabiz.security.entries.app.self-registration=true", "jabiz.security.entries.sponsor.self-registration=true",
    "jabiz.security.sign-up.rate-limit.per-address=1000", "jabiz.security.sign-up.rate-limit.per-email=100"})
class AccountSignUpIT extends AccountItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3041, null,
        ServerSetup.PROTOCOL_SMTP));

    // Links go to the frontend of the entry they belong to (decision D43 item 4): the app at /taker/, sponsors at /sponsor/,
    // the administration at /admin/.
    private static final Pattern VERIFY_LINK = Pattern.compile(
        "https://quizbuks\\.example\\.com/((?:admin/|sponsor/|taker/)?)auth/verify-email\\?entry=([a-z0-9-]+)&token=([A-Za-z0-9_-]+)");
    private static final Pattern JOIN_LINK = Pattern.compile(
        "https://quizbuks\\.example\\.com/((?:admin/|sponsor/|taker/)?)auth/join\\?entry=([a-z0-9-]+)&token=([A-Za-z0-9_-]+)");
    private static final Map<String, String> ENTRY_ROOTS = Map.of("app", "taker/", "sponsor", "sponsor/", "admin", "admin/");
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    /** Anonymous requests, each from an address of its own (the mock server has no connections). */
    private WebTestClient web;

    @BeforeEach
    void web() {
        int n = ADDRESSES.incrementAndGet();
        String address = "198.51." + (100 + n / 250) + "." + (1 + n % 250);
        WebFilter connection = (exchange, chain) -> chain.filter(exchange.mutate().request(request -> request
            .remoteAddress(new InetSocketAddress(address, 40000))).build());
        web = WebTestClient.bindToApplicationContext(context).webFilter(connection).configureClient()
            .responseTimeout(Duration.ofSeconds(60)).defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en").build();
    }

    @AfterEach
    void onlyInserted() {
        assertAccountsOnlyInserted();
    }

    private WebTestClient.ResponseSpec anonymous(String path, Map<String, ?> body) {
        return web.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private void register(String entry, String email) {
        anonymous("/api/auth/register", Map.of("entry", entry, "email", email, "password", PASSWORD))
            .expectStatus().isAccepted();
        deliverEvents();
    }

    private WebTestClient.ResponseSpec signIn(String email, String entry) {
        return anonymous("/api/auth/login", Map.of("userName", email, "password", PASSWORD, "entry", entry));
    }

    private Map<String, Object> sessionAt(String email, String entry) {
        return signIn(email, entry).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private Map<String, Object> refreshed(Map<String, Object> session, String entry) {
        return anonymous("/api/auth/refresh", Map.of("refreshToken", session.get("refreshToken"), "entry", entry))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private static String code(WebTestClient.ResponseSpec response, int status) {
        return refusalCode(response.expectStatus().isEqualTo(status).expectBody(MAP).returnResult()
            .getResponseBody());
    }

    private static String address(String prefix) {
        return unique(prefix).toLowerCase() + "@example.com";
    }

    private static String userIdOf(String email) {
        List<Map<String, Object>> rows = query("SELECT DISTINCT ON (user_id) user_id::text AS id FROM "
            + "sec_user_version WHERE lower(email) = lower(?) ORDER BY user_id, version_no DESC", email);
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst().get("id"));
    }

    private static List<String> rolesOf(String userId) {
        return query("""
            SELECT r.role_code FROM (SELECT DISTINCT ON (user_role_id) role_id, is_deleted FROM sec_user_role_version
                WHERE user_id = ?::uuid ORDER BY user_role_id, version_no DESC) a
            JOIN (SELECT DISTINCT ON (role_id) role_id, role_code FROM sec_role_version
                ORDER BY role_id, version_no DESC) r ON r.role_id = a.role_id
            WHERE NOT a.is_deleted ORDER BY r.role_code""", userId).stream()
            .map(row -> String.valueOf(row.get("role_code"))).toList();
    }

    /** Mail received at the address, oldest first. */
    private static List<MimeMessage> received(String address) {
        return Arrays.stream(MAIL.getReceivedMessages()).filter(message -> {
            try {
                return message.getAllRecipients()[0].toString().equalsIgnoreCase(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private static String text(MimeMessage message) {
        try {
            return part(message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String part(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            return String.valueOf(part.getContent());
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                String found = part(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String subject(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The token of the latest link of the pattern mailed to the address. */
    private static String token(Pattern pattern, String address) {
        List<MimeMessage> mail = received(address);
        for (int i = mail.size() - 1; i >= 0; i--) {
            Matcher matcher = pattern.matcher(text(mail.get(i)));
            if (matcher.find()) {
                assertThat(matcher.group(1)).as("the root of entry %s", matcher.group(2))
                    .isEqualTo(ENTRY_ROOTS.get(matcher.group(2)));
                return matcher.group(3);
            }
        }
        throw new AssertionError("No link " + pattern + " in the mail to " + address);
    }

    private void verify(String email) {
        anonymous("/api/auth/verify-email", Map.of("token", token(VERIFY_LINK, email), "password", PASSWORD))
            .expectStatus().isNoContent();
    }

    @Test
    void aTakerRegistersSignsInUnverifiedAndCompletesTheProfileOnceVerified() {
        String email = address("tom");
        register("app", email);
        String userId = userIdOf(email);
        assertThat(rolesOf(userId)).containsExactly(QbRoles.TAKER);
        assertThat(received(email)).hasSize(1);

        // Pending activation: the app lets them in to look around, but nothing is written (U-06).
        Map<String, Object> session = sessionAt(email, "app");
        assertThat(code(client.post().uri("/api/processes/QB_PROFILE_SAVE/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, bearerOf(session))
            .bodyValue(body("displayName", "Tom", "countryCode", "JP", "preferredCurrency", "JPY")).exchange(), 403))
            .isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(profileOf(userId)).isNull();

        verify(email);
        // The session's next token knows the address is verified.
        Map<String, Object> renewed = refreshed(session, "app");
        assertThat(run("QB_PROFILE_SAVE", bearerOf(renewed), body("displayName", "Tom", "countryCode", "JP",
            "preferredCurrency", "JPY"))).containsEntry("created", true);
        deliverEvents();
        assertThat(received(email)).extracting(AccountSignUpIT::subject).contains("Welcome, Tom");
        // The welcome mail is the app's (entries("app")): it unsubscribes on the app's own page (decision D52).
        assertThat(received(email).getLast()).satisfies(mail -> assertThat(text(mail))
            .contains("https://quizbuks.example.com/taker/mail/unsubscribe?token=").doesNotContain("/admin/"));
        // By the verified address as well as by the user name.
        sessionAt(email.toUpperCase(), "app");
    }

    @Test
    void aSponsorRegistersVerifiesAppliesAndIsToldOfTheReview() {
        String email = address("sara");
        register("sponsor", email);
        String userId = userIdOf(email);
        assertThat(rolesOf(userId)).containsExactly(QbRoles.SPONSOR);
        // The sponsor console takes verified addresses only.
        assertThat(code(signIn(email, "sponsor"), 403)).isEqualTo("EMAIL_NOT_VERIFIED");
        verify(email);
        Map<String, Object> session = sessionAt(email, "sponsor");

        Map<String, Object> applied = run("QB_SPONSOR_APPLY", bearerOf(session), body("displayName", "Sara",
            "company", "Acme", "acceptTerms", true));
        assertThat(applied).containsEntry("reviewStatus", SponsorStatus.PENDING);
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", applied.get("approvalRequestId"),
            "decision", "APPROVE"));
        deliverEvents();
        MimeMessage approved = received(email).getLast();
        assertThat(subject(approved)).isEqualTo("Your sponsor account is approved");
        assertThat(text(approved)).contains("Acme").contains("https://quizbuks.example.com/sponsor/");

        // Banned: the console refuses them (the guard, after the password and the verified address).
        String boss = userWithRole(unique("boss"), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, QbRoles.ADMIN_SUPER), body("userId", userId, "reason", "Fraud"));
        assertThat(code(signIn(email, "sponsor"), 403)).isEqualTo("SIGN_IN_REFUSED");
    }

    @Test
    void theEntriesStaySeparateForOnePersonWithBothRoles() {
        String email = address("both");
        register("app", email);
        verify(email);
        String userId = userIdOf(email);
        // The same address at the sponsor console: "you already have an account", and the role once joined.
        register("sponsor", email);
        assertThat(rolesOf(userId)).containsExactly(QbRoles.TAKER);
        anonymous("/api/auth/join", Map.of("token", token(JOIN_LINK, email))).expectStatus().isNoContent();
        assertThat(rolesOf(userId)).containsExactlyInAnyOrder(QbRoles.TAKER, QbRoles.SPONSOR);

        String app = bearerOf(sessionAt(email, "app"));
        String sponsor = bearerOf(sessionAt(email, "sponsor"));
        // Each token holds its entry's permissions only.
        client.post().uri("/api/processes/QB_SPONSOR_APPLY/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, app).bodyValue(body("displayName", "B", "company", "B Co",
                "acceptTerms", true)).exchange().expectStatus().isForbidden();
        client.post().uri("/api/processes/QB_PROFILE_SAVE/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, sponsor).bodyValue(body("displayName", "B", "countryCode", "JP",
                "preferredCurrency", "JPY")).exchange().expectStatus().isForbidden();
        run("QB_PROFILE_SAVE", app, body("displayName", "B", "countryCode", "JP", "preferredCurrency", "JPY"));
        run("QB_SPONSOR_APPLY", sponsor, body("displayName", "B", "company", "B Co", "acceptTerms", true));
        // The administration takes neither.
        assertThat(code(signIn(email, "admin"), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(query("SELECT outcome, entry FROM sec_login_record_version WHERE user_id = ?::uuid "
            + "ORDER BY attempt_no DESC LIMIT 1", userId)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("outcome", "NO_ROLE").containsEntry("entry", "admin"));
    }

    /**
     * Review 8: a ban closes all three entries. A verified person who is taker and sponsor is refused at both; the
     * administration takes no banned user either, even one given an administrator's role after the ban.
     */
    @Test
    void aBanClosesAllThreeEntries() {
        String email = address("all");
        register("app", email);
        verify(email);
        register("sponsor", email);
        anonymous("/api/auth/join", Map.of("token", token(JOIN_LINK, email))).expectStatus().isNoContent();
        String userId = userIdOf(email);
        sessionAt(email, "app");
        sessionAt(email, "sponsor");
        String boss = userWithRole(unique("boss"), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, QbRoles.ADMIN_SUPER), body("userId", userId, "reason", "Fraud"));
        // The platform's first administrator's role (no second factor), given after the ban.
        if (query("SELECT 1 FROM sec_role_version WHERE role_code = 'ADMIN'").isEmpty()) {
            commit(com.jabiz.runtime.security.SecurityEntities.ROLE_DATASET, "INSERT", null, null, Map.of("roleCode",
                "ADMIN", "labels", Map.of("en", "Administrator"), "enabled", true), null);
        }
        assignRole(userId, "ADMIN");

        for (String entry : List.of("app", "sponsor", "admin")) {
            assertThat(code(signIn(email, entry), 403)).as(entry).isEqualTo("SIGN_IN_REFUSED");
        }
        assertThat(query("SELECT entry FROM sec_login_record_version WHERE user_id = ?::uuid AND outcome = "
            + "'REFUSED' ORDER BY attempt_no", userId)).extracting(row -> row.get("entry"))
            .containsExactly("app", "sponsor", "admin");
    }

    @Test
    void theAdministrationTakesNoRegistrations() {
        String email = address("adm");
        assertThat(code(anonymous("/api/auth/register", Map.of("entry", "admin", "email", email, "password",
            PASSWORD)), 422)).isEqualTo("SIGN_UP_DISABLED");
        assertThat(code(anonymous("/api/auth/register", Map.of("email", email, "password", PASSWORD)), 422))
            .isEqualTo("SIGN_UP_DISABLED");
        assertThat(userIdOf(email)).isNull();
    }

    @Test
    void anAddressRegisteredForSomebodyElseKeepsNoQuizBuksData() {
        String victim = address("victim");
        // The attacker registers the victim's address and signs in to the app (no verification needed to look).
        register("app", victim);
        String userId = userIdOf(victim);
        Map<String, Object> attacker = sessionAt(victim, "app");
        assertThat(code(client.post().uri("/api/processes/QB_PROFILE_SAVE/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, bearerOf(attacker))
            .bodyValue(body("displayName", "Attacker", "countryCode", "JP", "preferredCurrency", "JPY")).exchange(),
            403)).isEqualTo("EMAIL_NOT_VERIFIED");

        // The mailbox's owner takes the account over with a password of their own (the platform's claim).
        anonymous("/api/auth/verify-email", Map.of("token", token(VERIFY_LINK, victim), "newPassword",
            "the owner's own password")).expectStatus().isNoContent();
        anonymous("/api/auth/refresh", Map.of("refreshToken", attacker.get("refreshToken"), "entry", "app"))
            .expectStatus().isUnauthorized();
        assertThat(profileOf(userId)).isNull();
        assertThat(onboardingOf(userId)).isNull();
        assertThat(mailTo(userId)).extracting(row -> row.get("template")).doesNotContain("qb.welcome");
    }
}
