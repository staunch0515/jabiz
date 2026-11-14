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
 * F-M12 b: the number of days a settlement waits for the steps before it is a controlled setting; not published, or not
 * a whole number of days from 0 to 31, refuses the settlement (422, like part a's settings): nothing is assumed.
 */
class OperatorSettlementReadinessSettingsIT extends CreditNoteItSupport {

    /** Every proposal is published but the readiness setting's. */
    @Override
    @SuppressWarnings("unchecked")
    protected void publishProposals(Map<String, Object> setupOutput) {
        List<Object> ids = (List<Object>) setupOutput.get("proposals");
        List<String> keys = (List<String>) setupOutput.get("paramsProposed");
        for (int i = 0; i < ids.size(); i++) {
            if (i < keys.size() && QbParams.OPERATOR_SETTLEMENT.contains(keys.get(i))) {
                continue;
            }
            run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", ids.get(i)));
        }
    }

    private void create(String key, String value) {
        QbParams.Param declared = QbParams.ALL.stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "valueKind", declared.valueKind(), "value", value, "description",
                declared.description()), "reason", "F-M12 b settings test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    private Violation refused(String operatorId) {
        BusinessRuleViolationException[] caught = new BusinessRuleViolationException[1];
        assertThatThrownBy(() -> systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month",
            "2027-03"))).isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> caught[0] = e);
        assertThat(caught[0].violations()).hasSize(1);
        return caught[0].violations().getFirst();
    }

    @Test
    void anUnpublishedOrInvalidReadinessSettingRefusesTheSettlement() {
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String operatorId = newOperator(TestTokens.bearer(tokens, "fm12b-settings-hq", QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ), unique("Readiness settings"));
        clock.set(Instant.parse("2027-04-02T00:00:00Z"));

        Violation missing = refused(operatorId);
        assertThat(missing.ruleCode()).isEqualTo(OperatorCodes.NO_SHARE_SETTING);
        assertThat(missing.params()).containsEntry("param", QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS);
        assertThat(query("SELECT 1 FROM qb_operator_settlement WHERE operator_id = ?", operatorId)).isEmpty();

        // Not a number of days from 0 to 31: refused, whatever the declared value is.
        Instant when = Instant.parse("2027-04-02T01:00:00Z");
        clock.set(when);
        create(QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS, "32");
        clock.set(when.plusSeconds(600));
        Violation tooMany = refused(operatorId);
        assertThat(tooMany.ruleCode()).isEqualTo(OperatorCodes.SHARE_SETTING_INVALID);
        assertThat(tooMany.params()).containsEntry("param", QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS)
            .containsEntry("value", "32");
        clock.set(when.plusSeconds(1200));
        setControlledParam(QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS, "-1");
        clock.set(when.plusSeconds(1800));
        assertThat(refused(operatorId).ruleCode()).isEqualTo(OperatorCodes.SHARE_SETTING_INVALID);
        assertThat(query("SELECT 1 FROM qb_operator_settlement WHERE operator_id = ?", operatorId)).isEmpty();

        // Right at last: it settles.
        clock.set(when.plusSeconds(2400));
        setControlledParam(QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS, "5");
        clock.set(when.plusSeconds(3000));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2027-03"));
        assertThat(query("SELECT 1 FROM qb_operator_settlement WHERE operator_id = ?", operatorId)).hasSize(1);
    }
}
