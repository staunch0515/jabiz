package com.jabiz.quizbuks.it;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-M12 review M6: a setting of the operators' share that is not published refuses the settlement (422, as a plan's
 * missing terms do), and so does one that is not valid; the declared value is never assumed.
 */
class OperatorSettlementSettingsIT extends CreditNoteItSupport {

    /** The installation publishes every proposal but the operators' share settings, which stay unpublished. */
    @Override
    @SuppressWarnings("unchecked")
    protected void publishProposals(Map<String, Object> setupOutput) {
        List<Object> ids = (List<Object>) setupOutput.get("proposals");
        List<String> keys = (List<String>) setupOutput.get("paramsProposed");
        for (int i = 0; i < ids.size(); i++) {
            if (i < keys.size() && QbParams.OPERATOR_SHARE.contains(keys.get(i))) {
                continue;
            }
            run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", ids.get(i)));
        }
    }

    /** Creates a setting (a change that creates a parameter gives its kind), with four eyes. */
    private void create(String key, String value) {
        QbParams.Param declared = QbParams.ALL.stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "valueKind", declared.valueKind(), "value", value, "description",
                declared.description()), "reason", "F-M12 settings test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    private BusinessRuleViolationException refusedSettlement(String operatorId) {
        BusinessRuleViolationException[] caught = new BusinessRuleViolationException[1];
        assertThatThrownBy(() -> systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month",
            "2027-03"))).isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> caught[0] = e);
        return caught[0];
    }

    private static Violation only(BusinessRuleViolationException e) {
        assertThat(e.violations()).hasSize(1);
        return e.violations().getFirst();
    }

    @Test
    void anUnpublishedSettingRefusesAndAnInvalidOneRefusesWhateverTheDeclaredValue() {
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorId = newOperator(TestTokens.bearer(tokens, "fm12-settings-hq", QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ), unique("Settings"));
        // Nothing of F-M12 is published in this schema: the first setting read is named.
        clock.set(Instant.parse("2027-04-02T00:00:00Z"));
        Violation missing = only(refusedSettlement(operatorId));
        assertThat(missing.ruleCode()).isEqualTo(OperatorCodes.NO_SHARE_SETTING);
        assertThat(missing.params()).containsEntry("param", QbParams.OPERATOR_SHARE_KINDS);
        assertThat(query("SELECT 1 FROM qb_operator_settlement WHERE operator_id = ?", operatorId)).isEmpty();

        // All published but one: that one is named.
        Instant at = Instant.parse("2027-04-02T01:00:00Z");
        clock.set(at);
        int step = 0;
        for (String key : QbParams.OPERATOR_SHARE) {
            if (key.equals(QbParams.operatorGiftTreatment("LIGHT"))) {
                continue;
            }
            clock.set(at.plusSeconds(60L * ++step));
            create(key, QbParams.defaultOf(key));
        }
        clock.set(Instant.parse("2027-04-02T02:00:00Z"));
        Violation light = only(refusedSettlement(operatorId));
        assertThat(light.ruleCode()).isEqualTo(OperatorCodes.NO_SHARE_SETTING);
        assertThat(light.params()).containsEntry("param", QbParams.operatorGiftTreatment("LIGHT"));

        // The last one is created with a value that is not valid.
        clock.set(Instant.parse("2027-04-02T02:10:00Z"));
        create(QbParams.operatorGiftTreatment("LIGHT"), "BOTH");
        clock.set(Instant.parse("2027-04-02T02:20:00Z"));
        Violation both = only(refusedSettlement(operatorId));
        assertThat(both.ruleCode()).isEqualTo(OperatorCodes.SHARE_SETTING_INVALID);
        assertThat(both.params()).containsEntry("param", QbParams.operatorGiftTreatment("LIGHT"));

        // Invalid values, one after the other (each later in time than the one before it).
        String[][] bad = {{QbParams.operatorGiftTreatment("LIGHT"), "BOTH"},
            {QbParams.OPERATOR_CREDIT_RECOVERY_RATE, "1.5"}, {QbParams.OPERATOR_CREDIT_RECOVERY_RATE, "-0.1"},
            {QbParams.OPERATOR_SHARE_KINDS, "MONTHLY;MANUAL"}, {QbParams.OPERATOR_SHARE_KINDS, " , "}};
        Instant when = Instant.parse("2027-04-02T03:00:00Z");
        clock.set(when);
        setControlledParam(QbParams.operatorGiftTreatment("LIGHT"), "PLATFORM");
        for (String[] change : bad) {
            when = when.plusSeconds(600);
            clock.set(when);
            setControlledParam(change[0], change[1]);
            clock.set(when.plusSeconds(60));
            Violation invalid = only(refusedSettlement(operatorId));
            assertThat(invalid.ruleCode()).as(change[0] + "=" + change[1]).isEqualTo(
                OperatorCodes.SHARE_SETTING_INVALID);
            assertThat(invalid.params()).containsEntry("param", change[0]);
            // Put right again, so that the next change is the only one wrong.
            when = when.plusSeconds(1200);
            clock.set(when);
            setControlledParam(change[0], change[0].equals(QbParams.operatorGiftTreatment("LIGHT")) ? "PLATFORM"
                : QbParams.defaultOf(change[0]));
        }
        // Right at last: it settles.
        clock.set(when.plusSeconds(600));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2027-03"));
        assertThat(query("SELECT 1 FROM qb_operator_settlement WHERE operator_id = ?", operatorId)).hasSize(1);
    }
}
