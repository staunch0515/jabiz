package com.jabiz.approval;

import java.math.BigDecimal;

/** Type of an approval fact (docs/design/18-numbering-approvals-tasks.md section 3.1). */
public enum FactType {
    /** A number, compared as {@link BigDecimal}; amounts are facts of this type. */
    NUMBER,
    /** A text, compared for equality and membership only. */
    TEXT,
    /** true or false. */
    BOOLEAN;

    /** The value in its canonical form ({@link BigDecimal} for numbers), or null when it is not of this type. */
    Object normalize(Object value) {
        return switch (this) {
            case NUMBER -> switch (value) {
                case BigDecimal decimal -> decimal;
                case Integer i -> BigDecimal.valueOf(i);
                case Long l -> BigDecimal.valueOf(l);
                case Short s -> BigDecimal.valueOf(s);
                case Byte b -> BigDecimal.valueOf(b);
                case java.math.BigInteger big -> new BigDecimal(big);
                case Double d when Double.isFinite(d) -> BigDecimal.valueOf(d);
                case Float f when Float.isFinite(f) -> new BigDecimal(f.toString());
                case null, default -> null;
            };
            case TEXT -> value instanceof String ? value : null;
            case BOOLEAN -> value instanceof Boolean ? value : null;
        };
    }
}
