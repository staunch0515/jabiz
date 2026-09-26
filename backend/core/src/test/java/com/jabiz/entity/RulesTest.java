package com.jabiz.entity;

import com.jabiz.context.RequestContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link Rules}: each factory's exported spec and server predicate come from the same parameters. */
class RulesTest {

    private static final Instant NOW = Instant.parse("2026-01-31T09:00:00Z");
    private static final ValidationContext CTX = new ValidationContext(
        Clock.fixed(NOW, ZoneOffset.UTC), RequestContext.system(Locale.ENGLISH, "t"));

    private static FieldDefinition field(Consumer<FieldBuilder> rule) {
        return EntityDefinition.define("Sample", eb -> {
            eb.physicalTable("t_sample");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:sample"));
            eb.field("value", f -> f.physicalColumn("f_value").apply(rule));
        }).field("value");
    }

    private static boolean passes(FieldDefinition field, Object value) {
        return field.rules().getFirst().isSatisfiedBy(value, CTX);
    }

    @Test
    void rangeChecksInclusiveBoundsOnDecimals() {
        FieldDefinition f = field(Rules.range("R", BigDecimal.ONE, new BigDecimal("10")));
        assertThat(f.ruleSpecs()).containsExactly(new RuleSpec("R", "RANGE",
            Map.of("min", BigDecimal.ONE, "max", new BigDecimal("10"))));
        assertThat(passes(f, new BigDecimal("1"))).isTrue();
        assertThat(passes(f, new BigDecimal("10.00"))).isTrue();
        assertThat(passes(f, new BigDecimal("0.99"))).isFalse();
        assertThat(passes(f, new BigDecimal("10.01"))).isFalse();
        assertThat(passes(f, "5")).isFalse();

        FieldDefinition open = field(Rules.range("R", null, BigDecimal.ZERO));
        assertThat(open.ruleSpecs().getFirst().params()).isEqualTo(Map.of("max", BigDecimal.ZERO));
        assertThat(passes(open, new BigDecimal("-5"))).isTrue();
        assertThat(passes(open, new BigDecimal("0.1"))).isFalse();
    }

    @Test
    void scaleIgnoresTrailingZeros() {
        FieldDefinition f = field(Rules.scale("S", 2));
        assertThat(f.ruleSpecs().getFirst()).isEqualTo(new RuleSpec("S", "SCALE", Map.of("scale", 2)));
        assertThat(passes(f, new BigDecimal("1.25"))).isTrue();
        assertThat(passes(f, new BigDecimal("1.2500"))).isTrue();
        assertThat(passes(f, new BigDecimal("100"))).isTrue();
        assertThat(passes(f, new BigDecimal("1.255"))).isFalse();
        assertThat(passes(f, 1)).isFalse();
    }

    @Test
    void lengthCountsCodePoints() {
        FieldDefinition f = field(Rules.length("L", 2, 3));
        assertThat(f.ruleSpecs().getFirst().params()).isEqualTo(Map.of("min", 2, "max", 3));
        assertThat(passes(f, "ab")).isTrue();
        assertThat(passes(f, "😀😀😀")).isTrue();
        assertThat(passes(f, "a")).isFalse();
        assertThat(passes(f, "abcd")).isFalse();
        assertThat(passes(f, 12)).isFalse();
        assertThat(field(Rules.length("L", null, 1)).ruleSpecs().getFirst().params()).isEqualTo(Map.of("max", 1));
        assertThat(field(Rules.length("L", 1, null)).ruleSpecs().getFirst().params()).isEqualTo(Map.of("min", 1));
    }

    @Test
    void patternMatchesTheWholeText() {
        FieldDefinition f = field(Rules.pattern("P", "[A-Z]{3}"));
        assertThat(f.ruleSpecs().getFirst()).isEqualTo(new RuleSpec("P", "PATTERN", Map.of("regex", "[A-Z]{3}")));
        assertThat(passes(f, "ABC")).isTrue();
        assertThat(passes(f, "ABCD")).isFalse();
        assertThat(passes(f, "xABC")).isFalse();
        assertThat(passes(f, 1)).isFalse();
    }

    @Test
    void notFutureUsesTheClockAndTolerance() {
        FieldDefinition f = field(Rules.notFuture("F", 60));
        assertThat(f.ruleSpecs().getFirst().params()).isEqualTo(Map.of("toleranceSeconds", 60));
        assertThat(passes(f, NOW.plusSeconds(60))).isTrue();
        assertThat(passes(f, NOW.minusSeconds(3600))).isTrue();
        assertThat(passes(f, NOW.plusSeconds(61))).isFalse();
        assertThat(passes(f, "2026-01-01T00:00:00Z")).isFalse();
    }

    @Test
    void notBlankRejectsBlankTextOnly() {
        FieldDefinition f = field(Rules.notBlank("B"));
        assertThat(f.ruleSpecs().getFirst()).isEqualTo(new RuleSpec("B", "REQUIRED", Map.of()));
        assertThat(passes(f, "x")).isTrue();
        assertThat(passes(f, " \t")).isFalse();
        assertThat(passes(f, "")).isFalse();
        assertThat(passes(f, 5)).isTrue();
    }

    @Test
    void invalidParametersFailAtDeclaration() {
        assertThatThrownBy(() -> Rules.range("R", null, null)).hasMessageContaining("min or max");
        assertThatThrownBy(() -> Rules.range("R", BigDecimal.TEN, BigDecimal.ONE)).hasMessageContaining("greater");
        assertThatThrownBy(() -> Rules.scale("S", -1)).hasMessageContaining("negative");
        assertThatThrownBy(() -> Rules.length("L", null, null)).hasMessageContaining("min or max");
        assertThatThrownBy(() -> Rules.length("L", 3, 2)).hasMessageContaining("invalid");
        assertThatThrownBy(() -> Rules.length("L", -1, null)).hasMessageContaining("invalid");
        assertThatThrownBy(() -> Rules.notFuture("F", -1)).hasMessageContaining("negative");
        assertThatThrownBy(() -> Rules.pattern("P", "(?<=a)b")).hasMessageContaining("JavaScript");
    }

    @Test
    void rulesRunInTheValidatorWithTheirParametersAsMessageArguments() {
        EntityDefinition def = EntityDefinition.define("Sample", eb -> {
            eb.physicalTable("t_sample");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:sample"));
            eb.field("amount", f -> f.physicalColumn("f_amount").asMonetary("JPY", 0)
                .apply(Rules.range("AMOUNT_MIN", BigDecimal.ONE, null)));
        });
        List<Violation> violations = EntityValidator.check(def, Map.of("amount", "0"), CTX, false).violations();
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.ruleCode()).isEqualTo("AMOUNT_MIN");
            assertThat(v.params()).containsEntry("min", BigDecimal.ONE);
        });
    }
}
