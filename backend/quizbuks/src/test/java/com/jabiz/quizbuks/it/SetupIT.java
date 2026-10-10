package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code QB_SETUP} creates the roles with their permissions, the ledger accounts, every country and the business
 * parameters; run again, it adds nothing and changes nothing (docs/quizbuks/plans/Q1-skeleton.md).
 */
class SetupIT extends QbItSupport {

    @Test
    void setsUpOnceAndAddsNothingWhenRunAgain() {
        Map<String, Object> first = setup();
        assertThat(first.get("rolesCreated")).isEqualTo(QbRoles.all().stream().map(QbRoles.Role::code).toList());
        int granted = QbRoles.all().stream().mapToInt(role -> role.permissions().size()).sum();
        assertThat(first.get("permissionsAdded")).isEqualTo(granted);
        assertThat(first.get("accountsOpened"))
            .isEqualTo(QbLedger.ACCOUNTS.stream().map(QbLedger.Account::code).toList());
        assertThat(first.get("countriesAdded")).isEqualTo(249);
        List<String> keys = QbParams.ALL.stream().map(QbParams.Param::key).toList();
        assertThat(first.get("paramsCreated"))
            .isEqualTo(keys.stream().filter(key -> !QbParams.CONTROLLED.contains(key)).toList());
        // Controlled parameters cannot be created directly (decision D40): they are proposed, and another
        // administrator publishes them.
        assertThat(first.get("paramsProposed")).isEqualTo(QbParams.CONTROLLED);
        assertThat((List<?>) first.get("proposals")).hasSize(QbParams.CONTROLLED.size());
        assertThat(query("SELECT count(*) AS n FROM sys_param_version WHERE param_key = ?",
            QbParams.TRANSFER_THRESHOLD).getFirst()).containsEntry("n", 0L);
        publishProposals(first);

        Map<String, Long> before = rowCounts();
        // One record of each item setup made: it never adds the same item again.
        assertThat(before.get("qb_setup_record_version")).isEqualTo((long) QbRoles.all().size() + granted
            + QbLedger.ACCOUNTS.size() + QbParams.ALL.size() + 249);
        assertRoles();
        assertAccounts();
        assertCountries();
        assertParams();

        Map<String, Object> second = setup();
        assertThat(second).containsEntry("rolesCreated", List.of()).containsEntry("permissionsAdded", 0)
            .containsEntry("accountsOpened", List.of()).containsEntry("countriesAdded", 0)
            .containsEntry("paramsCreated", List.of()).containsEntry("paramsProposed", List.of())
            .containsEntry("proposals", List.of());
        assertThat(rowCounts()).isEqualTo(before);
        assertCountries();

        assertOnlyInserted("qb_country_version");
        assertOnlyInserted("qb_setup_record_version");
    }

    @Test
    void needsThePermissionAndASecondFactor() {
        runSetup(TestTokens.bearer(tokens, "visitor", QbPermissions.COUNTRY_READ)).expectStatus().isForbidden();
        runSetup(TestTokens.withoutMfa(tokens, "installer", QbPermissions.SETUP)).expectStatus().isForbidden();
    }

    @Test
    void controlledParametersChangeOnlyWithFourEyes() {
        publishProposals(setup());
        for (String key : QbParams.CONTROLLED) {
            // A valid value of the parameter's kind: refused for being controlled, not for its value.
            String value = QbParams.ALL.stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow()
                .valueKind().get("type").equals("bool") ? "true" : "1";
            Map<String, Object> refused = client.post().uri("/api/processes/PARAM_SET/latest")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Authorization", admin()).bodyValue(Map.of("key", key, "value", value)).exchange()
                .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
            assertThat(refused.toString()).as(key).contains("PARAM_CONTROLLED");
        }
        // The others are changed as before.
        client.post().uri("/api/processes/PARAM_SET/latest")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("Authorization", admin())
            .bodyValue(Map.of("key", QbParams.AI_MODEL, "value", "gpt-x")).exchange().expectStatus().isOk();
    }

    @Test
    void theRegionsColumnHoldsEveryRegion() {
        // Regions.MAX_LENGTH grows with the regions; the column must grow with it (a new migration).
        assertThat(query("SELECT character_maximum_length AS n FROM information_schema.columns "
            + "WHERE table_schema = current_schema() AND table_name = 'qb_country_version' AND column_name = 'regions'"))
            .singleElement().satisfies(row -> assertThat(((Number) row.get("n")).intValue())
                .isGreaterThanOrEqualTo(Regions.MAX_LENGTH));
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
            "sys_param_version", "qb_country_version", "qb_setup_record_version")) {
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
}
