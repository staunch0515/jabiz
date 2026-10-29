package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.messaging.LegalDrafts;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.identity.SponsorApprovals;
import com.jabiz.quizbuks.publishing.PublicationApprovals;
import com.jabiz.quizbuks.setup.QbApprovalRules;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.setup.QbSodRules;
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
        // Approval rules change only through controlled changes as well: the publication review (Q4) and the
        // sponsor onboarding review (Q2) are proposed.
        // Q10b (L3): the separation of duties too.
        assertThat(first.get("rulesProposed")).isEqualTo(List.of(PublicationApprovals.RULE, SponsorApprovals.RULE,
            com.jabiz.quizbuks.wallet.QbPayouts.REVIEW_RULE, com.jabiz.quizbuks.wallet.BankTopUps.REVIEW_RULE,
            com.jabiz.quizbuks.wallet.ManualCredits.REVIEW_RULE, com.jabiz.quizbuks.operator.OperatorSettlements.REVIEW_RULE,
            QbSodRules.CONTROL.code(), QbSodRules.SECURITY.code()));
        assertThat((List<?>) first.get("proposals")).hasSize(QbParams.CONTROLLED.size() + QbApprovalRules.ALL.size()
            + QbSodRules.ALL.size());
        // Q7b: a draft of each legal text, a marked test placeholder; nothing published.
        assertThat(first.get("legalDraftsCreated")).isEqualTo(QbLegal.KINDS);
        assertThat(query("SELECT kind, title->>'en' AS title FROM qb_legal_doc_version ORDER BY kind"))
            .hasSize(QbLegal.KINDS.size())
            .allSatisfy(row -> assertThat(row.get("title").toString()).startsWith(LegalDrafts.MARKER));
        assertThat(query("SELECT count(*) AS n FROM qb_legal_version_version").getFirst()).containsEntry("n", 0L);
        assertThat(query("SELECT count(*) AS n FROM sys_sod_rule_version").getFirst()).containsEntry("n", 0L);
        assertThat(query("SELECT count(*) AS n FROM sys_approval_rule_version").getFirst()).containsEntry("n", 0L);
        assertThat(query("SELECT count(*) AS n FROM sys_param_version WHERE param_key = ?",
            QbParams.TRANSFER_THRESHOLD).getFirst()).containsEntry("n", 0L);
        publishProposals(first);

        Map<String, Long> before = rowCounts();
        // One record of each item setup made: it never adds the same item again.
        assertThat(before.get("qb_setup_record_version")).isEqualTo((long) QbRoles.all().size() + granted
            + QbLedger.ACCOUNTS.size() + QbParams.ALL.size() + 249 + QbApprovalRules.ALL.size() + QbSodRules.ALL.size()
            + QbLegal.KINDS.size());
        assertThat(query("SELECT rule_code, left_permissions, right_permissions, enabled FROM sys_sod_rule_version "
            + "ORDER BY rule_code")).satisfiesExactly(
                rule -> assertThat(rule).containsEntry("rule_code", QbSodRules.CONTROL.code())
                    .containsEntry("left_permissions", "control.propose")
                    .containsEntry("right_permissions", "control.publish").containsEntry("enabled", true),
                rule -> assertThat(rule).containsEntry("rule_code", QbSodRules.SECURITY.code())
                    .containsEntry("left_permissions", "security.role.write,security.user-role.write")
                    .containsEntry("right_permissions", "control.publish,approval.decide")
                    .containsEntry("enabled", true));
        assertThat(query("SELECT rule_code, subject, enabled, priority, condition, levels "
            + "FROM sys_approval_rule_version ORDER BY rule_code"))
            .satisfiesExactly(
                // Q48b: an operator's settlement is paid only after another administrator approves it.
                rule -> assertThat(rule).containsEntry("rule_code",
                    com.jabiz.quizbuks.operator.OperatorSettlements.REVIEW_RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.operator.OperatorSettlements.APPROVAL_SUBJECT)
                    .containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("operatorPayment"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.TOPUP_BANK_APPROVE)),
                rule -> assertThat(rule).containsEntry("rule_code", PublicationApprovals.RULE)
                    .containsEntry("subject", PublicationApprovals.SUBJECT).containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("autoApprove"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.REVIEW_PUBLICATION)),
                rule -> assertThat(rule).containsEntry("rule_code", SponsorApprovals.RULE)
                    .containsEntry("subject", SponsorApprovals.SUBJECT).containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("autoApprove"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.REVIEW_SPONSOR)),
                // Q13a: every bank top-up finance records, approved by another administrator.
                rule -> assertThat(rule).containsEntry("rule_code", com.jabiz.quizbuks.wallet.BankTopUps.REVIEW_RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.wallet.BankTopUps.APPROVAL_SUBJECT)
                    .containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("bank"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.TOPUP_BANK_APPROVE)),
                // Q18b: every manual credit finance records, approved by another administrator.
                rule -> assertThat(rule).containsEntry("rule_code", com.jabiz.quizbuks.wallet.ManualCredits.REVIEW_RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.wallet.ManualCredits.APPROVAL_SUBJECT)
                    .containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("manual"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.TOPUP_BANK_APPROVE)),
                // Q6b: transfers above the limit, reviewed by finance.
                rule -> assertThat(rule).containsEntry("rule_code", com.jabiz.quizbuks.wallet.QbPayouts.REVIEW_RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.wallet.QbPayouts.APPROVAL_SUBJECT)
                    .containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("needsReview"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.PAYOUT_REVIEW)));
        assertRoles();
        assertAccounts();
        assertCountries();
        assertParams();

        Map<String, Object> second = setup();
        assertThat(second).containsEntry("rolesCreated", List.of()).containsEntry("permissionsAdded", 0)
            .containsEntry("accountsOpened", List.of()).containsEntry("countriesAdded", 0)
            .containsEntry("paramsCreated", List.of()).containsEntry("paramsProposed", List.of())
            .containsEntry("rulesProposed", List.of()).containsEntry("proposals", List.of())
            .containsEntry("legalDraftsCreated", List.of());
        assertThat(rowCounts()).isEqualTo(before);
        assertCountries();

        assertOnlyInserted("qb_country_version");
        assertOnlyInserted("qb_setup_record_version");
        assertOnlyInserted("qb_legal_doc_version");
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
            Map<String, Object> kind = QbParams.ALL.stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow()
                .valueKind();
            Object type = kind.get("type");
            // The commission rate is a fraction below 1; the days (Q10b) are whole.
            // Q8a: a code (the AI model and effort) takes a listed value other than its first one.
            String value = "bool".equals(type) ? "true"
                : "code".equals(type) ? String.valueOf(((List<?>) kind.get("allowedValues")).getLast())
                : "numeric".equals(type) && !Integer.valueOf(0).equals(kind.get("scale")) ? "0.1" : "1";
            Map<String, Object> refused = client.post().uri("/api/processes/PARAM_SET/latest")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Authorization", admin()).bodyValue(Map.of("key", key, "value", value)).exchange()
                .expectBody(MAP).returnResult().getResponseBody();
            assertThat(String.valueOf(refused)).as(key).contains("PARAM_CONTROLLED");
        }
        // The others are changed as before.
        client.post().uri("/api/processes/PARAM_SET/latest")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("Authorization", admin())
            .bodyValue(Map.of("key", QbParams.APP_MIN_VERSION, "value", "1.0.1")).exchange().expectStatus().isOk();
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
            + "WHERE role_code LIKE 'QB\\_%' OR role_code LIKE 'QBS\\_%' ORDER BY role_code");
        assertThat(roles).extracting(row -> row.get("role_code")).containsExactlyInAnyOrderElementsOf(
            QbRoles.all().stream().map(QbRoles.Role::code).toList());
        for (Map<String, Object> role : roles) {
            QbRoles.Role declared = QbRoles.of((String) role.get("role_code"));
            assertThat(role.get("require_mfa")).as(declared.code()).isEqualTo(declared.requireMfa());
            List<Object> permissions = query("SELECT permission FROM sec_role_permission_version WHERE role_id = ?",
                role.get("role_id")).stream().map(row -> row.get("permission")).toList();
            assertThat(permissions).as(declared.code()).containsExactlyInAnyOrderElementsOf(declared.permissions());
            // Q5: takers read the quizzes' files; the answering system processes and the development credit are
            // nobody's.
            if (QbRoles.TAKER.equals(declared.code())) {
                assertThat(permissions).contains(QbPermissions.CONTENT_FILE_READ, QbPermissions.PLAY);
            }
            assertThat(permissions).as(declared.code()).doesNotContain(QbPermissions.PLAY_SYSTEM,
                QbPermissions.WALLET_DEV_CREDIT);
        }
    }

    private void assertAccounts() {
        Map<Object, Object> accounts = query("SELECT account_code, account_type FROM ledger_account_version")
            .stream().collect(Collectors.toMap(row -> row.get("account_code"), row -> row.get("account_type")));
        assertThat(accounts).containsExactlyInAnyOrderEntriesOf(Map.of("1110", "ASSET", "1120", "ASSET",
            "2110", "LIABILITY", "2120", "LIABILITY", "2130", "LIABILITY", "2140", "LIABILITY", "4110", "REVENUE",
            "5110", "EXPENSE", "5120", "EXPENSE", "5130", "EXPENSE"));
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
        Map<Object, Object> expected = new TreeMap<>(Map.ofEntries(
            Map.entry(QbParams.CREATOR_FEE_RATE, "0.0000"), Map.entry(QbParams.TRANSFER_THRESHOLD, "1000"),
            Map.entry(QbParams.TRANSFER_REVIEW_ABOVE, "50000"), Map.entry(QbParams.TRANSFER_ENABLED, "false"),
            Map.entry(QbParams.REVIEW_SPONSOR_AUTO, "false"), Map.entry(QbParams.REVIEW_PUBLICATION_AUTO, "false"),
            Map.entry(QbParams.APP_MIN_VERSION, "1.0.0"),
            Map.entry(QbParams.TOPUP_MIN, "1000"), Map.entry(QbParams.TOPUP_MAX, "1000000"),
            Map.entry(QbParams.TRANSFER_FEE, "0"),
            // Q10b (M3): the hold period and the new sponsors' limit.
            Map.entry(QbParams.TRANSFER_HOLD_DAYS, "7"), Map.entry(QbParams.TOPUP_NEW_SPONSOR_DAYS, "30"),
            Map.entry(QbParams.TOPUP_NEW_SPONSOR_MAX_TOTAL, "100000"),
            // Q9e: the analytics' minimum group (an ordinary parameter).
            Map.entry(QbParams.STATS_MIN_GROUP, "5")));
        // Q9d: the account mapping and the receipts' issuer start empty, for finance to set with four eyes.
        QbParams.JOURNAL_ACCOUNTS.forEach(key -> expected.put(key, ""));
        QbParams.ISSUER.forEach(key -> expected.put(key, ""));
        // Q8a: AI off, Claude Opus 5.5 at effort medium, the limits, the expiry and the estimate's prices.
        expected.putAll(Map.of(QbParams.AI_ENABLED, "false", QbParams.AI_MODEL, "claude-opus-5-5",
            QbParams.AI_EFFORT, "medium", QbParams.AI_SPONSOR_DAILY_JOBS, "20",
            QbParams.AI_SPONSOR_MONTHLY_TOKENS, "2000000", QbParams.AI_GLOBAL_MONTHLY_TOKENS, "50000000",
            QbParams.AI_EXPIRE_DAYS, "7", QbParams.AI_PRICE_INPUT, "4.0000", QbParams.AI_PRICE_OUTPUT, "20.0000"));
        // Q7b: no support address until an administrator sets one.
        expected.put(QbParams.SUPPORT_EMAIL, "quizbuks@gmail.com");
        // Q7b review M1: how far ahead a legal text may be announced.
        expected.put(QbParams.LEGAL_MAX_ANNOUNCE_DAYS, "180");
        // Q7c: the limits of the sponsors' notices, the announcements' default life and the payout reminders.
        expected.put(QbParams.BROADCAST_SPONSOR_MAX, "3");
        expected.put(QbParams.BROADCAST_SPONSOR_INTERVAL, "24");
        expected.put(QbParams.BROADCAST_EXPIRES_DAYS, "30");
        expected.put(QbParams.PAYOUT_REMINDER_DAYS, "30");
        // Q7c review M2: below this reach a sponsor sees no clicks or conversions.
        expected.put(QbParams.BROADCAST_STATS_MIN_AUDIENCE, "50");
        // Q13a: bank top-ups' range, and the receiving account empty until finance sets it with four eyes.
        expected.put(QbParams.TOPUP_BANK_MIN, "1000");
        expected.put(QbParams.TOPUP_BANK_MAX, "10000000");
        QbParams.BANK_RECEIVING.forEach(key -> expected.put(key, ""));
        expected.put(QbParams.WITHDRAWAL_BANK_ENABLED, "false");
        expected.put(QbParams.WITHDRAWAL_BANK_FEE, "0");
        expected.put(QbParams.WITHDRAWAL_BANK_COOLDOWN_HOURS, "72");
        // Q22: the days of cash rewards and prizes.
        expected.put(QbParams.CASH_CONFIRM_DAYS, "7");
        expected.put(QbParams.CASH_PAY_DAYS, "14");
        expected.put(QbParams.REDEEM_DAYS, "30");
        // Q33: the days a sponsor has to send a digital prize by hand.
        expected.put(QbParams.DIGITAL_SEND_DAYS, "3");
        // Q28: the default methods and the highest reward per attempt.
        expected.put(QbParams.REWARD_DEFAULT_METHODS, "PLATFORM");
        expected.put(QbParams.REWARD_MAX_PER_ATTEMPT, "0");
        // Q48: AI prospecting off, its model and effort, the operators' cost cap and rate; the platform's share.
        expected.putAll(Map.of(QbParams.PROSPECT_ENABLED, "false", QbParams.PROSPECT_MODEL, "claude-opus-5-5",
            QbParams.PROSPECT_EFFORT, "medium", QbParams.PROSPECT_OPERATOR_MONTHLY_CAP, "100.0000",
            QbParams.PROSPECT_USD_JPY, "150.00", QbParams.OPERATOR_PLATFORM_SHARE, "0.2000"));
        assertThat(values).containsExactlyInAnyOrderEntriesOf(expected);
    }

    /** Rows of every table QB_SETUP writes, by table. */
    private static Map<String, Long> rowCounts() {
        Map<String, Long> counts = new TreeMap<>();
        for (String table : List.of("sec_role_version", "sec_role_permission_version", "ledger_account_version",
            "sys_param_version", "qb_country_version", "qb_setup_record_version", "sys_approval_rule_version",
            "sys_control_change_version", "qb_legal_doc_version")) {
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
