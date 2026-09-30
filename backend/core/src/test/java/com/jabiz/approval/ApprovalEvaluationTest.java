package com.jabiz.approval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.jabiz.approval.ApprovalConditionTest.JOURNAL;
import static com.jabiz.approval.ApprovalConditionTest.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalEvaluationTest {

    static ApprovalRule rule(String code, int priority, String condition, String levels) {
        return new ApprovalRule("id-" + code, 3, code, ApprovalCondition.parse(JSON.readValue(condition, Object.class),
            JOURNAL), ApprovalLevel.parse(JSON.readValue(levels, Object.class), JOURNAL), priority);
    }

    static final ApprovalRule SMALL = rule("small", 10, "{\"fact\":\"amount\",\"op\":\"lt\",\"value\":1000}", "[]");
    static final ApprovalRule LARGE = rule("large", 20, "{\"fact\":\"amount\",\"op\":\"gte\",\"value\":100000}",
        "[{\"permission\":\"fin.journal.approve\",\"limitFact\":\"amount\"},{\"permission\":\"fin.cfo\"}]");
    static final ApprovalRule MANUAL = rule("manual", 30, "{\"fact\":\"source\",\"op\":\"eq\",\"value\":\"MANUAL\"}",
        "[{\"permission\":\"fin.journal.approve\"}]");

    static ApprovalEvaluation evaluate(Map<String, ?> facts, ApprovalRule... rules) {
        return ApprovalEvaluation.evaluate(List.of(rules), JOURNAL.normalizeFacts(facts));
    }

    @Test
    void theFirstRuleByPriorityThatAppliesDecides() {
        ApprovalEvaluation small = evaluate(Map.of("amount", 10, "source", "MANUAL"), MANUAL, LARGE, SMALL);
        assertThat(small.matched()).isEqualTo(SMALL);
        assertThat(small.required()).isFalse();
        assertThat(small.levels()).isEmpty();
        assertThat(small.versionKeys()).containsExactly("id-small:3", "id-large:3", "id-manual:3");

        ApprovalEvaluation large = evaluate(Map.of("amount", 200000, "source", "MANUAL"), MANUAL, LARGE, SMALL);
        assertThat(large.matched()).isEqualTo(LARGE);
        assertThat(large.required()).isTrue();
        assertThat(large.levels()).containsExactly(new ApprovalLevel("fin.journal.approve", "amount"),
            new ApprovalLevel("fin.cfo", null));

        assertThat(evaluate(Map.of("amount", 5000, "source", "MANUAL"), MANUAL, LARGE, SMALL).matched())
            .isEqualTo(MANUAL);
    }

    @Test
    void noRuleThatAppliesMeansNoApproval() {
        ApprovalEvaluation none = evaluate(Map.of("amount", 5000, "source", "FEED"), MANUAL, LARGE, SMALL);
        assertThat(none.matched()).isNull();
        assertThat(none.required()).isFalse();
        assertThat(evaluate(Map.of()).versionKeys()).isEmpty();
    }

    @Test
    void tiesOfPriorityGoByCode() {
        ApprovalRule b = rule("b", 1, "{}", "[{\"permission\":\"x\"}]");
        ApprovalRule a = rule("a", 1, "{}", "[]");
        assertThat(evaluate(Map.of(), b, a).matched()).isEqualTo(a);
    }

    @Test
    void levelsAreChecked() {
        assertThat(ApprovalLevel.parse(null, JOURNAL)).isEmpty();
        assertThatThrownBy(() -> ApprovalLevel.parse(Map.of(), JOURNAL)).hasMessageContaining("must be a list");
        assertThatThrownBy(() -> ApprovalLevel.parse(JSON.readValue("""
            [{"permission": "*"}, {"permission": "a", "limitFact": "source"}, {"permission": "a", "x": 1}, 3,
             {"permission": "a"}, {"permission": "b"}]""", Object.class), JOURNAL))
            .hasMessageContaining("at most 5 levels")
            .hasMessageContaining("levels[0]: permission '*' must match")
            .hasMessageContaining("levels[1]: limitFact 'source' is not a NUMBER fact")
            .hasMessageContaining("levels[2]: unknown key 'x'")
            .hasMessageContaining("levels[3] must be an object");
        assertThatThrownBy(() -> new ApprovalLevel(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ApprovalRule("r", 1, "c", new ApprovalCondition.Always(), List.of(), 0).versionKey())
            .isEqualTo("r:1");
        assertThat(new ApprovalCondition.All(List.of(new ApprovalCondition.Always())).test(Map.of())).isTrue();
        assertThat(new ApprovalCondition.Any(List.of()).test(Map.of("amount", BigDecimal.ONE))).isFalse();
    }
}
