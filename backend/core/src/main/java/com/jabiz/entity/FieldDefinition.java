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
 */
public record FieldDefinition(
    String name,
    String physicalColumn,
    boolean immutable,
    boolean required,
    boolean generated,
    SemanticKind kind,
    List<FieldRule> rules,
    List<RuleSpec> ruleSpecs
) {}

