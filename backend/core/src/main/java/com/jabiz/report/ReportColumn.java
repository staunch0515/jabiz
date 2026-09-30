package com.jabiz.report;

import com.jabiz.entity.SemanticKind;

import java.util.Objects;

/**
 * One column of an exported report (docs/design/19-reports.md section 4).
 *
 * @param name  the result column's name
 * @param label its display name in the report's language
 * @param kind  its semantic kind: amounts and numbers become numeric cells, times date cells
 */
public record ReportColumn(String name, String label, SemanticKind kind) {

    public ReportColumn {
        Objects.requireNonNull(name, "name must not be null");
        label = label == null || label.isBlank() ? name : label;
        kind = kind == null ? new SemanticKind.None() : kind;
    }

    /** Amounts, numbers and versions: written as numbers, right aligned. */
    public boolean numeric() {
        return kind instanceof SemanticKind.Monetary || kind instanceof SemanticKind.Numeric
            || kind instanceof SemanticKind.Version;
    }

    /** Points in time. */
    public boolean temporal() {
        return kind instanceof SemanticKind.Temporal;
    }

    /** Digits after the point of a numeric column; 0 for whole numbers and other columns. */
    public int scale() {
        return switch (kind) {
            case SemanticKind.Monetary m -> Math.max(m.scale(), 0);
            case SemanticKind.Numeric n -> Math.max(n.scale(), 0);
            default -> 0;
        };
    }
}
