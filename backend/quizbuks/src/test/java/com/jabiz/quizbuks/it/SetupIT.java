package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code QB_SETUP} creates the roles with their permissions, the ledger accounts, every country and the business
 * parameters; run again, it adds nothing and changes nothing (docs/quizbuks/plans/Q1-skeleton.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SetupIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
            .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en").build();
    }

    @Test
    void setsUpOnceAndAddsNothingWhenRunAgain() {
        String admin = TestTokens.bearer(tokens, "installer", QbPermissions.SETUP);

        Map<String, Object> first = setup(admin);
        assertThat(first.get("rolesCreated")).isEqualTo(QbRoles.all().stream().map(QbRoles.Role::code).toList());
        int granted = QbRoles.all().stream().mapToInt(role -> role.permissions().size()).sum();
        assertThat(first.get("permissionsAdded")).isEqualTo(granted);
        assertThat(first.get("accountsOpened"))
            .isEqualTo(QbLedger.ACCOUNTS.stream().map(QbLedger.Account::code).toList());
        assertThat(first.get("countriesAdded")).isEqualTo(249);
        assertThat(first.get("paramsCreated")).isEqualTo(QbParams.ALL.stream().map(QbParams.Param::key).toList());

        Map<String, Long> before = rowCounts();
        assertRoles();
        assertAccounts();
        assertCountries();
        assertParams();

        Map<String, Object> second = setup(admin);
        assertThat(second).containsEntry("rolesCreated", List.of()).containsEntry("permissionsAdded", 0)
            .containsEntry("accountsOpened", List.of()).containsEntry("countriesAdded", 0)
            .containsEntry("paramsCreated", List.of());
        assertThat(rowCounts()).isEqualTo(before);
        assertCountries();

        assertOnlyInserted("qb_country_version");
    }

    @Test
    void needsThePermissionAndASecondFactor() {
        post(TestTokens.bearer(tokens, "visitor", QbPermissions.COUNTRY_READ)).expectStatus().isForbidden();
        post(TestTokens.withoutMfa(tokens, "installer", QbPermissions.SETUP)).expectStatus().isForbidden();
    }

    private void assertRoles() {
        List<Map<String, Object>> roles = query("SELECT role_id, role_code, require_mfa FROM sec_role_version "
            + "WHERE role_code LIKE 'QB\\_%' ORDER BY role_code");
        assertThat(roles).extracting(row -> row.get("role_code")).containsExactlyInAnyOrderElementsOf(
            QbRoles.all().stream().map(QbRoles.Role::code).toList());
        for (Map<String, Object> role : roles) {
            QbRoles.Role declared = QbRoles.of((String) role.get("role_code"));
            assertThat(role.get("require_mfa")).as(declared.code()).isEqualTo(declared.requireMfa());
            List<Object> permissions = query("SELECT permission FROM sec_role_permission_version WHERE role_id = ?",
                role.get("role_id")).stream().map(row -> row.get("permission")).toList();
            assertThat(permissions).as(declared.code()).containsExactlyInAnyOrderElementsOf(declared.permissions());
        }
    }

    private void assertAccounts() {
        Map<Object, Object> accounts = query("SELECT account_code, account_type FROM ledger_account_version")
            .stream().collect(Collectors.toMap(row -> row.get("account_code"), row -> row.get("account_type")));
        assertThat(accounts).containsExactlyInAnyOrderEntriesOf(Map.of("1110", "ASSET", "1120", "ASSET",
            "2110", "LIABILITY", "2120", "LIABILITY", "2130", "LIABILITY", "4110", "REVENUE", "5110", "EXPENSE"));
    }

    private void assertCountries() {
        assertThat(query("SELECT count(*) AS n, count(DISTINCT code) AS codes FROM qb_country_version").getFirst())
            .containsEntry("n", 249L).containsEntry("codes", 249L);
        Map<String, Object> japan = query("SELECT name ->> 'en' AS en, name ->> 'zh' AS zh, name ->> 'ja' AS ja, "
            + "regions FROM qb_country_version WHERE code = 'JP'").getFirst();
        assertThat(japan).containsEntry("en", "Japan").containsEntry("zh", "日本").containsEntry("ja", "日本")
            .containsEntry("regions", "GLOBAL,JP,ASIA");
        assertThat(query("SELECT regions FROM qb_country_version WHERE code = 'RU'").getFirst())
            .containsEntry("regions", "GLOBAL,EUROPE,ASIA");
    }

    private void assertParams() {
        Map<Object, Object> values = new TreeMap<>(query("SELECT param_key, param_value FROM sys_param_version "
            + "WHERE param_key LIKE 'qb.%'").stream()
            .collect(Collectors.toMap(row -> row.get("param_key"), row -> row.get("param_value"))));
        assertThat(values).containsExactlyInAnyOrderEntriesOf(Map.of(
            QbParams.CREATOR_FEE_RATE, "0.0000", QbParams.TRANSFER_THRESHOLD, "1000",
            QbParams.TRANSFER_REVIEW_ABOVE, "50000", QbParams.TRANSFER_ENABLED, "false",
            QbParams.REVIEW_SPONSOR_AUTO, "false", QbParams.REVIEW_PUBLICATION_AUTO, "false",
            QbParams.AI_MODEL, "gpt-4o-mini", QbParams.APP_MIN_VERSION, "1.0.0"));
    }

    /** Rows of every table QB_SETUP writes, by table. */
    private static Map<String, Long> rowCounts() {
        Map<String, Long> counts = new TreeMap<>();
        for (String table : List.of("sec_role_version", "sec_role_permission_version", "ledger_account_version",
            "sys_param_version", "qb_country_version")) {
            counts.put(table, ((Number) query("SELECT count(*) AS n FROM " + table).getFirst().get("n")).longValue());
        }
        return counts;
    }

    /** The table was only ever inserted into, and carries the platform's guard against updates and deletes. */
    private static void assertOnlyInserted(String table) {
        assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed FROM pg_stat_user_tables "
            + "WHERE schemaname = current_schema() AND relname = ?", table)).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).isZero());
        assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
            + "AND c.relname = ? AND NOT t.tgisinternal", table)).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> setup(String authorization) {
        var exchange = post(authorization).expectBody(MAP).returnResult();
        assertThat(exchange.getStatus().value()).as("QB_SETUP answered " + exchange.getResponseBody()).isEqualTo(200);
        return (Map<String, Object>) exchange.getResponseBody().get("output");
    }

    private WebTestClient.ResponseSpec post(String authorization) {
        return client.post().uri("/api/processes/QB_SETUP/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, authorization).bodyValue(Map.of()).exchange();
    }
}
