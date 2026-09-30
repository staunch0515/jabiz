package com.jabiz.approval;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalConditionTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final ApprovalSubject JOURNAL = ApprovalSubject.define("fin.journal", s -> s
        .number("amount").text("source").bool("manual"));

    static ApprovalCondition parse(String json) {
        return ApprovalCondition.parse(json == null ? null : JSON.readValue(json, Object.class), JOURNAL);
    }

    static Map<String, Object> facts(Map<String, ?> given) {
        return JOURNAL.normalizeFacts(given);
    }

    @Test
    void anEmptyConditionAlwaysApplies() {
        assertThat(parse(null).test(Map.of())).isTrue();
        assertThat(parse("{}").test(Map.of())).isTrue();
    }

    @Test
    void numbersCompareByValueWhateverTheirScale() {
        ApprovalCondition gte = parse("{\"fact\":\"amount\",\"op\":\"gte\",\"value\":10000}");
        assertThat(gte.test(facts(Map.of("amount", new BigDecimal("10000.00"))))).isTrue();
        assertThat(gte.test(facts(Map.of("amount", 9999.99)))).isFalse();
        assertThat(parse("{\"fact\":\"amount\",\"op\":\"gt\",\"value\":1}").test(facts(Map.of("amount", 1)))).isFalse();
        assertThat(parse("{\"fact\":\"amount\",\"op\":\"lt\",\"value\":1}").test(facts(Map.of("amount", 0L)))).isTrue();
        assertThat(parse("{\"fact\":\"amount\",\"op\":\"lte\",\"value\":1.5}").test(facts(Map.of("amount", 1.5f))))
            .isTrue();
        assertThat(parse("{\"fact\":\"amount\",\"op\":\"eq\",\"value\":2}").test(facts(Map.of("amount", 2.000))))
            .isTrue();
        assertThat(parse("{\"fact\":\"amount\",\"op\":\"ne\",\"value\":2}").test(facts(Map.of("amount", 3))))
            .isTrue();
        assertThat(parse("{\"fact\":\"amount\",\"op\":\"in\",\"value\":[1,2.0]}").test(facts(Map.of("amount", 2))))
            .isTrue();
    }

    @Test
    void textsAndBooleansCompareForEqualityAndMembership() {
        assertThat(parse("{\"fact\":\"source\",\"op\":\"in\",\"value\":[\"MANUAL\",\"IMPORT\"]}")
            .test(facts(Map.of("source", "IMPORT")))).isTrue();
        assertThat(parse("{\"fact\":\"source\",\"op\":\"notIn\",\"value\":[\"MANUAL\"]}")
            .test(facts(Map.of("source", "MANUAL")))).isFalse();
        assertThat(parse("{\"fact\":\"manual\",\"op\":\"eq\",\"value\":true}").test(facts(Map.of("manual", true))))
            .isTrue();
        assertThat(parse("{\"fact\":\"manual\",\"op\":\"ne\",\"value\":true}").test(facts(Map.of("manual", true))))
            .isFalse();
    }

    @Test
    void aMissingFactMakesEveryComparisonFalse() {
        assertThat(parse("{\"fact\":\"amount\",\"op\":\"ne\",\"value\":1}").test(Map.of())).isFalse();
        assertThat(parse("{\"fact\":\"source\",\"op\":\"notIn\",\"value\":[\"A\"]}").test(Map.of())).isFalse();
    }

    @Test
    void allAndAnyNest() {
        ApprovalCondition condition = parse("""
            {"all": [{"fact": "amount", "op": "gte", "value": 10000},
                     {"any": [{"fact": "source", "op": "eq", "value": "MANUAL"},
                              {"fact": "manual", "op": "eq", "value": true}]}]}""");
        assertThat(condition.test(facts(Map.of("amount", 20000, "source", "MANUAL")))).isTrue();
        assertThat(condition.test(facts(Map.of("amount", 20000, "source", "FEED", "manual", true)))).isTrue();
        assertThat(condition.test(facts(Map.of("amount", 20000, "source", "FEED", "manual", false)))).isFalse();
        assertThat(condition.test(facts(Map.of("amount", 100, "source", "MANUAL")))).isFalse();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        assertThatThrownBy(() -> parse("""
            {"all": [{"fact": "nope", "op": "eq", "value": 1},
                     {"fact": "amount", "op": "like", "value": 1},
                     {"fact": "source", "op": "gt", "value": "A"},
                     {"fact": "manual", "op": "in", "value": [true]},
                     {"fact": "amount", "op": "eq", "value": "ten"},
                     {"fact": "amount", "op": "in", "value": []},
                     {"fact": "amount", "op": "in", "value": [1, "x"]},
                     {"fact": "amount", "op": "eq", "value": 1, "extra": 2},
                     "text",
                     {"any": []},
                     {"all": [], "any": []}]}"""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fact 'nope' is not declared")
            .hasMessageContaining("unknown operator 'like'")
            .hasMessageContaining("operator gt does not apply to TEXT")
            .hasMessageContaining("operator in does not apply to BOOLEAN")
            .hasMessageContaining("value ten is not NUMBER")
            .hasMessageContaining("in takes a non-empty list")
            .hasMessageContaining("value x is not NUMBER")
            .hasMessageContaining("unknown key 'extra'")
            .hasMessageContaining("condition.all[8] must be an object")
            .hasMessageContaining("condition.all[9].any must be a non-empty list")
            .hasMessageContaining("'all' stands alone");
    }

    @Test
    void depthAndSizeAreLimited() {
        String deep = "{\"fact\":\"amount\",\"op\":\"eq\",\"value\":1}";
        for (int i = 0; i < ApprovalCondition.MAX_DEPTH + 1; i++) {
            deep = "{\"all\":[" + deep + "]}";
        }
        String tooDeep = deep;
        assertThatThrownBy(() -> parse(tooDeep)).hasMessageContaining("nested deeper than");
        String many = "{\"any\":[" + String.join(",", java.util.Collections.nCopies(
            ApprovalCondition.MAX_COMPARISONS + 1, "{\"fact\":\"amount\",\"op\":\"eq\",\"value\":1}")) + "]}";
        assertThatThrownBy(() -> parse(many)).hasMessageContaining("more than 100 comparisons");
    }

    @Test
    void subjectsCheckTheirNamesAndFacts() {
        assertThatThrownBy(() -> ApprovalSubject.define("Fin", s -> {})).hasMessageContaining("must match");
        assertThatThrownBy(() -> ApprovalSubject.define("fin", s -> s.number("a").text("a")))
            .hasMessageContaining("declared twice");
        assertThatThrownBy(() -> ApprovalSubject.define("fin", s -> s.number("1a")))
            .hasMessageContaining("must match");
        assertThatThrownBy(() -> JOURNAL.normalizeFacts(Map.of("amount", "10", "other", 1)))
            .hasMessageContaining("fact 'amount' of approval subject fin.journal must be NUMBER, not String")
            .hasMessageContaining("fact 'other' is not declared");
        java.util.Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("amount", null);
        assertThat(JOURNAL.normalizeFacts(withNull)).isEmpty();
        assertThat(JOURNAL.normalizeFacts(Map.of("amount", java.math.BigInteger.TEN, "manual", false)))
            .containsEntry("amount", BigDecimal.TEN).containsEntry("manual", false);
        assertThat(JOURNAL.normalizeFacts(Map.of("amount", (short) 1)).get("amount")).isEqualTo(BigDecimal.ONE);
        assertThat(JOURNAL.normalizeFacts(Map.of("amount", (byte) 1)).get("amount")).isEqualTo(BigDecimal.ONE);
        assertThat(JOURNAL.facts().keySet()).containsExactly("amount", "source", "manual");
        assertThat(List.of(FactType.values())).hasSize(3);
    }
}
