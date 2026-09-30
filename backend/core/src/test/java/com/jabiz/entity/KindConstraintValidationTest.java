package com.jabiz.entity;

import com.jabiz.context.RequestContext;
import com.jabiz.dictionary.DictionaryLookup;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Constraints that come from the semantic kind itself: text length, numeric precision, monetary scale, dictionary
 * membership.
 */
class KindConstraintValidationTest {

    private static final ValidationContext CTX = new ValidationContext(
        Clock.fixed(Instant.parse("2026-01-31T09:00:00Z"), ZoneOffset.UTC), RequestContext.system(Locale.ENGLISH, "t"));

    private static final EntityDefinition SHIPMENT = EntityDefinition.define("Shipment", eb -> {
        eb.physicalTable("t_shipment");
        eb.primaryKey("id");
        eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:test:shipment"));
        eb.field("title", f -> f.physicalColumn("f_title").asText(5)
            .rule("TITLE_RULE", v -> false));
        eb.field("ratio", f -> f.physicalColumn("f_ratio").asNumeric(5, 2));
        eb.field("port", f -> f.physicalColumn("f_port").asCode("urn:test:dict:port"));
        eb.field("fragile", f -> f.physicalColumn("f_fragile").asBool());
        eb.field("fee", f -> f.physicalColumn("f_fee").asMonetary("USD", 2));
        eb.field("yen", f -> f.physicalColumn("f_yen").asMonetary("JPY", 0));
        eb.field("charge", f -> f.physicalColumn("f_charge").asMonetary("USD", 2)
            .apply(Rules.scale("CHARGE_SCALE", 1)));
    });

    private static final DictionaryLookup PORTS =
        urn -> urn.equals("urn:test:dict:port") ? Optional.of(Set.of("JPTYO", "JPYOK")) : Optional.empty();

    private static EntityValidator.Result check(Map<String, Object> raw, DictionaryLookup lookup) {
        return EntityValidator.check(SHIPMENT, raw, CTX, false, lookup);
    }

    @Test
    void textLongerThanMaxLengthIsRejectedBeforeRulesRun() {
        EntityValidator.Result result = check(Map.of("title", "abcdef"), DictionaryLookup.NONE);

        assertThat(result.violations()).extracting(Violation::field, Violation::ruleCode)
            .containsExactly(tuple("title", "TOO_LONG"));
        assertThat(result.violations().getFirst().params()).containsEntry("max", 5);
    }

    @Test
    void lengthCountsCharactersNotUtf16Units() {
        // Five characters outside the BMP are ten UTF-16 units.
        String emoji = "😀".repeat(5);
        assertThat(check(Map.of("title", emoji), DictionaryLookup.NONE).violations())
            .extracting(Violation::ruleCode).containsExactly("TITLE_RULE");
    }

    @Test
    void numericMustFitPrecisionAndScale() {
        assertThat(check(Map.of("ratio", "999.99"), DictionaryLookup.NONE).isValid()).isTrue();
        assertThat(check(Map.of("ratio", "1.50000"), DictionaryLookup.NONE).isValid()).isTrue();
        assertThat(check(Map.of("ratio", "1000"), DictionaryLookup.NONE).violations())
            .extracting(Violation::ruleCode).containsExactly("NUMERIC_PRECISION");
        assertThat(check(Map.of("ratio", "1.555"), DictionaryLookup.NONE).violations())
            .singleElement().satisfies(v -> assertThat(v.params()).containsEntry("precision", 5).containsEntry("scale", 2));
    }

    @Test
    void numericWithoutIntegerDigitsAcceptsZeroAndFractions() {
        EntityDefinition fraction = EntityDefinition.define("Fraction", eb -> {
            eb.physicalTable("t_fraction");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id"));
            eb.field("share", f -> f.physicalColumn("f_share").asNumeric(3, 3));
        });

        for (String ok : new String[] {"0", "0.000", "0.5", "-0.999"}) {
            assertThat(EntityValidator.check(fraction, Map.of("share", ok), CTX, false).isValid()).as(ok).isTrue();
        }
        assertThat(EntityValidator.check(fraction, Map.of("share", "1"), CTX, false).violations())
            .extracting(Violation::ruleCode).containsExactly("NUMERIC_PRECISION");
    }

    @Test
    void dictionaryCodesAreCheckedAgainstTheLookup() {
        assertThat(check(Map.of("port", "JPTYO"), PORTS).isValid()).isTrue();
        assertThat(check(Map.of("port", "XXAAA"), PORTS).violations())
            .singleElement()
            .satisfies(v -> {
                assertThat(v.ruleCode()).isEqualTo("NOT_IN_DICTIONARY");
                assertThat(v.params()).containsEntry("value", "XXAAA").containsEntry("dict", "urn:test:dict:port");
            });
    }

    @Test
    void unknownDictionariesAreNotCheckedByTheValidator() {
        // The runtime always supplies a lookup; without one, dictionary-backed codes cannot be judged here.
        assertThat(check(Map.of("port", "XXAAA"), DictionaryLookup.NONE).isValid()).isTrue();
        assertThat(EntityValidator.check(SHIPMENT, Map.of("port", "XXAAA"), CTX, false).isValid()).isTrue();
    }

    @Test
    void boolRejectsOtherValues() {
        assertThat(check(Map.of("fragile", "maybe"), DictionaryLookup.NONE).violations())
            .extracting(Violation::ruleCode).containsExactly("INVALID_VALUE");
    }

    @Test
    void requireValidUsesTheLookup() {
        assertThat(EntityValidator.requireValid(SHIPMENT, Map.of("port", "JPYOK"), CTX, false, PORTS))
            .containsEntry("port", "JPYOK");
        assertThatThrownBy(() -> EntityValidator.requireValid(SHIPMENT, Map.of("port", "X"), CTX, false, PORTS))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("NOT_IN_DICTIONARY");
    }

    @Test
    void monetaryAmountsHoldNoMoreDigitsThanTheirCurrency() {
        assertThat(check(Map.of("fee", "12.34", "yen", "1500"), DictionaryLookup.NONE).isValid()).isTrue();
        // Trailing zeros are not digits of the amount.
        assertThat(check(Map.of("fee", "12.3400", "yen", "1500.00"), DictionaryLookup.NONE).isValid()).isTrue();

        EntityValidator.Result result = check(Map.of("fee", "12.345", "yen", "0.5"), DictionaryLookup.NONE);
        assertThat(result.violations()).extracting(Violation::field, Violation::ruleCode)
            .containsExactlyInAnyOrder(tuple("fee", "MONETARY_SCALE"), tuple("yen", "MONETARY_SCALE"));
        assertThat(result.violations()).filteredOn(v -> v.field().equals("fee")).first()
            .extracting(Violation::params).isEqualTo(Map.of("scale", 2, "currency", "USD"));
    }

    @Test
    void anOwnScaleRuleReportsUnderItsCodeInsteadOfTheKind() {
        assertThat(check(Map.of("charge", "1.25"), DictionaryLookup.NONE).violations())
            .extracting(Violation::ruleCode).containsExactly("CHARGE_SCALE");
        assertThat(check(Map.of("charge", "1.2"), DictionaryLookup.NONE).isValid()).isTrue();
    }

    @Test
    void anOwnScaleRuleMayNotAllowMoreDigitsThanTheCurrency() {
        assertThatThrownBy(() -> EntityDefinition.define("Loose", eb -> {
            eb.physicalTable("t_loose");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:test:loose"));
            eb.field("amount", f -> f.physicalColumn("f_amount").asMonetary("USD", 2)
                .apply(Rules.scale("AMOUNT_SCALE", 3)));
        })).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rule AMOUNT_SCALE of monetary field 'amount' allows 3 digits")
            .hasMessageContaining("more than the scale 2 of USD");
    }
}
