package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.FirstLaunchSwitches;
import com.jabiz.quizbuks.setup.FirstLaunchSwitchesCheck;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code QB_SETUP} on a system that says it is production (F-F10a, docs/quizbuks/plans/impl/F-F10.md section 5): the
 * controlled parameters are proposed with the first launch's values, key by key as {@link FirstLaunchSwitches} lists
 * them; the values pass the platform's own validation when another administrator publishes them; a second run proposes
 * nothing; a value already published is not set back; and the startup check reads the database with its own SQL.
 */
@TestPropertySource(properties = "jabiz.web.environment=production")
class FirstLaunchSetupIT extends ContentItSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    FirstLaunchSwitchesCheck check;

    /** The values of the proposals waiting, by key, as the controlled change keeps them. */
    private Map<String, String> proposedValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map<String, Object> row : query("SELECT change_values FROM (SELECT DISTINCT ON (change_id) * FROM "
            + "sys_control_change_version ORDER BY change_id, version_no DESC) c WHERE status = 'PROPOSED' "
            + "AND target_entity = 'SysParam'")) {
            JsonNode json = JSON.readTree(String.valueOf(row.get("change_values")));
            values.put(json.get("paramKey").asString(), json.get("value").isString() ? json.get("value").asString()
                : json.get("value").toString());
        }
        return values;
    }

    /** The finance page's card of the switches, as finance reads it. */
    private List<Map<String, Object>> cardRows() {
        return items("qb.admin.first-launch-switches", TestTokens.bearer(tokens, "f-card", "qb.admin.finance.read"),
            Map.of("limit", 100));
    }

    private String current(String key) {
        return (String) query("SELECT param_value FROM sys_param_version WHERE param_key = ? ORDER BY version_no DESC "
            + "LIMIT 1", key).getFirst().get("param_value");
    }

    @Test
    void theProposalsAreTheRegistrysValuesKeyByKeyAndAPublishedValueIsNotSetBack() {
        Map<String, Object> first = setup();
        assertThat(first.get("paramsProposed")).isEqualTo(QbParams.CONTROLLED);
        Map<String, String> proposed = proposedValues();
        assertThat(proposed).containsOnlyKeys(QbParams.CONTROLLED);
        for (QbParams.Param param : QbParams.ALL) {
            if (!QbParams.CONTROLLED.contains(param.key())) {
                continue;
            }
            Object expected = FirstLaunchSwitches.valueFor(param, true);
            // As the platform keeps it: a numeric value with its scale ("0" of a rate is "0.0000").
            assertThat(proposed.get(param.key())).as(param.key())
                .isEqualTo(String.valueOf(com.jabiz.param.ParamKinds.canonical(
                    com.jabiz.param.ParamKinds.parse(param.valueKind()), expected)));
        }
        // The cases the registry exists for.
        assertThat(proposed).containsEntry(QbParams.REWARD_KUDOS_ENABLED, "false")
            .containsEntry(QbParams.KUDOS_BUY_ENABLED, "false").containsEntry(QbParams.TOPUP_TARGETS, "CASH")
            .containsEntry(QbParams.TOPUP_DEFAULT_TARGET, "CASH")
            .containsEntry(QbParams.ENTRY_FEE_ENABLED, "false");
        // F-M9a-2b: the AI fee and the website draft were waiting rows whose key existed (proposed off); they are
        // charged at the launch now, the website draft with the AI fee.
        assertThat(proposed).containsEntry(QbParams.AI_FEE_ENABLED, "true")
            .containsEntry(QbParams.SITE_PROFILE_ENABLED, "true");
        // F-M7: the audience fee is charged at the launch; the unused days are given back only once the requester
        // turns that on (the declared value, off).
        assertThat(proposed).containsEntry(QbParams.AUDIENCE_BILLING_ENABLED, "true")
            .containsEntry(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "false");
        // The three that are on at the first launch, each stated here and not through the registry, so that a row
        // turned off there (or a value read wrongly from it) cannot pass unnoticed. The proposals of AI and help are
        // only published once the AI notice is (operations.md); the test publishes them to see the platform take them.
        assertThat(proposed.get(QbParams.AI_ENABLED)).as(QbParams.AI_ENABLED).isEqualTo("true");
        assertThat(proposed.get(QbParams.HELP_ENABLED)).as(QbParams.HELP_ENABLED).isEqualTo("true");
        assertThat(proposed.get(QbParams.FEE_CREDIT_GRANT_ENABLED)).as(QbParams.FEE_CREDIT_GRANT_ENABLED)
            .isEqualTo("true");

        // Before anything is published the Kudos reward switch is "not there": the check says so, once.
        assertThat(check.check()).extracting(CheckProblem::location)
            .containsExactly("parameter " + QbParams.REWARD_KUDOS_ENABLED);

        // The card's template: nothing is published yet, so a controlled parameter is "not created yet" and holds no
        // value, while an ordinary one (user-daily of the AI help, say) was created by the setup itself.
        List<Map<String, Object>> before = cardRows();
        assertThat(before).hasSize((int) FirstLaunchSwitches.ALL.stream()
            .filter(row -> row.status() == FirstLaunchSwitches.Status.ACTIVE
                || FirstLaunchSwitches.KEY_BEFORE_MEANING.contains(row.key())).count());
        for (Map<String, Object> row : before) {
            boolean controlled = QbParams.CONTROLLED.contains(String.valueOf(row.get("paramKey")));
            assertThat(row).as(String.valueOf(row.get("paramKey"))).containsEntry("paramExists", !controlled);
            if (controlled) {
                assertThat(row.get("value")).as(String.valueOf(row.get("paramKey"))).isNull();
            }
        }
        assertThat(before).anySatisfy(row -> assertThat(row).containsEntry("paramKey", QbParams.REWARD_KUDOS_ENABLED)
            .containsEntry("paramExists", false).containsEntry("expected", "false"));

        // The platform's own validation takes them (CASH, 0 and the booleans); publish every one.
        publishProposals(first);
        // And once published, each row holds the value in force: the first launch's, as the parameter stores it.
        for (Map<String, Object> row : cardRows()) {
            assertThat(row).as(String.valueOf(row.get("paramKey"))).containsEntry("paramExists", true);
            if (row.get("expected") != null) {
                String expected = String.valueOf(row.get("expected"));
                String stored = String.valueOf(row.get("value"));
                assertThat(expected.matches("-?\\d+(\\.\\d+)?") ? new java.math.BigDecimal(stored)
                    .compareTo(new java.math.BigDecimal(expected)) == 0 : stored.equals(expected))
                    .as("%s in force %s, the first launch's %s", row.get("paramKey"), stored, expected).isTrue();
            }
        }
        assertThat(current(QbParams.REWARD_KUDOS_ENABLED)).isEqualTo("false");
        assertThat(current(QbParams.TOPUP_TARGETS)).isEqualTo("CASH");
        assertThat(current(QbParams.ENTRY_FEE_ENABLED)).isEqualTo("false");
        assertThat(current(QbParams.AI_ENABLED)).isEqualTo("true");
        assertThat(current(QbParams.HELP_ENABLED)).isEqualTo("true");
        assertThat(current(QbParams.FEE_CREDIT_GRANT_ENABLED)).isEqualTo("true");
        // A rate or an amount of 0 is a value the numeric kind takes (written with its scale).
        assertThat(new java.math.BigDecimal(current(QbParams.FEE_MONTHLY))).isZero();
        assertThat(new java.math.BigDecimal(current(QbParams.CREATOR_FEE_RATE))).isZero();
        // The startup check runs its SQL against the database: the switches as the first launch has them; only the
        // website draft's three reservation limits are named (F-M9a-2b, #692 re-review L-b: QB_SETUP does not set
        // them, they come from the P95 of real jobs, operations.md 7.56).
        assertThat(check.check()).extracting(CheckProblem::location).containsExactly(
            "parameter " + QbParams.AI_FEE_SITE_CALLS, "parameter " + QbParams.AI_FEE_SITE_PAGES,
            "parameter " + QbParams.AI_FEE_SITE_OUTPUT);

        // A second run proposes nothing new, and what an administrator published since stays.
        setControlledParam(QbParams.KUDOS_BUY_ENABLED, "true");
        Map<String, Object> second = setup();
        assertThat(second.get("paramsProposed")).isEqualTo(List.of());
        assertThat(second.get("proposals")).isEqualTo(List.of());
        assertThat(proposedValues()).isEmpty();
        assertThat(current(QbParams.KUDOS_BUY_ENABLED)).isEqualTo("true");
        // ... and the check, reading the same rows, now names it.
        assertThat(check.check()).filteredOn(problem -> !problem.location().startsWith("parameter qb.ai.fee.site-"))
            .singleElement().satisfies(problem -> {
                assertThat(problem.isError()).isFalse();
                assertThat(problem.location()).isEqualTo("parameter " + QbParams.KUDOS_BUY_ENABLED);
            });
    }
}
