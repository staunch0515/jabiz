package com.jabiz.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class EntityValidatorTest {

    private static final Instant NOW = Instant.parse("2026-01-31T09:00:00Z");
    private static final ValidationContext CTX = new ValidationContext(Clock.fixed(NOW, ZoneOffset.UTC));

    private static final EntityDefinition ORDER = EntityDefinition.define("Order", eb -> {
        eb.physicalTable("t_order");
        eb.primaryKey("orderId");
        eb.field("orderId", f -> f.physicalColumn("f_id").required(true).asSemanticIdentity("urn:test:order"));
        eb.field("amount", f -> f.physicalColumn("f_amount").required(true).asMonetary("JPY", 0)
            .rule("NON_NEGATIVE", "RANGE", Map.of("min", 0), v -> ((BigDecimal) v).signum() >= 0));
        eb.field("note", f -> f.physicalColumn("f_note"));
        eb.field("placedAt", f -> f.physicalColumn("f_placed_at").asTemporal(TemporalRole.EVENT_TIME)
            .rule("NOT_FUTURE", (v, ctx) -> !((Instant) v).isAfter(ctx.clock().instant())));
        eb.field("status", f -> f.physicalColumn("f_status").asCode("urn:test:dict:status", "OPEN", "DONE"));
        eb.field("recordedAt", f -> f.physicalColumn("f_recorded_at").required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("rowVersion", f -> f.physicalColumn("f_version").required(true).asVersion());
    });

    private static Map<String, Object> validInsert() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("orderId", "O-1");
        raw.put("amount", "100");
        return raw;
    }

    @Test
    void validInputIsNormalizedToCanonicalTypes() {
        Map<String, Object> raw = validInsert();
        raw.put("placedAt", "2026-01-31T08:00:00Z");
        raw.put("status", "OPEN");

        EntityValidator.Result result = EntityValidator.check(ORDER, raw, CTX, true);

        assertThat(result.isValid()).isTrue();
        assertThat(result.attributes())
            .containsEntry("amount", new BigDecimal("100"))
            .containsEntry("placedAt", Instant.parse("2026-01-31T08:00:00Z"))
            .containsEntry("status", "OPEN");
    }

    @Test
    void unknownFieldIsReported() {
        Map<String, Object> raw = validInsert();
        raw.put("colour", "red");

        EntityValidator.Result result = EntityValidator.check(ORDER, raw, CTX, true);

        assertThat(result.violations()).extracting(Violation::field, Violation::ruleCode)
            .containsExactly(tuple("colour", "UNKNOWN_FIELD"));
        assertThat(result.attributes()).doesNotContainKey("colour");
    }

    @Test
    void requiredFieldsAreEnforcedOnInsert() {
        EntityValidator.Result result = EntityValidator.check(ORDER, Map.of("note", "x"), CTX, true);

        assertThat(result.violations()).extracting(Violation::field, Violation::ruleCode)
            .containsExactlyInAnyOrder(tuple("orderId", "REQUIRED"), tuple("amount", "REQUIRED"));
    }

    @Test
    void onUpdateOnlySuppliedRequiredFieldsAreChecked() {
        assertThat(EntityValidator.check(ORDER, Map.of("note", "x"), CTX, false).isValid()).isTrue();

        Map<String, Object> clearing = new HashMap<>();
        clearing.put("amount", null);
        assertThat(EntityValidator.check(ORDER, clearing, CTX, false).violations())
            .extracting(Violation::field, Violation::ruleCode)
            .containsExactly(tuple("amount", "REQUIRED"));
    }

    @Test
    void systemManagedFieldsAreIgnoredAndNeverRequired() {
        Map<String, Object> raw = validInsert();
        raw.put("rowVersion", 99);
        raw.put("recordedAt", "not even a timestamp");

        EntityValidator.Result result = EntityValidator.check(ORDER, raw, CTX, true);

        assertThat(result.isValid()).isTrue();
        assertThat(result.attributes()).doesNotContainKeys("rowVersion", "recordedAt");
    }

    @Test
    void invalidValueIsReportedAndItsRulesAreSkipped() {
        Map<String, Object> raw = validInsert();
        raw.put("amount", "lots");

        EntityValidator.Result result = EntityValidator.check(ORDER, raw, CTX, true);

        // Neither REQUIRED nor NON_NEGATIVE is added for a value that could not be converted.
        assertThat(result.violations()).extracting(Violation::field, Violation::ruleCode)
            .containsExactly(tuple("amount", "INVALID_VALUE"));
        assertThat(result.violations().get(0).message()).contains("Field 'amount'");
    }

    @Test
    void dictionaryIsEnforcedOnInput() {
        Map<String, Object> raw = validInsert();
        raw.put("status", "LOST");

        assertThat(EntityValidator.check(ORDER, raw, CTX, true).violations())
            .extracting(Violation::field, Violation::ruleCode)
            .containsExactly(tuple("status", "INVALID_VALUE"));
    }

    @Test
    void failedRuleReportsItsCode() {
        Map<String, Object> raw = validInsert();
        raw.put("amount", -1);

        assertThat(EntityValidator.check(ORDER, raw, CTX, true).violations())
            .extracting(Violation::field, Violation::ruleCode)
            .containsExactly(tuple("amount", "NON_NEGATIVE"));
    }

    @Test
    void rulesReceiveTheClockFromTheValidationContext() {
        Map<String, Object> raw = validInsert();
        raw.put("placedAt", NOW.plusSeconds(1));

        assertThat(EntityValidator.check(ORDER, raw, CTX, true).violations())
            .extracting(Violation::ruleCode)
            .containsExactly("NOT_FUTURE");
    }

    @Test
    void rulesReceiveNormalizedValues() {
        AtomicReference<Object> seen = new AtomicReference<>();
        EntityDefinition def = EntityDefinition.define("Probe", eb -> {
            eb.physicalTable("t_probe");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id"));
            eb.field("at", f -> f.physicalColumn("f_at").asTemporal(TemporalRole.EVENT_TIME)
                .rule("PROBE", v -> { seen.set(v); return true; }));
        });

        EntityValidator.check(def, Map.of("at", "2026-01-31T18:00:00+09:00"), CTX, true);

        assertThat(seen.get()).isEqualTo(NOW);
    }

    @Test
    void throwingRuleIsReportedAsEvaluationFailure() {
        EntityDefinition def = EntityDefinition.define("Probe", eb -> {
            eb.physicalTable("t_probe");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id")
                .rule("EXPLODES", v -> { throw new IllegalStateException("boom"); }));
        });

        assertThat(EntityValidator.check(def, Map.of("id", "x"), CTX, true).violations())
            .singleElement()
            .satisfies(v -> {
                assertThat(v.ruleCode()).isEqualTo("RULE_EVALUATION_FAILED");
                assertThat(v.message()).contains("EXPLODES").contains("boom");
            });
    }

    @Test
    void allViolationsAreAccumulated() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("colour", "red");
        raw.put("amount", -5);
        raw.put("status", "LOST");

        assertThat(EntityValidator.check(ORDER, raw, CTX, true).violations())
            .extracting(Violation::field, Violation::ruleCode)
            .containsExactlyInAnyOrder(
                tuple("colour", "UNKNOWN_FIELD"),
                tuple("orderId", "REQUIRED"),
                tuple("amount", "NON_NEGATIVE"),
                tuple("status", "INVALID_VALUE"));
    }

    @Test
    void requireValidReturnsAttributesOrThrowsWithAllViolations() {
        assertThat(EntityValidator.requireValid(ORDER, validInsert(), CTX, true))
            .containsEntry("orderId", "O-1");

        assertThatThrownBy(() -> EntityValidator.requireValid(ORDER, Map.of("amount", -1), CTX, true))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("orderId: REQUIRED")
            .hasMessageContaining("amount: NON_NEGATIVE")
            .satisfies(e -> assertThat(((ValidationException) e).violations()).hasSize(2));
    }

    @Test
    void validationExceptionCopiesViolations() {
        List<Violation> source = new java.util.ArrayList<>(List.of(new Violation("f", "C", "m")));
        ValidationException ex = new ValidationException(source);
        source.clear();
        assertThat(ex.violations()).hasSize(1);
    }
}
