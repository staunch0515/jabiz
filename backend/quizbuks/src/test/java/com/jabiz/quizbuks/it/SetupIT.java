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
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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
        assertThat(first.get("permissionsRemoved")).isEqualTo(0);
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
            com.jabiz.quizbuks.page.SponsorPageApprovals.RULE,
            com.jabiz.quizbuks.setup.QbApprovalRules.OPERATOR_PLAN_REVIEW.code(),
            com.jabiz.quizbuks.setup.QbApprovalRules.OPERATOR_WITHDRAWAL_REVIEW.code(),
            com.jabiz.quizbuks.setup.QbApprovalRules.FEE_REQUEST_REVIEW.code(),
            com.jabiz.quizbuks.setup.QbApprovalRules.TREE_REVIEW.code(),
            com.jabiz.quizbuks.setup.QbApprovalRules.TREE_COPY_REVIEW.code(),
            com.jabiz.quizbuks.setup.QbApprovalRules.TREE_PLATFORM_REVIEW.code(),
            com.jabiz.quizbuks.setup.QbApprovalRules.TREE_LIT_REVOKE_REVIEW.code(),
            com.jabiz.quizbuks.setup.QbApprovalRules.SHARED_VIDEO_REVIEW.code(),
            com.jabiz.quizbuks.setup.QbApprovalRules.VERIFICATION_REVIEW.code(),
            QbSodRules.CONTROL.code(), QbSodRules.SECURITY.code(), QbSodRules.MAIL_TEXTS.code()));
        assertThat((List<?>) first.get("proposals")).hasSize(QbParams.CONTROLLED.size() + QbApprovalRules.ALL.size()
            + QbSodRules.ALL.size());
        // Q7b: a draft of each legal text, a marked test placeholder; nothing published.
        assertThat(first.get("legalDraftsCreated")).isEqualTo(QbLegal.KINDS);
        assertThat(query("SELECT kind, title->>'en' AS title FROM qb_legal_doc_version ORDER BY kind"))
            .hasSize(QbLegal.KINDS.size())
            .allSatisfy(row -> assertThat(row.get("title").toString()).startsWith(LegalDrafts.MARKER));
        assertThat(query("SELECT count(*) AS n FROM qb_legal_version_version").getFirst()).containsEntry("n", 0L);
        // F-C10b: a draft of each AI help manual, the repository's text; nothing published.
        assertThat(first.get("helpManualsCreated")).isEqualTo(com.jabiz.quizbuks.help.QbHelp.ENTRIES);
        assertThat(query("SELECT entry, published_body, source_hash FROM qb_help_manual_version ORDER BY entry"))
            .hasSize(2).allSatisfy(row -> assertThat(row.get("published_body")).isNull())
            .allSatisfy(row -> assertThat(row.get("source_hash")).isEqualTo(
                com.jabiz.quizbuks.help.HelpManualDrafts.hash(com.jabiz.quizbuks.help.HelpManualDrafts.of(
                    String.valueOf(row.get("entry"))))));
        assertThat(query("SELECT count(*) AS n FROM sys_sod_rule_version").getFirst()).containsEntry("n", 0L);
        assertThat(query("SELECT count(*) AS n FROM sys_approval_rule_version").getFirst()).containsEntry("n", 0L);
        assertThat(query("SELECT count(*) AS n FROM sys_param_version WHERE param_key = ?",
            QbParams.TRANSFER_THRESHOLD).getFirst()).containsEntry("n", 0L);
        publishProposals(first);

        Map<String, Long> before = rowCounts();
        // One record of each item setup made: it never adds the same item again.
        assertThat(before.get("qb_setup_record_version")).isEqualTo((long) QbRoles.all().size() + granted
            + QbLedger.ACCOUNTS.size() + QbParams.ALL.size() + 249 + QbApprovalRules.ALL.size() + QbSodRules.ALL.size()
            + QbLegal.KINDS.size() + com.jabiz.quizbuks.help.QbHelp.ENTRIES.size());
        assertThat(query("SELECT rule_code, left_permissions, right_permissions, enabled FROM sys_sod_rule_version "
            + "ORDER BY rule_code")).satisfiesExactly(
                rule -> assertThat(rule).containsEntry("rule_code", QbSodRules.CONTROL.code())
                    .containsEntry("left_permissions", "control.propose")
                    .containsEntry("right_permissions", "control.publish").containsEntry("enabled", true),
                // F-C8, F-C9: nobody who manages roles writes the mail texts and the letterhead.
                rule -> assertThat(rule).containsEntry("rule_code", QbSodRules.MAIL_TEXTS.code())
                    .containsEntry("left_permissions", "security.role.write,security.user-role.write")
                    .containsEntry("right_permissions", "mail.text.write").containsEntry("enabled", true),
                rule -> assertThat(rule).containsEntry("rule_code", QbSodRules.SECURITY.code())
                    .containsEntry("left_permissions", "security.role.write,security.user-role.write")
                    .containsEntry("right_permissions", "control.publish,approval.decide")
                    .containsEntry("enabled", true));
        assertThat(query("SELECT rule_code, subject, enabled, priority, condition, levels "
            // In code-point order, whatever the database's collation ("qb.sponsor-…" before "qb.sponsor.…").
            + "FROM sys_approval_rule_version ORDER BY rule_code COLLATE \"C\""))
            .satisfiesExactly(
                // Q87b: a fee by hand or an invoice voided takes another administrator's approval.
                rule -> assertThat(rule).containsEntry("rule_code", com.jabiz.quizbuks.fee.QbFees.REVIEW_RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.fee.QbFees.APPROVAL_SUBJECT)
                    .containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("feeRequest"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.TOPUP_BANK_APPROVE)),
                // Q48e-2: an operator's plan changes only after another administrator approves it.
                rule -> assertThat(rule).containsEntry("rule_code", com.jabiz.quizbuks.operator.OperatorPlans.REVIEW_RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.operator.OperatorPlans.APPROVAL_SUBJECT)
                    .containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("operatorPlan"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.TOPUP_BANK_APPROVE)),
                // Q48b: an operator's settlement is paid only after another administrator approves it.
                rule -> assertThat(rule).containsEntry("rule_code",
                    com.jabiz.quizbuks.operator.OperatorSettlements.REVIEW_RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.operator.OperatorSettlements.APPROVAL_SUBJECT)
                    .containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("operatorPayment"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.TOPUP_BANK_APPROVE)),
                // Q48e-2: an operator's withdrawal is paid only after another administrator approves it.
                rule -> assertThat(rule).containsEntry("rule_code",
                    com.jabiz.quizbuks.operator.OperatorWithdrawals.REVIEW_RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.operator.OperatorWithdrawals.APPROVAL_SUBJECT)
                    .containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("operatorWithdrawal"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.TOPUP_BANK_APPROVE)),
                rule -> assertThat(rule).containsEntry("rule_code", PublicationApprovals.RULE)
                    .containsEntry("subject", PublicationApprovals.SUBJECT).containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("autoApprove"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.REVIEW_PUBLICATION)),
                // F-S12: every shared video, reviewed once by the provider's operator.
                rule -> assertThat(rule).containsEntry("rule_code",
                    com.jabiz.quizbuks.sharedvideo.SharedVideoApprovals.RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.sharedvideo.SharedVideoApprovals.SUBJECT)
                    .containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("shared"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.REVIEW_PUBLICATION)),
                // Q60a: sponsors' pages, reviewed by those who review the onboarding.
                rule -> assertThat(rule).containsEntry("rule_code", com.jabiz.quizbuks.page.SponsorPageApprovals.RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.page.SponsorPageApprovals.SUBJECT)
                    .containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("autoApprove"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.REVIEW_SPONSOR)),
                // F-M3: every company verification, reviewed by the Sponsor's operator.
                rule -> assertThat(rule).containsEntry("rule_code",
                    com.jabiz.quizbuks.verification.VerificationApprovals.RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.verification.VerificationApprovals.SUBJECT)
                    .containsEntry("enabled", true)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("verification"))
                    .satisfies(r -> assertThat(r.get("levels").toString()).contains(QbPermissions.REVIEW_SPONSOR)),
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
                        .contains(QbPermissions.PAYOUT_REVIEW)),
                // F-S2: knowledge maps, one rule by owner: an operator's copy and a Sponsor's map by the operator's
                // members, the platform's template by another administrator who writes templates.
                rule -> assertThat(rule).containsEntry("rule_code", com.jabiz.quizbuks.tree.TreeApprovals.OPERATOR_RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.tree.TreeApprovals.SUBJECT)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("OPERATOR"))
                    .satisfies(r -> assertThat(r.get("levels").toString()).contains(QbPermissions.REVIEW_TREE)),
                // F-S2b2: revoking a lit node, by another member or administrator who revokes.
                rule -> assertThat(rule).containsEntry("rule_code", com.jabiz.quizbuks.tree.TreeRevocations.RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.tree.TreeRevocations.SUBJECT)
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.TREE_LIT_REVOKE)),
                rule -> assertThat(rule).containsEntry("rule_code", com.jabiz.quizbuks.tree.TreeApprovals.PLATFORM_RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.tree.TreeApprovals.SUBJECT)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("PLATFORM"))
                    .satisfies(r -> assertThat(r.get("levels").toString())
                        .contains(QbPermissions.TREE_PLATFORM_WRITE)),
                rule -> assertThat(rule).containsEntry("rule_code", com.jabiz.quizbuks.tree.TreeApprovals.SPONSOR_RULE)
                    .containsEntry("subject", com.jabiz.quizbuks.tree.TreeApprovals.SUBJECT)
                    .satisfies(r -> assertThat(r.get("condition").toString()).contains("SPONSOR"))
                    .satisfies(r -> assertThat(r.get("levels").toString()).contains(QbPermissions.REVIEW_TREE)));
        assertRoles();
        assertAccounts();
        assertCountries();
        assertParams();

        Map<String, Object> second = setup();
        assertThat(second).containsEntry("rolesCreated", List.of()).containsEntry("permissionsAdded", 0)
            .containsEntry("permissionsRemoved", 0)
            .containsEntry("accountsOpened", List.of()).containsEntry("countriesAdded", 0)
            .containsEntry("paramsCreated", List.of()).containsEntry("paramsProposed", List.of())
            .containsEntry("rulesProposed", List.of()).containsEntry("proposals", List.of())
            .containsEntry("legalDraftsCreated", List.of()).containsEntry("helpManualsCreated", List.of());
        assertThat(rowCounts()).isEqualTo(before);
        assertCountries();

        assertOnlyInserted("qb_country_version");
        assertOnlyInserted("qb_setup_record_version");
        assertOnlyInserted("qb_legal_doc_version");
        assertOnlyInserted("qb_help_manual_version");
    }

    /**
     * F-S2c2b (D1): the codes of the certifications are retired; an installation that granted them, to a QuizBuks role
     * or any other, has them removed by the next run (a tombstone, through the platform's write path, audited). A grant
     * of one scheduled to begin later is removed by the first run after it begins; a run with nothing left does nothing.
     */
    @Test
    void retiredCodesAreRemovedFromEveryRole() {
        setup();
        String content = roleId(QbRoles.ADMIN_CONTENT);
        commit(SecurityEntities.ROLE_DATASET, "INSERT", null, null, Map.of("roleCode", "CUSTOM_CERTIFIERS",
            "labels", Map.of("en", "Certifiers"), "enabled", true), null);
        String custom = roleId("CUSTOM_CERTIFIERS");
        for (String permission : QbPermissions.RETIRED) {
            commit(SecurityEntities.ROLE_PERMISSION_DATASET, "INSERT", null, null, Map.of("roleId", content,
                "permission", permission), null);
            commit(SecurityEntities.ROLE_PERMISSION_DATASET, "INSERT", null, null, Map.of("roleId", custom,
                "permission", permission), null);
        }
        Instant later = clock.instant().plus(Duration.ofDays(30));
        String scheduledRole = roleId(QbRoles.ADMIN_FINANCE);
        commit(SecurityEntities.ROLE_PERMISSION_DATASET, "INSERT", null, null, Map.of("roleId", scheduledRole,
            "permission", "qb.certification.admin"), later);
        List<Object> ids = query("SELECT DISTINCT role_permission_id FROM sec_role_permission_version WHERE "
            + "permission IN ('qb.certification.manage', 'qb.certification.admin') AND role_id IN (?::uuid, ?::uuid)",
            content, custom).stream().map(row -> row.get("role_permission_id")).toList();
        assertThat(ids).hasSize(4);

        assertThat(setup()).containsEntry("permissionsRemoved", 4).containsEntry("permissionsAdded", 0);
        for (Object id : ids) {
            assertThat(query("SELECT is_deleted FROM sec_role_permission_version WHERE role_permission_id = ? "
                + "ORDER BY version_no DESC LIMIT 1", id).getFirst().get("is_deleted")).as("%s", id).isEqualTo(true);
            assertThat(query("SELECT 1 FROM sys_audit_record WHERE entity_type = 'SecRolePermission' AND entity_id = ? "
                + "AND action = 'DELETE'", String.valueOf(id))).as("%s", id).hasSize(1);
        }
        // The other grants of the roles stay.
        assertThat(query("SELECT 1 FROM sec_role_permission_version WHERE role_id = ?::uuid AND permission = ? AND NOT "
            + "is_deleted", content, QbRoles.of(QbRoles.ADMIN_CONTENT).permissions().getFirst())).isNotEmpty();

        // Nothing left now (the scheduled one has not begun): nothing to do.
        assertThat(setup()).containsEntry("permissionsRemoved", 0);
        // Once the scheduled grant began, the next run removes it.
        clock.advance(Duration.ofDays(31));
        assertThat(setup()).containsEntry("permissionsRemoved", 1);
        assertThat(setup()).containsEntry("permissionsRemoved", 0);
    }

    private String roleId(String code) {
        return String.valueOf(query("SELECT role_id FROM sec_role_version WHERE role_code = ?", code).getFirst()
            .get("role_id"));
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
            QbParams.Param param = QbParams.ALL.stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
            Map<String, Object> kind = param.valueKind();
            Object type = kind.get("type");
            // The commission rate is a fraction below 1; the days (Q10b) are whole.
            // Q8a: a code (the AI model and effort) takes a listed value other than its first one.
            // A switch is set to what it is not.
            String value = "bool".equals(type) ? String.valueOf(!Boolean.TRUE.equals(param.value()))
                : "code".equals(type) ? String.valueOf(((List<?>) kind.get("allowedValues")).getLast())
                : "numeric".equals(type) && !Integer.valueOf(0).equals(kind.get("scale"))
                    // Q48e-2: a value other than the one in effect (plan 5's share is 0.1).
                    ? (new java.math.BigDecimal(String.valueOf(param.value())).compareTo(new java.math.BigDecimal("0.1")) == 0
                        ? "0.2" : "0.1") : "1";
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

    /**
     * F-M10: {@code qb.help.user-daily} became a controlled parameter. On a system that has it already, {@code QB_SETUP}
     * skips it (a parameter that exists is left as it is): the value stays, nothing is proposed for it, and from then on
     * it changes only through a controlled change. (The parameter was created through a published proposal here: a
     * controlled key cannot be created directly any more, which is what the old installation's row is, for this.)
     */
    @Test
    @SuppressWarnings("unchecked")
    void anExistingDailyLimitKeepsItsValueWhenSetupRunsAgainAndChangesOnlyWithFourEyes() {
        publishProposals(setup());
        Map<String, Object> change = (Map<String, Object>) client.post().uri("/api/processes/CONTROL_CHANGE_PROPOSE/latest")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header("Authorization", TestTokens.bearer(tokens, "super-admin", "control.propose"))
            .bodyValue(Map.of("targetEntity", "SysParam", "values", Map.of("paramKey", QbParams.HELP_USER_DAILY,
                "value", "35"), "reason", "test")).exchange().expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody().get("output");
        client.post().uri("/api/processes/CONTROL_CHANGE_PUBLISH/latest")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header("Authorization", TestTokens.bearer(tokens, "finance-admin", "control.publish"))
            .bodyValue(Map.of("changeId", change.get("changeId"))).exchange().expectStatus().isOk();
        assertThat(paramValue(QbParams.HELP_USER_DAILY)).isEqualTo("35");

        Map<String, Object> again = setup();
        assertThat((List<Object>) again.get("paramsProposed")).doesNotContain(QbParams.HELP_USER_DAILY);
        assertThat((List<Object>) again.get("paramsCreated")).doesNotContain(QbParams.HELP_USER_DAILY);
        // Not back to 20, and not changed by the run.
        assertThat(paramValue(QbParams.HELP_USER_DAILY)).isEqualTo("35");
        Map<String, Object> refused = client.post().uri("/api/processes/PARAM_SET/latest")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("Authorization", admin())
            .bodyValue(Map.of("key", QbParams.HELP_USER_DAILY, "value", "40")).exchange()
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(String.valueOf(refused)).contains("PARAM_CONTROLLED");
        assertThat(paramValue(QbParams.HELP_USER_DAILY)).isEqualTo("35");
    }

    private static String paramValue(String key) {
        return String.valueOf(query("SELECT param_value FROM (SELECT DISTINCT ON (param_key) * FROM sys_param_version ORDER BY "
            + "param_key, version_no DESC) p WHERE param_key = ?", key).getFirst().get("param_value"));
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
        Map<Object, Object> expected = new java.util.HashMap<>(Map.of("1110", "ASSET", "1120", "ASSET",
            "2110", "LIABILITY", "2120", "LIABILITY", "2130", "LIABILITY", "2140", "LIABILITY", "4110", "REVENUE",
            "5110", "EXPENSE", "5120", "EXPENSE", "5130", "EXPENSE"));
        // Q87a: the sponsors' cash accounts.
        expected.put("2160", "LIABILITY");
        // Q87b: the fees receivable, the fee revenue and the tax on it.
        expected.put("1130", "ASSET");
        expected.put("4120", "REVENUE");
        expected.put("2150", "LIABILITY");
        expected.put("2170", "LIABILITY");
        expected.put("2180", "LIABILITY");
        // F-M1: the Sponsors' platform fee Kudos (granted credit).
        expected.put("2190", "LIABILITY");
        // F-M2: the promotion expenses of the platform fee Kudos granted.
        expected.put("5140", "EXPENSE");
        assertThat(accounts).containsExactlyInAnyOrderEntriesOf(expected);
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
        // F-C1: the asset library's limits (ordinary parameters).
        expected.putAll(Map.of(QbParams.ASSETS_MAX_COUNT, "1000", QbParams.ASSETS_MAX_TAGS, "10",
            QbParams.ASSETS_TAG_LENGTH, "30"));
        // F-C2: the free storage (controlled, 500 MB) and how long the daily usage snapshots are kept.
        expected.put(QbParams.USAGE_FREE_BYTES, "524288000");
        expected.put(QbParams.USAGE_RETENTION_DAYS, "800");
        // F-C11: the most products of one sponsor (an ordinary parameter).
        expected.put(QbParams.PRODUCTS_MAX, "50");
        // F-S11: unknown addresses count as mainland China; the sites mainland China plays (controlled).
        expected.put(QbParams.REGION_UNKNOWN_IP_COUNTRY, "CN");
        // F-S10: the home's shelves of short videos (ordinary parameters).
        expected.put(QbParams.FEED_SHORTS_POSITIONS, "3,12");
        expected.put(QbParams.FEED_SHORTS_PER_SHELF, "10");
        // F-S9: the limits of topics (ordinary parameters).
        expected.put(QbParams.TOPIC_MAX_PER_QUIZ, "10");
        expected.put(QbParams.TOPIC_CUSTOM_MAX_LENGTH, "30");
        // F-S6: the question bank's limits (ordinary parameters).
        expected.put(QbParams.CONTENT_MAX_QUESTIONS, "1000");
        expected.put(QbParams.PLAY_MAX_PER_ATTEMPT, "100");
        expected.put(QbParams.CONTENT_IMPORT_BATCH, "200");
        // F-S8: blocking's limit and the smallest group a sponsor is shown a count of (ordinary parameters).
        expected.put(QbParams.BLOCK_MAX_PER_USER, "1000");
        expected.put(QbParams.BLOCK_COUNT_MIN_GROUP, "20");
        // F-S13: the exposures and the pushes (ordinary parameters).
        expected.put(QbParams.EXPOSURE_SECONDS, "10");
        expected.put(QbParams.EXPOSURE_DAILY_CAP, "200");
        expected.put(QbParams.EXPOSURE_PUSH_EVERY, "5");
        expected.put(QbParams.EXPOSURE_REPORT_BATCH, "20");
        expected.put(QbParams.EXPOSURE_REPORT_INTERVAL, "30");
        expected.put(QbParams.EXPOSURE_REPORT_MAX_PER_CALL, "50");
        expected.put(QbParams.EXPOSURE_RECONCILE_MIN_TOLERANCE, "20");
        expected.put(QbParams.EXPOSURE_RECONCILE_TOLERANCE_PERCENT, "5");
        expected.put(QbParams.EXPOSURE_END_GRACE_SEC, "60");
        // F-S14: the reminder of a shared video's use waiting for its provider (ordinary).
        expected.put(QbParams.VIDEO_USE_REMIND_DAYS, "7");
        // F-C10: the AI help (off and the monthly tokens controlled; the rest ordinary).
        expected.put(QbParams.HELP_ENABLED, "false");
        expected.put(QbParams.HELP_MODEL, "claude-haiku-5-5");
        expected.put(QbParams.HELP_USER_DAILY, "20");
        expected.put(QbParams.HELP_GLOBAL_MONTHLY_TOKENS, "20000000");
        // F-C10b: a cache read's weight towards the monthly tokens (controlled).
        expected.put(QbParams.HELP_CACHE_READ_WEIGHT, "0.10");
        // F-M10: the dollar alert of the AI costs (ordinary); the help's daily questions are controlled now.
        expected.put(QbParams.HELP_GLOBAL_MONTHLY_USD_ALERT, "8.00");
        expected.put(QbParams.HELP_HISTORY_TURNS, "6");
        expected.put(QbParams.HELP_RETENTION_DAYS, "180");
        expected.put(QbParams.HELP_MANUAL_MAX_BYTES, "204800");
        expected.put(QbParams.HELP_POLL_SECONDS, "3");
        expected.put(QbParams.HELP_POLL_MAX_MINUTES, "3");
        expected.put(QbParams.HELP_ESCALATION_REMIND_DAYS, "2");
        expected.put(QbParams.HELP_AUTO_CLOSE_DAYS, "14");
        expected.put(QbParams.HELP_QUESTION_MAX_LENGTH, "2000");
        expected.put(QbParams.HELP_ANSWER_MAX_LENGTH, "8000");
        expected.put(QbParams.HELP_MAX_LINKS, "5");
        expected.put(QbParams.VIDEO_USE_MAX_PERIOD_DAYS, "3660");
        // F-C4: the sponsor package (ordinary parameters).
        expected.put(QbParams.PACKAGE_CONFIRM_BYTES, "104857600");
        expected.put(QbParams.PACKAGE_IMPORT_RETENTION_DAYS, "90");
        // F-M2: the platform fee Kudos granted (the reminder ordinary, the rest controlled; the cap not set up).
        expected.put(QbParams.FEE_CREDIT_EXPIRY_NOTICE_DAYS, "14");
        expected.put(QbParams.FEE_CREDIT_GRANT_ENABLED, "false");
        expected.put(QbParams.FEE_CREDIT_GRANT_BASIC, "1000");
        expected.put(QbParams.FEE_CREDIT_GRANT_CERTIFIED, "10000");
        expected.put(QbParams.FEE_CREDIT_VALID_DAYS, "90");
        // F-F3: the Sponsor app's bottom menu on a phone (ordinary).
        expected.put(QbParams.SPONSOR_BOTTOM_BAR, "home,publications,prizes,messages");

        expected.put(QbParams.VIDEO_MAINLAND_SITES, "bilibili,youku,tencent");
        // F-S2: the knowledge maps' limits and the review reminder (ordinary).
        expected.put(QbParams.TREE_MAX_NODES, "200");
        expected.put(QbParams.TREE_MAX_CONDITIONS, "20");
        expected.put(QbParams.TREE_MAX_DEPTH, "20");
        expected.put(QbParams.TREE_SPONSOR_MAX_TREES, "1");
        expected.put(QbParams.TREE_REVIEW_REMIND_AFTER, "3");
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
        // F-S4b: empty, none (a new Sponsor gets what a lit node opens).
        expected.put(QbParams.REWARD_DEFAULT_METHODS, "");
        expected.put(QbParams.REWARD_MAX_PER_ATTEMPT, "0");
        // F-F10a: declared on; the first launch (production, staging) proposes false, which SetupIT does not run as.
        expected.put(QbParams.REWARD_KUDOS_ENABLED, "true");
        // Q48: AI prospecting off, its model and effort, the operators' cost cap and rate.
        expected.putAll(Map.of(QbParams.PROSPECT_ENABLED, "false", QbParams.PROSPECT_MODEL, "claude-opus-5-5",
            QbParams.PROSPECT_EFFORT, "medium", QbParams.PROSPECT_OPERATOR_MONTHLY_CAP, "100.0000",
            QbParams.PROSPECT_USD_JPY, "150.00"));
        // Q70: the referrers' limits (not controlled).
        expected.put(QbParams.REFERRAL_PER_REFERRER_DAILY, "3");
        expected.put(QbParams.REFERRAL_PER_REFERRER_TOTAL, "10");
        // Q82: the conversations' daily cap and quiet days (not controlled).
        expected.put(QbParams.CONVERSATION_DAILY_CAP, "20");
        expected.put(QbParams.CONVERSATION_CLOSE_DAYS, "90");
        // Q47c: charging for the audience rules (off) and the unused-days refund (controlled; F-M5 removed the fallback).
        expected.putAll(Map.of(QbParams.AUDIENCE_BILLING_ENABLED, "false",
            QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "false"));
        // Q87a: where top-ups go, the exchange's switches and the transfers' rate (all controlled).
        expected.putAll(Map.of(QbParams.TOPUP_TARGETS, "KUDOS,CASH", QbParams.TOPUP_DEFAULT_TARGET, "KUDOS",
            QbParams.KUDOS_BUY_ENABLED, "true", QbParams.KUDOS_SELL_ENABLED, "false", QbParams.TRANSFER_FEE_RATE,
            "0.0000"));
        // Q87b: the platform's fees; the declared values keep what the platform did before (no fee, prepaid).
        expected.putAll(Map.of(QbParams.FEE_DUE_DAYS, "30", QbParams.FEE_MONTHLY, "0", QbParams.FEE_TAX_RATE, "0.1000",
            QbParams.FEE_DEFAULT_BILLING_MODE, "PREPAID", QbParams.FEE_OVERDUE_BLOCK, "true"));
        // Q88b: entry fees, off until the administrators turn them on (D-Q88-14).
        expected.put(QbParams.ENTRY_FEE_ENABLED, "false");
        // Q48e-2: the operators' five plans (fixed fee, platform's share) and their withdrawals.
        expected.putAll(Map.of(QbParams.operatorPlanFee("1"), "0", QbParams.operatorPlanShare("1"), "0.5000",
            QbParams.operatorPlanFee("2"), "20000", QbParams.operatorPlanShare("2"), "0.4000",
            QbParams.operatorPlanFee("3"), "40000", QbParams.operatorPlanShare("3"), "0.3000",
            QbParams.operatorPlanFee("4"), "60000", QbParams.operatorPlanShare("4"), "0.2000",
            QbParams.operatorPlanFee("5"), "100000", QbParams.operatorPlanShare("5"), "0.1000"));
        expected.putAll(Map.of(QbParams.OPERATOR_WITHDRAWAL_MIN, "10000",
            QbParams.OPERATOR_WITHDRAWAL_COOLDOWN_HOURS, "24"));
        // F-M12: the operators' share of the platform fees (controlled).
        expected.put(QbParams.OPERATOR_SHARE_KINDS, "MONTHLY,MANUAL,AUDIENCE,DISTRIBUTION,EXPOSURE");
        expected.put(QbParams.OPERATOR_SHARE_AI_COST_IN_BASE, "false");
        expected.put(QbParams.OPERATOR_CREDIT_RECOVERY_RATE, "1.0000");
        expected.put(QbParams.operatorGiftTreatment("ONBOARDING"), "OPERATOR");
        expected.put(QbParams.operatorGiftTreatment("CERTIFIED_TOP_UP"), "OPERATOR");
        expected.put(QbParams.operatorGiftTreatment("MANUAL"), "PLATFORM");
        expected.put(QbParams.operatorGiftTreatment("LIGHT"), "PLATFORM");
        expected.put(QbParams.OPERATOR_GIFT_RECOVER_OUTSIDE_BASE, "false");
        // F-M12 b: how long a settlement waits for the steps before it (controlled).
        expected.put(QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS, "5");
        // F-M12 c: the hours after which the daily check reports a month an operator has no settlement for (controlled).
        expected.put(QbParams.OPERATOR_SETTLEMENT_WAITING_AFTER_HOURS, "48");
        // F-M9: the AI fee (its least purchase is not created here).
        expected.put(QbParams.AI_FEE_ENABLED, "false");
        expected.put(QbParams.AI_FEE_MARKUP, "1.50");
        expected.put(QbParams.AI_FEE_USD_JPY, "150.00");
        expected.put(QbParams.AI_FEE_UNBILLED_AFTER_HOURS, "48");
        expected.put(QbParams.AI_CREDIT_EXPIRED_HELD_AFTER_HOURS, "48");
        // F-M3: how long the company verification's link is good (not controlled).
        expected.put(QbParams.VERIFICATION_LINK_HOURS, "48");
        // F-M5: when an ended publication's open prepayment is a difference (controlled since F-M14).
        expected.put(QbParams.PREPAYMENT_RELEASE_OVERDUE_DAYS, "2");
        // F-F11a: the bytes one import request carries (an ordinary parameter).
        expected.put(QbParams.CONTENT_IMPORT_BATCH_BYTES, "3145728");
        // F-M7 (N9): the daily check of the audience fees not decided (controlled since F-M14).
        expected.put(QbParams.AUDIENCE_REFUND_MISSING_AFTER_HOURS, "30");
        // F-C7: drafting from a website (the switch off; the limits of one request and of a month).
        expected.putAll(Map.of(QbParams.SITE_PROFILE_ENABLED, "false", QbParams.SITE_PROFILE_MONTHLY, "10",
            QbParams.SITE_PROFILE_DAILY, "3", QbParams.SITE_PROFILE_MAX_PAGES, "10",
            QbParams.SITE_PROFILE_QUESTIONS, "5", QbParams.SITE_PROFILE_OPTIONS, "4",
            QbParams.SITE_PROFILE_MATERIALS_MAX, "5", QbParams.SITE_PROFILE_HANDLE_ALTERNATIVES, "3"));
        // F-M6: the distribution fee (off; the headquarters' range of the operators' prices; the final settlement's
        // grace and the daily check's hours).
        expected.putAll(Map.of(QbParams.DISTRIBUTION_BILLING_ENABLED, "false",
            QbParams.DISTRIBUTION_RATE_MIN, "0.0000", QbParams.DISTRIBUTION_RATE_MAX, "1.0000",
            QbParams.DISTRIBUTION_UNIT_FLOOR_MIN, "1.0000", QbParams.DISTRIBUTION_UNIT_FLOOR_MAX, "100.0000",
            QbParams.DISTRIBUTION_UNIT_CEILING_MAX, "1000.0000", QbParams.DISTRIBUTION_MINIMUM_DEPOSIT_MAX, "100000",
            QbParams.DISTRIBUTION_FINAL_END_GRACE_SEC, "3600", QbParams.DISTRIBUTION_UNSETTLED_AFTER_HOURS, "30",
            QbParams.DISTRIBUTION_FINAL_MISSING_AFTER_HOURS, "48"));
        // F-M8: the exposure fee (off; the range of the operators' prices and the fewest exposures bought are
        // placeholders; the grace and the daily check's hours; the biggest top-up; the reminder's share, ordinary).
        expected.putAll(Map.of(QbParams.EXPOSURE_BILLING_ENABLED, "false",
            QbParams.EXPOSURE_UNIT_MIN, "1.0000", QbParams.EXPOSURE_UNIT_MAX, "100.0000",
            QbParams.EXPOSURE_MIN_PURCHASE, "100", QbParams.EXPOSURE_FINAL_END_GRACE_SEC, "3600",
            QbParams.EXPOSURE_UNSETTLED_AFTER_HOURS, "30", QbParams.EXPOSURE_FINAL_MISSING_AFTER_HOURS, "48",
            QbParams.EXPOSURE_TOP_UP_MAX, "1000000", QbParams.EXPOSURE_LOW_RATIO, "0.1000"));
        // F-M13b: the automatic payment of invoices (the switch off, controlled; the limit ordinary).
        expected.putAll(Map.of(QbParams.FEE_AUTO_PAY_MAX_INVOICES, "20", QbParams.FEE_AUTO_PAY_ENABLED, "false",
            QbParams.FEE_AUTO_PAY_MISSED_AFTER_HOURS, "48"));
        // F-M15 b: the wind-up of an erased sponsor's money (off, controlled) and when finance hears it takes long.
        expected.putAll(Map.of(QbParams.ERASURE_CLOSE_ENABLED, "false", QbParams.ERASURE_CLOSE_ALERT_DAYS, "3",
            QbParams.ERASURE_CLOSE_REGISTER_DAYS, "30"));
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
