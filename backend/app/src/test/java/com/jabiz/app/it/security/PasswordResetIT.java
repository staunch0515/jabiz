package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.it.fixture.SqlStatementLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Password reset by mail (docs/design/10-security.md section 15; decision D36 item 5 and implementation note 5;
 * ROADMAP phase 16b-2): asking never tells whether the address has an account, the link sets the password once, proves
 * the address, ends every session and lifts a lock, and it works for users who had no password (identity provider
 * only).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3029"})
class PasswordResetIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3029, null, ServerSetup.PROTOCOL_SMTP));

    private static final String NEW_PASSWORD = "brand new horse battery";

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    private void ask(String email, String entry) {
        anonymous("/api/auth/password-reset/request", entry == null ? Map.of("email", email)
            : Map.of("email", email, "entry", entry)).expectStatus().isAccepted().expectBody().isEmpty();
    }

    private void confirm(String token, String password) {
        anonymous("/api/auth/password-reset/confirm", Map.of("token", token, "password", password))
            .expectStatus().isNoContent();
    }

    private static List<Map<String, Object>> records(String userId) {
        return query("SELECT outcome, failure_count, locked_until, entry, user_agent FROM sec_login_record_version "
            + "WHERE user_id = ?::uuid ORDER BY attempt_no", userId);
    }

    @Test
    void askingTellsNothingAndOnlyEnabledUsersGetALink() {
        String unknown = address("nobody");
        ask(unknown, null);

        String disabled = address("off");
        String name = unique("off");
        String userId = createUserWithEmail(name, disabled, PASSWORD);
        Map<String, Object> user = get("/api/datasets/" + userDataset() + "/entities/" + userId, admin())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/datasets/" + userDataset() + "/commit", admin(), Map.of("changes", List.of(Map.of(
            "action", "UPDATE", "id", userId, "version", user.get("version"), "attributes",
            Map.of("enabled", false))))).expectStatus().isOk();
        ask(disabled, null);

        String enabled = address("on");
        createUserWithEmail(unique("on"), enabled, PASSWORD);
        ask(enabled.toUpperCase(Locale.ROOT), PORTAL);
        assertThat(refusal(anonymous("/api/auth/password-reset/request", Map.of("email", enabled, "entry", "nope")),
            400)).isEqualTo("INVALID_VALUE");

        deliver();
        assertThat(to(unknown)).isEmpty();
        assertThat(to(disabled)).isEmpty();
        assertThat(to(enabled)).singleElement().satisfies(message -> assertThat(text(message))
            .contains("https://app.example.com/auth/password-reset?entry=portal&token="));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLinkSetsThePasswordEndsEverySessionAndLiftsTheLock() {
        String email = address("pia");
        String name = unique("pia");
        String userId = userWithEmail(name, email);
        Map<String, Object> session = signIn(name, PASSWORD);
        // Locked by five wrong passwords.
        for (int i = 0; i < 5; i++) {
            loginTo(name, "wrong horse battery", null).expectStatus().isUnauthorized();
        }
        assertThat(records(userId).getLast()).extractingByKey("locked_until").isNotNull();

        ask(email, PORTAL);
        deliver();
        String token = token(RESET_LINK, email);

        // A password the rules refuse leaves the link usable.
        assertThat(refusal(anonymous("/api/auth/password-reset/confirm", Map.of("token", token, "password", "short")),
            400)).isEqualTo("PASSWORD_TOO_SHORT");
        client.post().uri("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.USER_AGENT, "it-agent/reset")
            .bodyValue(Map.of("token", token, "password", NEW_PASSWORD)).exchange().expectStatus().isNoContent();

        assertThat(records(userId).getLast()).containsEntry("outcome", "PASSWORD_RESET")
            .containsEntry("failure_count", 0).containsEntry("locked_until", null).containsEntry("entry", "portal")
            .containsEntry("user_agent", "it-agent/reset");
        assertThat(refusal(post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken"))), 401))
            .isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(query("SELECT DISTINCT reason FROM sec_refresh_family_revocation r JOIN sec_refresh_token t "
            + "ON t.family_id = r.family_id WHERE t.user_id = ?::uuid", userId))
            .extracting(row -> row.get("reason")).containsExactly("PASSWORD_RESET");
        assertThat(refusal(loginTo(name, PASSWORD, null), 401)).isEqualTo("LOGIN_FAILED");
        Map<String, Object> next = loginTo(name, NEW_PASSWORD, null).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        // The link proved the address: it now signs in too, and the session says so.
        assertThat(tokens.verify((String) next.get("accessToken")).emailVerified()).isTrue();
        loginTo(email, NEW_PASSWORD, null).expectStatus().isOk();

        // Once only, and of its own purpose.
        assertThat(refusal(anonymous("/api/auth/password-reset/confirm", Map.of("token", token, "password",
            NEW_PASSWORD)), 422)).isEqualTo("TOKEN_INVALID");
        ask(email, null);
        deliver();
        String again = token(RESET_LINK, email);
        assertThat(refusal(anonymous("/api/auth/verify-email", Map.of("token", again)), 422))
            .isEqualTo("TOKEN_INVALID");
        assertThat(refusal(anonymous("/api/auth/join", Map.of("token", again, "password", NEW_PASSWORD)), 422))
            .isEqualTo("TOKEN_INVALID");
        // Expired after an hour.
        clock.advance(Duration.ofHours(1));
        assertThat(refusal(anonymous("/api/auth/password-reset/confirm", Map.of("token", again, "password",
            NEW_PASSWORD)), 422)).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void aUserOfAnIdentityProviderOnlyCanSetAPassword() {
        String email = address("oid");
        String name = unique("oid");
        String userId = createUserWithEmail(name, email, null);
        assign(userId, roleId(CUSTOMER), null);
        assertThat(userOf(email)).containsEntry("password_hash", null);

        ask(email, null);
        deliver();
        confirm(token(RESET_LINK, email), NEW_PASSWORD);
        loginTo(name, NEW_PASSWORD, null).expectStatus().isOk();
    }

    @Test
    void aLinkIsVoidForAUserDisabledSinceOrAtAnotherAddress() {
        String email = address("gone");
        String name = unique("gone");
        String userId = userWithEmail(name, email);
        ask(email, null);
        deliver();
        String token = token(RESET_LINK, email);
        Map<String, Object> user = get("/api/datasets/" + userDataset() + "/entities/" + userId, admin())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/datasets/" + userDataset() + "/commit", admin(), Map.of("changes", List.of(Map.of(
            "action", "UPDATE", "id", userId, "version", user.get("version"), "attributes",
            Map.of("enabled", false))))).expectStatus().isOk();
        assertThat(refusal(anonymous("/api/auth/password-reset/confirm", Map.of("token", token, "password",
            NEW_PASSWORD)), 422)).isEqualTo("TOKEN_INVALID");
        assertThat(records(userId)).isEmpty();
    }

    @Test
    void askingIsLimitedAlikeForEveryAddress() {
        String known = address("lim");
        createUserWithEmail(unique("lim"), known, PASSWORD);
        for (String email : List.of(known, address("lim-unknown"))) {
            for (int i = 0; i < 3; i++) {
                ask(email, null);
            }
            anonymous("/api/auth/password-reset/request", Map.of("email", email)).expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER);
        }
    }

    @Test
    void resettingOnlyAppendsRows() {
        String email = address("app");
        String name = unique("app");
        userWithEmail(name, email);
        signIn(name, PASSWORD);
        SqlStatementLog.STATEMENTS.clear();
        ask(email, null);
        deliver();
        confirm(token(RESET_LINK, email), NEW_PASSWORD);

        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("UPDATE"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("DELETE"));
    }

    /** A user with an address, a password and the customer role; returns the user id. */
    private String userWithEmail(String name, String email) {
        String userId = createUserWithEmail(name, email, PASSWORD);
        assign(userId, roleId(CUSTOMER), null);
        return userId;
    }
}
