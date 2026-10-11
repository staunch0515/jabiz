package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Banning (P-02, 5.3-11; docs/quizbuks/plans/Q2-accounts.md, D-Q2-4): a banned user is refused at sign-in by the
 * QuizBuks guard (recorded, not counted as a failure) and loses their session at its next refresh; unbanned, they
 * sign in again. Nobody bans themselves or an administrator; bans and lifts are kept as history and in the audit.
 */
class BanIT extends AccountItSupport {

    @AfterEach
    void onlyInserted() {
        assertAccountsOnlyInserted();
    }

    private String superAdmin() {
        return userWithRole(unique("boss"), QbRoles.ADMIN_SUPER);
    }

    private static List<Map<String, Object>> records(String userId) {
        return query("SELECT outcome, entry, failure_count FROM sec_login_record_version WHERE user_id = ?::uuid "
            + "ORDER BY attempt_no", userId);
    }

    private Map<String, Object> refresh(Object refreshToken, String entry) {
        return client.post().uri("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("refreshToken", refreshToken, "entry", entry)).exchange().expectStatus()
            .isUnauthorized().expectBody(MAP).returnResult().getResponseBody();
    }

    @Test
    void aBannedUserCannotSignInAndLosesTheirSessionUntilUnbanned() {
        String name = unique("tom");
        String tom = userWithRole(name, QbRoles.TAKER);
        assignRole(tom, QbRoles.SPONSOR);
        Map<String, Object> session = session(name, "app");
        String admin = superAdmin();

        Map<String, Object> banned = run("QB_USER_BAN", tokenOf(admin, QbRoles.ADMIN_SUPER), body("userId", tom,
            "reason", "Cheating"));
        assertThat(banned).containsEntry("userId", tom).containsEntry("banned", true).containsEntry("revision", 1);
        assertThat(banOf(tom)).containsEntry("banned", true).containsEntry("reason", "Cheating")
            .containsEntry("banned_by", admin);

        // Refused at the app, recorded and not counted as a failure.
        assertThat(refusalCode(login(name, "app").expectStatus().isForbidden().expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("SIGN_IN_REFUSED");
        assertThat(records(tom).getLast()).containsEntry("outcome", "REFUSED").containsEntry("entry", "app")
            .satisfies(row -> assertThat(((Number) row.get("failure_count")).intValue()).isZero());
        // The administration does not take the user at all (no role of its), as before the ban.
        login(name, "admin").expectStatus().isUnauthorized();
        // The session ends at its next refresh, and its family with it.
        assertThat(refusalCode(refresh(session.get("refreshToken"), "app"))).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(query("SELECT reason FROM sec_refresh_family_revocation r JOIN sec_refresh_token t "
            + "ON t.family_id = r.family_id WHERE t.user_id = ?::uuid", tom)).extracting(row -> row.get("reason"))
            .contains("REFUSED");

        Map<String, Object> lifted = run("QB_USER_UNBAN", tokenOf(admin, QbRoles.ADMIN_SUPER), body("userId", tom,
            "note", "Appeal accepted"));
        assertThat(lifted).containsEntry("banned", false).containsEntry("revision", 2);
        session(name, "app");
        assertThat(banOf(tom)).containsEntry("banned", false).containsEntry("lift_note", "Appeal accepted")
            .containsEntry("lifted_by", admin);

        // The history of bans and lifts, and the audit.
        assertThat(query("SELECT banned FROM qb_user_ban_version WHERE user_id = ?::uuid ORDER BY version_no", tom))
            .extracting(row -> row.get("banned")).containsExactly(true, false);
        Object banId = banOf(tom).get("ban_id");
        assertThat(query("SELECT action, actor_id FROM sys_audit_record WHERE entity_type = 'QbUserBan' "
            + "AND entity_id = ? ORDER BY record_no", String.valueOf(banId)))
            .extracting(row -> row.get("action"), row -> row.get("actor_id"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("INSERT", admin),
                org.assertj.core.groups.Tuple.tuple("UPDATE", admin));
        // An administrator reads the history.
        assertThat(get("/api/datasets/" + BANS + "/entities/" + banId + "/history",
            tokenOf("reader", QbRoles.ADMIN_CONTENT)).expectStatus().isOk().expectBody(String.class).returnResult()
            .getResponseBody()).contains("Cheating", "Appeal accepted");
    }

    @Test
    void nobodyBansThemselvesOrAnAdministrator() {
        String admin = superAdmin();
        String token = tokenOf(admin, QbRoles.ADMIN_SUPER);
        assertThat(codes(refused("QB_USER_BAN", token, body("userId", admin, "reason", "Me"), 422)))
            .containsExactly(AccountCodes.BAN_SELF);
        for (String role : List.of(QbRoles.ADMIN_CONTENT, QbRoles.ADMIN_FINANCE, QbRoles.ADMIN_SUPER)) {
            String other = userWithRole(unique("adm"), role);
            assertThat(codes(refused("QB_USER_BAN", token, body("userId", other, "reason", "No"), 422))).as(role)
                .containsExactly(AccountCodes.BAN_ADMIN);
            assertThat(banOf(other)).isNull();
        }
    }

    @Test
    void banningFollowsTheState() {
        String admin = superAdmin();
        String token = tokenOf(admin, QbRoles.ADMIN_SUPER);
        String tom = userWithRole(unique("tom"), QbRoles.TAKER);
        assertThat(codes(refused("QB_USER_UNBAN", token, body("userId", tom), 422)))
            .containsExactly(AccountCodes.BAN_STATE);
        run("QB_USER_BAN", token, body("userId", tom.toUpperCase(), "reason", "Spam"));
        assertThat(String.valueOf(banOf(tom).get("user_id"))).isEqualTo(tom);
        assertThat(codes(refused("QB_USER_BAN", token, body("userId", tom, "reason", "Again"), 422)))
            .containsExactly(AccountCodes.BAN_STATE);
        run("QB_USER_UNBAN", token, body("userId", tom));
        assertThat(codes(refused("QB_USER_UNBAN", token, body("userId", tom), 422)))
            .containsExactly(AccountCodes.BAN_STATE);
        // A reason is needed; users that do not exist are not found.
        refused("QB_USER_BAN", token, body("userId", tom), 400);
        refused("QB_USER_BAN", token, body("userId", UUID.randomUUID().toString(), "reason", "Ghost"), 404);
        refused("QB_USER_BAN", token, body("userId", "sponsor-a", "reason", "Not a user"), 404);
    }

    @Test
    void onlyHoldersOfTheBanPermissionBanAndTheGuardSeesNoOtherData() {
        String tom = userWithRole(unique("tom"), QbRoles.TAKER);
        // A content administrator reads users but does not ban them; nor does a sponsor.
        refused("QB_USER_BAN", tokenOf("reader", QbRoles.ADMIN_CONTENT), body("userId", tom, "reason", "x"), 403);
        refused("QB_USER_BAN", sponsorA(), body("userId", tom, "reason", "x"), 403);
        refused("QB_USER_UNBAN", TestTokens.bearer(tokens, "nobody", QbPermissions.ADMIN_USERS_READ),
            body("userId", tom), 403);
        assertThat(banOf(tom)).isNull();
        // Bans are not anybody's own data: there is no dataset of one's own ban, and the default one is the
        // administrators'.
        queryDataset(BANS, tokenOf(tom, QbRoles.TAKER)).expectStatus().isForbidden();
        // The ban process acts on the platform's users (a row action in the administration).
        assertThat(get("/api/meta/processes", tokenOf("boss-meta", QbRoles.ADMIN_SUPER)).expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody()).contains(SecurityEntities.USER);
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec get(String path, String token) {
        return client.get().uri(path).header("Authorization", token).exchange();
    }
}
