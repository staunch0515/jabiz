package com.jabiz.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class FieldBuilder {
    private final String name;
    private String physicalColumn;
    private boolean immutable = false;
    private boolean required = false;
    private boolean generated = false;
    private boolean sensitive = false;
    private boolean processOnly = false;
    private boolean auditMasked = false;
    private MaskSpec masked;
    private SemanticKind kind = new SemanticKind.None();
    private final List<FieldRule> rules = new ArrayList<>();
    private final List<RuleSpec> ruleSpecs = new ArrayList<>();

    FieldBuilder(String name) { this.name = name; }

    public FieldBuilder physicalColumn(String col) { this.physicalColumn = col; return this; }
    public FieldBuilder immutable(boolean v) { this.immutable = v; return this; }
    public FieldBuilder required(boolean v) { this.required = v; return this; }
    public FieldBuilder generated(boolean v) { this.generated = v; return this; }
    /**
     * Marks a secret (for example a password hash): read APIs leave it out, the dataset API refuses to write it,
     * logs and operation records mask it. Only processes can set it.
     */
    public FieldBuilder sensitive() { this.sensitive = true; return this; }
    /**
     * Marks a field only processes change (a status, a review comment): it stays readable, but the dataset API and
     * the generic entity processes refuse to write it and a revert does not restore it
     * (docs/design/16-content-authoring.md section 5).
     */
    public FieldBuilder processOnly() { this.processOnly = true; return this; }
    /**
     * Keeps the value out of the audit trail while it stays readable (an uploaded file's original name): the audit
     * records that it changed, as {@code ***} (docs/design/21-audit-retention.md section 1.2).
     */
    public FieldBuilder auditMasked() { this.auditMasked = true; return this; }
    /**
     * Shows the field masked in {@code style} everywhere (read APIs, history, audit, templates and exports) and lets
     * holders of {@code permission} ask for one value at a time in plain text, each time on the record
     * (docs/design/10-security.md section 13.1, decision D28). Only holders may write it, filter or sort by it. The
     * field must be text.
     */
    public FieldBuilder masked(String permission, MaskStyle style) {
        this.masked = new MaskSpec(permission, style);
        return this;
    }
    public FieldBuilder asSemanticIdentity(String urn) { kind = new SemanticKind.SemanticIdentity(urn); return this; }
    public FieldBuilder asMonetary(String currency, int scale) { kind = new SemanticKind.Monetary(currency, scale); return this; }
    public FieldBuilder asTemporal(TemporalRole role) { kind = new SemanticKind.Temporal(role); return this; }
    public FieldBuilder asCode(String dictUrn, String... values) { kind = new SemanticKind.Code(dictUrn, List.of(values)); return this; }
    public FieldBuilder asVersion() { kind = new SemanticKind.Version(); return this; }
    public FieldBuilder asText(int maxLength) { kind = new SemanticKind.Text(maxLength, false); return this; }
    public FieldBuilder asText(Integer maxLength, boolean multiline) { kind = new SemanticKind.Text(maxLength, multiline); return this; }
    public FieldBuilder asNumeric(int precision, int scale) { kind = new SemanticKind.Numeric(precision, scale); return this; }
    public FieldBuilder asBool() { kind = new SemanticKind.Bool(); return this; }
    /** The field holds the primary key of an instance of {@code targetEntity}; the reference is enforced on write. */
    public FieldBuilder asReference(String targetEntity) { kind = new SemanticKind.Reference(targetEntity); return this; }
    public FieldBuilder asCustom(String kindId, Map<String, Object> params) { kind = new SemanticKind.Custom(kindId, params); return this; }
    /** Sets any kind, typically one built by an extension module (for example a custom kind factory). */
    public FieldBuilder kind(SemanticKind semanticKind) { kind = java.util.Objects.requireNonNull(semanticKind); return this; }

    /**
     * Rule that can be exported to clients: the parameters (a {@link RuleSpec}, plain data)
     * and the server-side implementation are declared together so they cannot drift apart.
     */
    public FieldBuilder rule(String code, String ruleKind, Map<String, Object> params, RulePredicate predicate) {
        ruleSpecs.add(exported(code, ruleKind, params));
        rules.add(new FieldRule(code, predicate));
        return this;
    }

    /** Exportable rule whose implementation does not need runtime services. */
    public FieldBuilder rule(String code, String ruleKind, Map<String, Object> params, Predicate<Object> predicate) {
        ruleSpecs.add(exported(code, ruleKind, params));
        rules.add(FieldRule.of(code, predicate));
        return this;
    }

    /** Applies a reusable piece of field configuration, typically a rule from {@link Rules}. */
    public FieldBuilder apply(java.util.function.Consumer<FieldBuilder> configuration) {
        configuration.accept(this);
        return this;
    }

    /**
     * An exported rule must be of a kind clients implement (decision D15); prefer the factories in {@link Rules},
     * which also derive the predicate from the parameters.
     */
    private static RuleSpec exported(String code, String ruleKind, Map<String, Object> params) {
        RuleKinds.requireKnown(code, ruleKind);
        if (RuleKinds.PATTERN.equals(ruleKind)) {
            RuleKinds.checkPortablePattern(code, String.valueOf(params.get("regex")));
        }
        // Insertion order is kept so the export (and the JSON clients see) is stable.
        return new RuleSpec(code, ruleKind, java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(params)));
    }

    /** Server-only rule (for example one that depends on the clock or external lookups); not exported. */
    public FieldBuilder rule(String code, RulePredicate predicate) {
        rules.add(new FieldRule(code, predicate));
        return this;
    }

    /** Server-only rule that does not need runtime services; not exported. */
    public FieldBuilder rule(String code, Predicate<Object> predicate) {
        rules.add(FieldRule.of(code, predicate));
        return this;
    }

    FieldDefinition build() {
        if (physicalColumn == null || physicalColumn.isBlank()) {
            throw new IllegalStateException("Field '" + name + "' has no physical column");
        }
        if (processOnly && (sensitive || generated)) {
            // Sensitive fields are already written by processes only; generated ones by the platform.
            throw new IllegalStateException("Field '" + name + "' cannot be process-only and "
                + (sensitive ? "sensitive" : "generated"));
        }
        if (masked != null) {
            if (!(kind instanceof SemanticKind.Text)) {
                throw new IllegalStateException("Masked field '" + name + "' must be a text field");
            }
            if (sensitive || generated) {
                // A sensitive field is never shown at all; a generated one is a key, which must stay usable.
                throw new IllegalStateException("Field '" + name + "' cannot be masked and "
                    + (sensitive ? "sensitive" : "generated"));
            }
        }
        return new FieldDefinition(
            name,
            physicalColumn,
            immutable,
            required,
            generated,
            kind,
            List.copyOf(rules),
            List.copyOf(ruleSpecs),
            sensitive,
            processOnly,
            auditMasked,
            masked
        );
    }
}

