package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.it.fixture.SqlStatementLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Password reset by mail (docs/design/10-security.md section 15.7; decision D36 item 5 and implementation note 5;
 * ROADMAP phase 16b-2): asking never tells whether the address has an account, only a trusted address gets a link
 * (verified, or a registration nobody verified yet; never an address an administrator set), and the link sets the
 * password once, proves the address, ends every session and lifts a lock. A second factor stays and is asked at the
 * next sign-in, except on an account somebody else may have registered (pre-claim). It works for users who had no
 * password (identity provider only).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3029", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE})
@Import(SignUpItSupport.FixedTimeLimits.class)
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

    /** A user with a verified address, a password and the customer role; returns the user id. */
    private String userWithEmail(String name, String email) {
        String userId = createUserWithEmail(name, email, PASSWORD);
        assign(userId, roleId(CUSTOMER), null);
        verifyEmail(userId);
        return userId;
    }

    @Test
    void askingTellsNothingAndOnlyEnabledUsersWithATrustedAddressGetALink() {
        String unknown = address("nobody");
        ask(unknown, null);

        String disabled = address("off");
        String userId = userWithEmail(unique("off"), disabled);
        updateUser(userId, Map.of("enabled", false));
        ask(disabled, null);

        // Set by an administrator and never verified (as every address before phase 16b-2): not trusted.
        String unverified = address("unv");
        createUserWithEmail(unique("unv"), unverified, PASSWORD);
        ask(unverified, null);

        String enabled = address("on");
        userWithEmail(unique("on"), enabled);
        ask(enabled.toUpperCase(Locale.ROOT), PORTAL);
        assertThat(refusal(anonymous("/api/auth/password-reset/request", Map.of("email", enabled, "entry", "nope")),
            400)).isEqualTo("INVALID_VALUE");

        deliver();
        assertThat(to(unknown)).isEmpty();
        assertThat(to(disabled)).isEmpty();
        assertThat(to(unverified)).isEmpty();
        assertThat(to(enabled)).singleElement().satisfies(message -> assertThat(text(message))
            .contains("https://app.example.com/portal/auth/password-reset?entry=portal&token="));
    }

    @Test
    void eachEntrysLinkLeadsToItsOwnFrontendAndTheTokenIsTheLinksOnlyCredential() {
        // The portal's frontend is at /portal/ (its app-path), the administration's and the loose entry's at the root.
        String portal = address("portal");
        userWithEmail(unique("portal"), portal);
        ask(portal, PORTAL);
        String admin = address("admin");
        userWithEmail(unique("admin"), admin);
        ask(admin, null);
        String loose = address("loose");
        userWithEmail(unique("loose"), loose);
        ask(loose, LOOSE);

        deliver();
        assertThat(text(to(portal).getFirst()))
            .contains("https://app.example.com/portal/auth/password-reset?entry=portal&token=");
        assertThat(text(to(admin).getFirst()))
            .contains("https://app.example.com/auth/password-reset?entry=admin&token=")
            .doesNotContain("/portal/");
        assertThat(text(to(loose).getFirst()))
            .contains("https://app.example.com/auth/password-reset?entry=loose&token=");
        // A link of the earlier form (the administration's landing page, /auth/...) carries the same token, which is
        // all the API needs: links sent before the portal had its own pages keep working.
        java.util.regex.Matcher link = RESET_LINK.matcher(text(to(portal).getFirst()));
        assertThat(link.find()).isTrue();
        String earlierForm = "https://app.example.com/auth/password-reset?entry=" + link.group(1) + "&token="
            + link.group(2);
        assertThat(RESET_LINK.matcher(earlierForm).matches()).isTrue();
        confirm(link.group(2), NEW_PASSWORD);
    }

    @Test
    void anAddressAnAdministratorChangedIsNeverTrustedForAReset() {
        // An administrator with a second factor; whoever may write users changes its address to one of their own.
        String name = unique("root");
        String email = address("root");
        String userId = createUserWithEmail(name, email, PASSWORD);
        assign(userId, roleId(BACK_OFFICE), null);
        verifyEmail(userId);
        enrollMfa(bearerOf(session(name, PASSWORD, null)));
        String attacker = address("mallory");
        updateUser(userId, Map.of("email", attacker));

        ask(attacker, null);
        deliver();
        assertThat(to(attacker)).isEmpty();
        assertThat(hasMfa(userId)).isTrue();
        // Registering it gets nothing either: no "you already have an account" link for an address nobody proved.
        register(PORTAL, attacker, PASSWORD).expectStatus().isAccepted();
        deliver();
        assertThat(to(attacker)).isEmpty();
    }

    @Test
    void aResetKeepsTheSecondFactorWhichTheNextSignInAsks() {
        String name = unique("kim");
        String email = address("kim");
        String userId = userWithEmail(name, email);
        enrollMfa(bearerOf(session(name, PASSWORD, LOOSE)));

        ask(email, null);
        deliver();
        confirm(token(RESET_LINK, email), NEW_PASSWORD);

        assertThat(hasMfa(userId)).isTrue();
        assertThat(session(name, NEW_PASSWORD, LOOSE)).containsEntry("status", "MFA_REQUIRED");
    }

    @Test
    void aResetTakesOverAnAccountSomebodyElseRegistered() {
        String victim = address("prey");
        String attackerPassword = "attacker horse battery";
        register(PORTAL, victim, attackerPassword).expectStatus().isAccepted();
        String userId = String.valueOf(userOf(victim).get("user_id"));
        Map<String, Object> attacker = session(victim, attackerPassword, LOOSE);
        enrollMfa(bearerOf(attacker));

        // Never verified, still at the address it registered with: the mailbox's owner may reset.
        ask(victim, PORTAL);
        deliver();
        confirm(token(RESET_LINK, victim), NEW_PASSWORD);

        assertThat(hasMfa(userId)).isFalse();
        assertThat(refusal(loginTo(victim, attackerPassword, LOOSE), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(refusal(post("/api/auth/refresh", null, Map.of("refreshToken", attacker.get("refreshToken"),
            "entry", LOOSE)), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(session(victim, NEW_PASSWORD, PORTAL)).containsEntry("status", "SIGNED_IN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLinkSetsThePasswordEndsEverySessionAndLiftsTheLock() {
        String email = address("pia");
        String name = unique("pia");
        String userId = userWithEmail(name, email);
        Map<String, Object> session = session(name, PASSWORD, LOOSE);
        // Locked by five wrong passwords.
        for (int i = 0; i < 5; i++) {
            loginTo(name, "wrong horse battery", LOOSE).expectStatus().isUnauthorized();
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
        assertThat(refusal(post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken"),
            "entry", LOOSE)), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(query("SELECT DISTINCT reason FROM sec_refresh_family_revocation r JOIN sec_refresh_token t "
            + "ON t.family_id = r.family_id WHERE t.user_id = ?::uuid", userId))
            .extracting(row -> row.get("reason")).containsExactly("PASSWORD_RESET");
        assertThat(refusal(loginTo(name, PASSWORD, LOOSE), 401)).isEqualTo("LOGIN_FAILED");
        Map<String, Object> next = session(name, NEW_PASSWORD, LOOSE);
        assertThat(tokens.verify((String) next.get("accessToken")).emailVerified()).isTrue();
        loginTo(email, NEW_PASSWORD, PORTAL).expectStatus().isOk();

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
        verifyEmail(userId);
        assertThat(userOf(email)).containsEntry("password_hash", null);

        ask(email, null);
        deliver();
        confirm(token(RESET_LINK, email), NEW_PASSWORD);
        loginTo(name, NEW_PASSWORD, LOOSE).expectStatus().isOk();
    }

    @Test
    void aLinkIsVoidForAUserDisabledSinceOrAtAnotherAddress() {
        String email = address("gone");
        String userId = userWithEmail(unique("gone"), email);
        ask(email, null);
        deliver();
        String token = token(RESET_LINK, email);
        updateUser(userId, Map.of("enabled", false));
        assertThat(refusal(anonymous("/api/auth/password-reset/confirm", Map.of("token", token, "password",
            NEW_PASSWORD)), 422)).isEqualTo("TOKEN_INVALID");

        String moved = address("moved");
        String other = userWithEmail(unique("moved"), moved);
        ask(moved, null);
        deliver();
        String link = token(RESET_LINK, moved);
        updateUser(other, Map.of("email", address("elsewhere")));
        // Checked before the password: even one the rules refuse is answered as the void link it is.
        assertThat(refusal(anonymous("/api/auth/password-reset/confirm", Map.of("token", link, "password", "x")),
            422)).isEqualTo("TOKEN_INVALID");
        assertThat(records(userId)).isEmpty();
    }

    @Test
    void askingIsLimitedAlikeForEveryAddress() {
        String known = address("lim");
        userWithEmail(unique("lim"), known);
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
        session(name, PASSWORD, LOOSE);
        SqlStatementLog.STATEMENTS.clear();
        ask(email, null);
        deliver();
        confirm(token(RESET_LINK, email), NEW_PASSWORD);

        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("UPDATE"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase(Locale.ROOT)).startsWith("DELETE"));
    }
}
