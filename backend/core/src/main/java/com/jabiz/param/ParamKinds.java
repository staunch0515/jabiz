package com.jabiz.param;

import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.SemanticKindParser;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * The semantic kinds a business parameter may have and how its values are stored (docs/design/04-temporal-append-only.md
 * section 9). A parameter's kind is written as data, in the notation of SQL template headers
 * ({@code {type: numeric, precision: 5, scale: 4}}); its value is stored as canonical text and read back as the
 * kind's canonical Java value. Supported: {@code text}, {@code numeric}, {@code monetary}, {@code bool},
 * {@code temporal} and {@code code} with its allowed values listed (a dictionary lookup cannot run in a synchronous
 * check).
 */
public final class ParamKinds {

    private ParamKinds() {}

    /**
     * Reads a kind written as data.
     *
     * @throws IllegalArgumentException when it is malformed or not supported for parameters
     */
    public static SemanticKind parse(Map<String, ?> spec) {
        SemanticKind kind = SemanticKindParser.parse(spec);
        switch (kind) {
            case SemanticKind.Text t -> { }
            case SemanticKind.Numeric n -> { }
            case SemanticKind.Monetary m -> { }
            case SemanticKind.Bool b -> { }
            case SemanticKind.Temporal t -> { }
            case SemanticKind.Code c when !c.allowedValues().isEmpty() -> { }
            case SemanticKind.Code c -> throw new IllegalArgumentException(
                "a code parameter must list its allowed values");
            default -> throw new IllegalArgumentException(
                "parameters cannot have kind '" + spec.get("type") + "'");
        }
        return kind;
    }

    /**
     * The canonical text of {@code raw} as a value of {@code kind}, as stored.
     *
     * @throws IllegalArgumentException when the value is not a valid value of the kind
     */
    public static String canonical(SemanticKind kind, Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("a parameter value must not be null");
        }
        Object value = FieldValueCoercer.coerce(kind, raw, true);
        return switch (value) {
            case BigDecimal decimal -> decimal(kind, decimal).toPlainString();
            case Instant instant -> instant.toString();
            case String text -> text(kind, text);
            default -> value.toString();
        };
    }

    /**
     * The value stored as {@code text}, as the canonical Java value of {@code kind}: {@code String},
     * {@code BigDecimal}, {@code Boolean} or {@code Instant}.
     *
     * @throws IllegalArgumentException when the stored text is not a valid value of the kind
     */
    public static Object value(SemanticKind kind, String text) {
        return FieldValueCoercer.coerce(kind, canonical(kind, text), false);
    }

    private static BigDecimal decimal(SemanticKind kind, BigDecimal value) {
        int scale = switch (kind) {
            case SemanticKind.Numeric n -> n.scale();
            case SemanticKind.Monetary m -> m.scale();
            default -> value.scale();
        };
        BigDecimal scaled;
        try {
            scaled = value.setScale(scale);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("more than " + scale + " decimal places: " + value.toPlainString(), e);
        }
        if (kind instanceof SemanticKind.Numeric n && scaled.precision() - scaled.scale() > n.precision() - n.scale()) {
            throw new IllegalArgumentException(
                value.toPlainString() + " does not fit numeric(" + n.precision() + "," + n.scale() + ")");
        }
        return scaled;
    }

    private static String text(SemanticKind kind, String text) {
        if (kind instanceof SemanticKind.Text t && t.maxLength() != null
            && text.codePointCount(0, text.length()) > t.maxLength()) {
            throw new IllegalArgumentException("longer than " + t.maxLength() + " characters");
        }
        return text;
    }
}
