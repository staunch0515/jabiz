package com.jabiz.entity;

import com.jabiz.testkinds.TestCellKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldValueCoercerTest {

    private static final SemanticKind TEMPORAL = new SemanticKind.Temporal(TemporalRole.EVENT_TIME);
    private static final SemanticKind MONETARY = new SemanticKind.Monetary("JPY", 0);
    private static final SemanticKind QUANTITY = new SemanticKind.Numeric(12, 3);
    /** A custom kind whose support converts to Long, like the geo extension's H3 cells. */
    private static final SemanticKind H3 = TestCellKind.of(8);
    private static final SemanticKind TEXT = new SemanticKind.Text(10, false);
    private static final SemanticKind BOOL = new SemanticKind.Bool();
    private static final SemanticKind REFERENCE = new SemanticKind.Reference("Owner");
    private static final SemanticKind VERSION = new SemanticKind.Version();
    private static final SemanticKind.Code STATUS =
        new SemanticKind.Code("urn:test:dict:status", List.of("OPEN", "DONE"));

    private static Object coerce(SemanticKind kind, Object raw) {
        return FieldValueCoercer.coerce(kind, raw, true);
    }

    @Test
    void nullStaysNullForEveryKind() {
        Stream.of(TEMPORAL, MONETARY, QUANTITY, H3, VERSION, STATUS, TEXT, BOOL, REFERENCE,
                new SemanticKind.SemanticIdentity("urn:x"), new SemanticKind.None())
            .forEach(kind -> assertThat(coerce(kind, null)).isNull());
    }

    @Nested
    class Temporal {
        private final Instant instant = Instant.parse("2026-01-31T09:00:00Z");

        @Test
        void acceptsJavaTimeTypes() {
            assertThat(coerce(TEMPORAL, instant)).isEqualTo(instant);
            assertThat(coerce(TEMPORAL, OffsetDateTime.parse("2026-01-31T18:00:00+09:00"))).isEqualTo(instant);
            assertThat(coerce(TEMPORAL, ZonedDateTime.of(2026, 1, 31, 18, 0, 0, 0, ZoneId.of("Asia/Tokyo"))))
                .isEqualTo(instant);
            assertThat(coerce(TEMPORAL, Date.from(instant))).isEqualTo(instant);
        }

        @Test
        void localDateTimeIsInterpretedAsUtc() {
            assertThat(coerce(TEMPORAL, LocalDateTime.of(2026, 1, 31, 9, 0))).isEqualTo(instant);
        }

        @Test
        void parsesIsoStrings() {
            assertThat(coerce(TEMPORAL, "2026-01-31T09:00:00Z")).isEqualTo(instant);
            assertThat(coerce(TEMPORAL, "  2026-01-31T18:00:00+09:00 ")).isEqualTo(instant);
            assertThat(coerce(TEMPORAL, new StringBuilder("2026-01-31T09:00:00Z"))).isEqualTo(instant);
        }

        @Test
        void rejectsMalformedString() {
            assertThatThrownBy(() -> coerce(TEMPORAL, "31/01/2026"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an ISO-8601 timestamp");
        }

        @Test
        void rejectsUnsupportedType() {
            assertThatThrownBy(() -> coerce(TEMPORAL, 1_700_000_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Long");
        }
    }

    @Nested
    class Decimal {
        @Test
        void acceptsNumbers() {
            assertThat(coerce(MONETARY, new BigDecimal("12.50"))).isEqualTo(new BigDecimal("12.50"));
            assertThat(coerce(MONETARY, new BigInteger("123"))).isEqualTo(new BigDecimal("123"));
            assertThat(coerce(MONETARY, 7)).isEqualTo(BigDecimal.valueOf(7));
            assertThat(coerce(MONETARY, 7L)).isEqualTo(BigDecimal.valueOf(7));
            assertThat(coerce(MONETARY, (short) 7)).isEqualTo(BigDecimal.valueOf(7));
            assertThat(coerce(MONETARY, (byte) 7)).isEqualTo(BigDecimal.valueOf(7));
            assertThat(coerce(QUANTITY, 1.5d)).isEqualTo(new BigDecimal("1.5"));
            assertThat(coerce(QUANTITY, 1.5f)).isEqualTo(new BigDecimal("1.5"));
        }

        @Test
        void parsesStrings() {
            assertThat(coerce(MONETARY, " 1000 ")).isEqualTo(new BigDecimal("1000"));
        }

        @ParameterizedTest
        @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void rejectsNonFiniteDoubles(double value) {
            assertThatThrownBy(() -> coerce(MONETARY, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a finite number");
        }

        @Test
        void rejectsMalformedString() {
            assertThatThrownBy(() -> coerce(MONETARY, "12,50"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a decimal number");
        }

        @Test
        void rejectsUnsupportedType() {
            assertThatThrownBy(() -> coerce(QUANTITY, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Boolean");
        }
    }

    @Nested
    class Integer64 {
        @Test
        void acceptsIntegralNumbers() {
            assertThat(coerce(VERSION, 3L)).isEqualTo(3L);
            assertThat(coerce(VERSION, 3)).isEqualTo(3L);
            assertThat(coerce(VERSION, (short) 3)).isEqualTo(3L);
            assertThat(coerce(VERSION, (byte) 3)).isEqualTo(3L);
            assertThat(coerce(VERSION, BigInteger.TEN)).isEqualTo(10L);
            assertThat(coerce(VERSION, new BigDecimal("10.000"))).isEqualTo(10L);
            assertThat(coerce(VERSION, 4.0d)).isEqualTo(4L);
        }

        @Test
        void parsesDecimalAndHexStrings() {
            assertThat(coerce(H3, " 42 ")).isEqualTo(42L);
            assertThat(coerce(H3, "0x882f516a23ffff")).isEqualTo(0x882f516a23ffffL);
            assertThat(coerce(H3, "0X882F516A23FFFF")).isEqualTo(0x882f516a23ffffL);
        }

        @Test
        void rejectsFractionalValues() {
            assertThatThrownBy(() -> coerce(VERSION, new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a 64-bit integer");
            assertThatThrownBy(() -> coerce(VERSION, 1.5d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an integer");
            assertThatThrownBy(() -> coerce(VERSION, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsOutOfRangeValues() {
            BigInteger tooLarge = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
            assertThatThrownBy(() -> coerce(VERSION, tooLarge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a 64-bit integer");
            assertThatThrownBy(() -> coerce(VERSION, tooLarge.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a 64-bit integer");
        }

        @Test
        void rejectsMalformedStringAndUnsupportedType() {
            assertThatThrownBy(() -> coerce(H3, "0xZZ"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> coerce(VERSION, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("to an integer");
        }
    }

    @Nested
    class Code {
        @Test
        void acceptsDictionaryValue() {
            assertThat(coerce(STATUS, "OPEN")).isEqualTo("OPEN");
        }

        @Test
        void rejectsValueOutsideDictionaryOnInput() {
            assertThatThrownBy(() -> FieldValueCoercer.coerce(STATUS, "CLOSED", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLOSED")
                .hasMessageContaining("urn:test:dict:status");
        }

        @Test
        void acceptsValueOutsideDictionaryWhenNotEnforced() {
            // Stored values may use codes that were retired from the dictionary.
            assertThat(FieldValueCoercer.coerce(STATUS, "CLOSED", false)).isEqualTo("CLOSED");
        }

        @Test
        void emptyDictionaryAcceptsAnyValue() {
            SemanticKind open = new SemanticKind.Code("urn:test:dict:any", List.of());
            assertThat(coerce(open, 42)).isEqualTo("42");
        }
    }

    @Test
    void identityAndNoneAreReturnedUnchanged() {
        Object value = Map.of("k", "v");
        assertThat(coerce(new SemanticKind.SemanticIdentity("urn:x"), value)).isSameAs(value);
        assertThat(coerce(new SemanticKind.None(), value)).isSameAs(value);
    }

    @Test
    void fieldOverloadPrefixesErrorWithFieldName() {
        FieldDefinition field = new FieldDefinition("amount", "f_amount", false, false, false,
            MONETARY, List.of(), List.of());
        assertThat(FieldValueCoercer.coerce(field, "5", true)).isEqualTo(new BigDecimal("5"));
        assertThatThrownBy(() -> FieldValueCoercer.coerce(field, "five", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Field 'amount': ")
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void javaTypeCoversEveryKind() {
        assertThat(FieldValueCoercer.javaType(TEMPORAL)).isEqualTo(Instant.class);
        assertThat(FieldValueCoercer.javaType(MONETARY)).isEqualTo(BigDecimal.class);
        assertThat(FieldValueCoercer.javaType(QUANTITY)).isEqualTo(BigDecimal.class);
        assertThat(FieldValueCoercer.javaType(H3)).isEqualTo(Long.class);
        assertThat(FieldValueCoercer.javaType(VERSION)).isEqualTo(Long.class);
        assertThat(FieldValueCoercer.javaType(STATUS)).isEqualTo(String.class);
        assertThat(FieldValueCoercer.javaType(new SemanticKind.SemanticIdentity("urn:x"))).isEqualTo(String.class);
        assertThat(FieldValueCoercer.javaType(new SemanticKind.None())).isEqualTo(String.class);
    }

    @Test
    void offsetDateTimeAtUtcRoundTrips() {
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        assertThat(coerce(TEMPORAL, now.atOffset(ZoneOffset.UTC).toString())).isEqualTo(now);
    }

    @Nested
    class NewKinds {

        @Test
        void textAcceptsCharacterSequencesOnly() {
            assertThat(coerce(TEXT, new StringBuilder("abc"))).isEqualTo("abc");
            assertThatThrownBy(() -> coerce(TEXT, 42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("to text");
        }

        @Test
        void boolAcceptsBooleansAndTheirNames() {
            assertThat(coerce(BOOL, true)).isEqualTo(Boolean.TRUE);
            assertThat(coerce(BOOL, " TRUE ")).isEqualTo(Boolean.TRUE);
            assertThat(coerce(BOOL, "false")).isEqualTo(Boolean.FALSE);
            assertThatThrownBy(() -> coerce(BOOL, "yes")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> coerce(BOOL, 1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void numericIsDecimal() {
            assertThat(coerce(QUANTITY, "1.250")).isEqualTo(new BigDecimal("1.250"));
        }

        @Test
        void referenceKeepsTheKeyAsIs() {
            assertThat(coerce(REFERENCE, "WB-1")).isEqualTo("WB-1");
        }

        @Test
        void customKindDelegatesToItsSupport() {
            assertThat(coerce(H3, "0x10")).isEqualTo(16L);
            assertThatThrownBy(() -> coerce(new SemanticKind.Custom("test.unregistered", Map.of()), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No CustomKindSupport is registered for kind 'test.unregistered'");
        }

        @Test
        void javaTypesOfTheNewKinds() {
            assertThat(FieldValueCoercer.javaType(TEXT)).isEqualTo(String.class);
            assertThat(FieldValueCoercer.javaType(BOOL)).isEqualTo(Boolean.class);
            assertThat(FieldValueCoercer.javaType(REFERENCE)).isEqualTo(String.class);
            assertThat(FieldValueCoercer.javaType(new SemanticKind.Numeric(5, 2))).isEqualTo(BigDecimal.class);
        }

        @Test
        void kindParametersAreValidated() {
            assertThatThrownBy(() -> new SemanticKind.Text(0, false)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SemanticKind.Numeric(2, 3)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SemanticKind.Reference(" ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SemanticKind.Custom("", Map.of())).isInstanceOf(IllegalArgumentException.class);
            assertThat(new SemanticKind.Code("urn:x", null).allowedValues()).isEmpty();
        }
    }
}
