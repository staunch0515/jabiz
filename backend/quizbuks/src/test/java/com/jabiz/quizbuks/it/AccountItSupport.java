package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Account integration tests (docs/quizbuks/plans/Q2-accounts.md): real platform users (with an address, so mail is
 * recorded for them), their tokens with the permissions of the QuizBuks roles, sign-in at the three entries, and the
 * datasets and templates of accounts. Installed as the publishing tests are (QuizBuks set up, every proposal
 * published, sponsors A and B onboarded).
 */
abstract class AccountItSupport extends PublicationItSupport {

    /** The three tables of accounts, only ever inserted into. */
    static final List<String> ACCOUNT_TABLES = List.of("qb_profile_version", "qb_sponsor_version",
        "qb_user_ban_version");

    static final String PASSWORD = "correct horse battery";

    /** A name no other test of the run uses. */
    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A platform user with an address and a password (created by an administrator); returns the user id. */
    @SuppressWarnings("unchecked")
    String createUser(String name) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("userName", name);
        input.put("displayName", name);
        input.put("email", name.toLowerCase() + "@example.com");
        input.put("password", PASSWORD);
        Map<String, Object> result = client.post().uri("/api/processes/SEC_USER_CREATE/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin()).bodyValue(input)
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    /** Assigns a QuizBuks (or platform) role by its code. */
    void assignRole(String userId, String roleCode) {
        String roleId = String.valueOf(query("SELECT role_id FROM sec_role_version WHERE role_code = ?", roleCode)
            .getFirst().get("role_id"));
        commit(SecurityEntities.USER_ROLE_DATASET, "INSERT", null, null, Map.of("userId", userId, "roleId", roleId),
            null);
    }

    /** A user holding a role; returns the user id. */
    String userWithRole(String name, String roleCode) {
        String userId = createUser(name);
        assignRole(userId, roleCode);
        return userId;
    }

    /** A token with the permissions of a QuizBuks role. */
    String tokenOf(String userId, String roleCode) {
        return TestTokens.bearer(tokens, userId, QbRoles.of(roleCode).permissions().toArray(String[]::new));
    }

    /** As {@link #tokenOf}, for a session whose address is not verified. */
    String unverifiedTokenOf(String userId, String roleCode) {
        return TestTokens.unverified(tokens, userId, QbRoles.of(roleCode).permissions().toArray(String[]::new));
    }

    /** Signs in at an entry. */
    WebTestClient.ResponseSpec login(String userName, String entry) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("userName", userName, "password", PASSWORD));
        if (entry != null) {
            body.put("entry", entry);
        }
        return client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange();
    }

    /** Signs in at an entry, which must succeed; returns the session. */
    Map<String, Object> session(String userName, String entry) {
        return login(userName, entry).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    static String bearerOf(Map<String, Object> session) {
        return "Bearer " + session.get("accessToken");
    }

    /** The rule code of a refusal's first violation, or its error code. */
    static String refusalCode(Map<String, Object> problem) {
        List<Object> codes = codes(problem);
        return codes.isEmpty() ? String.valueOf(problem.get("code")) : String.valueOf(codes.getFirst());
    }

    /** A page of a dataset as the caller reads it; the instances' attributes. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows(String dataset, String token) {
        Map<String, Object> page = client.post().uri("/api/datasets/{id}/query", dataset)
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(Map.of("limit", 100)).exchange().expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(item -> (Map<String, Object>) item.get("attributes")).toList();
    }

    WebTestClient.ResponseSpec queryDataset(String dataset, String token) {
        return client.post().uri("/api/datasets/{id}/query", dataset).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of()).exchange();
    }

    /** The user's current profile row, or null. */
    static Map<String, Object> profileOf(String userId) {
        List<Map<String, Object>> rows = latest("qb_profile_version", "profile_id", "user_id = '" + userId + "'");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The user's current onboarding row, or null. */
    static Map<String, Object> onboardingOf(String userId) {
        List<Map<String, Object>> rows = latest("qb_sponsor_version", "onboarding_id", "user_id = '" + userId + "'");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The user's current ban row, or null. */
    static Map<String, Object> banOf(String userId) {
        List<Map<String, Object>> rows = latest("qb_user_ban_version", "ban_id", "user_id = '" + userId + "'");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The mail recorded for a user (sent or skipped), oldest first: template and parameters. */
    static List<Map<String, Object>> mailTo(String userId) {
        return query("SELECT template, category, params FROM sys_mail_message WHERE user_id = ?::uuid "
            + "ORDER BY created_time, message_id", userId);
    }

    static List<Object> templatesTo(String userId) {
        return mailTo(userId).stream().map(row -> row.get("template")).toList();
    }

    /** The open tasks of a user (from the table). */
    static List<Map<String, Object>> openTasksOf(String userId) {
        return query("SELECT task_type, title_key, title_params, source_key FROM (SELECT DISTINCT ON (task_id) * "
            + "FROM sys_task_version ORDER BY task_id, version_no DESC) t WHERE assignee_user_id = ? "
            + "AND status = 'OPEN' AND NOT is_deleted", userId);
    }

    /** The account tables were only ever inserted into, and carry the append-only guard. */
    static void assertAccountsOnlyInserted() {
        for (String table : ACCOUNT_TABLES) {
            assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
                .as(table).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
            assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
                + "AND c.relname = ? AND NOT t.tgisinternal", table)).as(table).isNotEmpty();
        }
        // The guard refuses what the counters only confirm (it fires per row: tables with rows only).
        for (String table : ACCOUNT_TABLES) {
            if (!query("SELECT 1 FROM " + table + " LIMIT 1").isEmpty()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> execute("UPDATE " + table
                    + " SET revision = revision")).as(table).hasMessageContaining("append-only");
            }
        }
    }

    /** The datasets of accounts, for the tests that read them. */
    static final String PROFILES = QbAccounts.defaultDataset(QbAccounts.PROFILE);
    static final String SPONSORS = QbAccounts.defaultDataset(QbAccounts.SPONSOR);
    static final String BANS = QbAccounts.defaultDataset(QbAccounts.BAN);
}
