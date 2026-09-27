package com.jabiz.entity;

import java.util.List;

/**
 * Declarative description of one logical field of an entity.
 *
 * @param name           logical field name (the name used by APIs and queries)
 * @param physicalColumn database column backing this field
 * @param immutable      true if the value can never change after insert
 * @param required       true if a non-null value must be supplied on insert
 * @param generated      true if the system assigns the value when the entity is created through the
 *                       generic add process; meaningful for the primary key only, and any caller-supplied
 *                       value is replaced
 * @param kind           semantic type of the field
 * @param rules          server-side rules evaluated on write
 * @param ruleSpecs      exportable descriptions of the rules that can be checked client-side
 * @param sensitive      true for secrets (password hashes and the like): never returned by read APIs, never written
 *                       through the dataset API, masked in logs and operation records (docs/design/10-security.md)
 * @param processOnly    true for fields only processes change: readable, but never written through the dataset API or
 *                       the generic entity processes (docs/design/16-content-authoring.md section 5)
 */
public record FieldDefinition(
    String name,
    String physicalColumn,
    boolean immutable,
    boolean required,
    boolean generated,
    SemanticKind kind,
    List<FieldRule> rules,
    List<RuleSpec> ruleSpecs,
    boolean sensitive,
    boolean processOnly
) {
    public FieldDefinition(String name, String physicalColumn, boolean immutable, boolean required, boolean generated,
        SemanticKind kind, List<FieldRule> rules, List<RuleSpec> ruleSpecs) {
        this(name, physicalColumn, immutable, required, generated, kind, rules, ruleSpecs, false, false);
    }

    public FieldDefinition(String name, String physicalColumn, boolean immutable, boolean required, boolean generated,
        SemanticKind kind, List<FieldRule> rules, List<RuleSpec> ruleSpecs, boolean sensitive) {
        this(name, physicalColumn, immutable, required, generated, kind, rules, ruleSpecs, sensitive, false);
    }
}

