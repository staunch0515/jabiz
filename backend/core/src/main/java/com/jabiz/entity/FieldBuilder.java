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
    private SemanticKind kind = new SemanticKind.None();
    private final List<FieldRule> rules = new ArrayList<>();
    private final List<RuleSpec> ruleSpecs = new ArrayList<>();

    FieldBuilder(String name) { this.name = name; }

    public FieldBuilder physicalColumn(String col) { this.physicalColumn = col; return this; }
    public FieldBuilder immutable(boolean v) { this.immutable = v; return this; }
    public FieldBuilder required(boolean v) { this.required = v; return this; }
    public FieldBuilder generated(boolean v) { this.generated = v; return this; }
    public FieldBuilder asSemanticIdentity(String urn) { kind = new SemanticKind.SemanticIdentity(urn); return this; }
    public FieldBuilder asMonetary(String currency, int scale) { kind = new SemanticKind.Monetary(currency, scale); return this; }
    public FieldBuilder asPhysicalQuantity(DimensionType dim, String unitUrn) { kind = new SemanticKind.PhysicalQuantity(dim, unitUrn); return this; }
    public FieldBuilder asTemporal(TemporalRole role) { kind = new SemanticKind.Temporal(role); return this; }
    public FieldBuilder asSpatialH3(int resolution) { kind = new SemanticKind.SpatialH3(resolution); return this; }
    public FieldBuilder asCode(String dictUrn, String... values) { kind = new SemanticKind.Code(dictUrn, List.of(values)); return this; }
    public FieldBuilder asVersion() { kind = new SemanticKind.Version(); return this; }

    /**
     * Rule that can be exported to clients: the parameters (a {@link RuleSpec}, plain data)
     * and the server-side implementation are declared together so they cannot drift apart.
     */
    public FieldBuilder rule(String code, String ruleKind, Map<String, Object> params, RulePredicate predicate) {
        ruleSpecs.add(new RuleSpec(code, ruleKind, Map.copyOf(params)));
        rules.add(new FieldRule(code, predicate));
        return this;
    }

    /** Exportable rule whose implementation does not need runtime services. */
    public FieldBuilder rule(String code, String ruleKind, Map<String, Object> params, Predicate<Object> predicate) {
        ruleSpecs.add(new RuleSpec(code, ruleKind, Map.copyOf(params)));
        rules.add(FieldRule.of(code, predicate));
        return this;
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
        return new FieldDefinition(
            name,
            physicalColumn,
            immutable,
            required,
            generated,
            kind,
            List.copyOf(rules),
            List.copyOf(ruleSpecs)
        );
    }
}

