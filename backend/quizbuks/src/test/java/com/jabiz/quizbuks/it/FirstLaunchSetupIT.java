package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.FirstLaunchSwitches;
import com.jabiz.quizbuks.setup.FirstLaunchSwitchesCheck;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.check.CheckProblem;
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
            .containsEntry(QbParams.TOPUP_DEFAULT_TARGET, "CASH");

        // Before anything is published the Kudos reward switch is "not there": the check says so, once.
        assertThat(check.check()).extracting(CheckProblem::location)
            .containsExactly("parameter " + QbParams.REWARD_KUDOS_ENABLED);

        // The platform's own validation takes them (CASH, 0 and the booleans); publish every one.
        publishProposals(first);
        assertThat(current(QbParams.REWARD_KUDOS_ENABLED)).isEqualTo("false");
        assertThat(current(QbParams.TOPUP_TARGETS)).isEqualTo("CASH");
        // A rate or an amount of 0 is a value the numeric kind takes (written with its scale).
        assertThat(new java.math.BigDecimal(current(QbParams.FEE_MONTHLY))).isZero();
        assertThat(new java.math.BigDecimal(current(QbParams.CREATOR_FEE_RATE))).isZero();
        // The startup check runs its SQL against the database: all off, nothing to say.
        assertThat(check.check()).isEmpty();

        // A second run proposes nothing new, and what an administrator published since stays.
        setControlledParam(QbParams.KUDOS_BUY_ENABLED, "true");
        Map<String, Object> second = setup();
        assertThat(second.get("paramsProposed")).isEqualTo(List.of());
        assertThat(second.get("proposals")).isEqualTo(List.of());
        assertThat(proposedValues()).isEmpty();
        assertThat(current(QbParams.KUDOS_BUY_ENABLED)).isEqualTo("true");
        // ... and the check, reading the same rows, now names it.
        assertThat(check.check()).singleElement().satisfies(problem -> {
            assertThat(problem.isError()).isFalse();
            assertThat(problem.location()).isEqualTo("parameter " + QbParams.KUDOS_BUY_ENABLED);
        });
    }
}
