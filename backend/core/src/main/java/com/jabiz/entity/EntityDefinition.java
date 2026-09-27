package com.jabiz.entity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Immutable metadata of one entity: physical mapping, fields, lifecycle and guards.
 * Instances are created through {@link #define(String, Consumer)}, which validates
 * the definition and fails fast on inconsistencies.
 */
public final class EntityDefinition {
    public final String name;
    public final String physicalTable;
    public final String primaryKey;
    /** Fields in declaration order. */
    public final Map<String, FieldDefinition> fields;
    /** Logical name of the lifecycle status field, or null if the entity has no lifecycle. */
    public final String stateField;
    public final List<StateTransitionRule> transitions;
    /** Guards of lifecycle transitions. */
    public final List<GuardDefinition> guards;
    /** Rules over the whole state (docs/design/02-metamodel.md section 4.1). */
    public final List<CheckDefinition> checks;
    /** Many-to-one references to other entities, enforced as data constraints. */
    public final List<ReferenceDefinition> references;
    /** Logical name of the optimistic-lock version field, or null if the entity is not writable. */
    public final String versionField;
    /** States that have outgoing transitions but no incoming ones; the only valid states on insert. */
    public final Set<String> initialStates;
    /** Unique value combinations. */
    public final List<UniqueConstraint> uniqueConstraints;
    /** List views by name, in declaration order. */
    public final Map<String, ListViewDefinition> listViews;
    /** Whether the entity keeps append-only bitemporal history (docs/design/04-temporal-append-only.md). */
    public final boolean temporal;
    /** Temporal settings; null unless {@link #temporal}. */
    public final TemporalSpec temporalSpec;
    /** Whether committed writes publish entity change events ({@link EntityBuilder#publishChanges()}). */
    public final boolean publishesChanges;
    /** Field that stands for an instance where it is referenced ({@link EntityBuilder#display}), or null. */
    public final String displayField;

    EntityDefinition(String name, String physicalTable, String primaryKey,
        Map<String, FieldDefinition> fields,
        String stateField,
        List<StateTransitionRule> transitions,
        List<GuardDefinition> guards,
        List<CheckDefinition> checks,
        List<ReferenceDefinition> references,
        List<UniqueConstraint> uniqueConstraints,
        Map<String, ListViewDefinition> listViews,
        TemporalSpec temporalSpec,
        boolean publishesChanges,
        String displayField) {
        this.name = name;
        this.physicalTable = physicalTable;
        this.primaryKey = primaryKey;
        this.fields = fields;
        this.stateField = stateField;
        this.transitions = transitions;
        this.guards = guards;
        this.checks = checks;
        this.references = references;
        this.uniqueConstraints = uniqueConstraints;
        this.listViews = listViews;
        this.temporalSpec = temporalSpec;
        this.temporal = temporalSpec != null;
        this.publishesChanges = publishesChanges;
        this.displayField = displayField;
        this.versionField = fields.values().stream()
            .filter(f -> f.kind() instanceof SemanticKind.Version)
            .map(FieldDefinition::name)
            .findFirst()
            .orElse(null);
        this.initialStates = computeInitialStates(transitions);
    }

    public static EntityDefinition define(String name, Consumer<EntityBuilder> block) {
        EntityBuilder b = new EntityBuilder(name);
        block.accept(b);
        return b.build();
    }

    public Optional<FieldDefinition> findField(String logicalName) {
        return Optional.ofNullable(fields.get(logicalName));
    }

    public FieldDefinition field(String logicalName) {
        FieldDefinition field = fields.get(logicalName);
        if (field == null) {
            throw new IllegalArgumentException("Field [" + logicalName + "] does not exist on entity [" + name + "]");
        }
        return field;
    }

    public String physicalColumn(String logicalName) {
        return field(logicalName).physicalColumn();
    }

    public String primaryKeyColumn() {
        return physicalColumn(primaryKey);
    }

    public Optional<String> versionColumn() {
        return Optional.ofNullable(versionField).map(this::physicalColumn);
    }

    public Optional<String> stateColumn() {
        return Optional.ofNullable(stateField).map(this::physicalColumn);
    }

    /** Fields whose values are issued by the system and are never accepted from callers. */
    public boolean isSystemManaged(FieldDefinition field) {
        return field.kind() instanceof SemanticKind.Version
               || (field.kind() instanceof SemanticKind.Temporal t && t.role() == TemporalRole.SYSTEM_RECORDED)
               || (temporal && TemporalSpec.isSystemField(field.name()));
    }

    /** Names of the fields marked {@linkplain FieldBuilder#sensitive() sensitive}, in declaration order. */
    public List<String> sensitiveFields() {
        return fields.values().stream().filter(FieldDefinition::sensitive).map(FieldDefinition::name).toList();
    }

    /** Names of the fields marked {@linkplain FieldBuilder#processOnly() process-only}, in declaration order. */
    public List<String> processOnlyFields() {
        return fields.values().stream().filter(FieldDefinition::processOnly).map(FieldDefinition::name).toList();
    }

    /** The only initial state of the lifecycle, or null when there is no lifecycle or several initial states. */
    public String soleInitialState() {
        return initialStates.size() == 1 ? initialStates.iterator().next() : null;
    }

    /**
     * Fields that make up the business state of a version: every field except the temporal system fields.
     * For entities that are not temporal these are all fields.
     */
    public List<String> stateFields() {
        return fields.keySet().stream()
            .filter(name -> !temporal || !TemporalSpec.isSystemField(name))
            .toList();
    }

    /**
     * Fields a caller can change: the state fields except the primary key and system-managed fields. On a temporal
     * entity an insert or a deletion counts as a change of all of them (docs/design/09-decisions.md D9).
     */
    public List<String> changeableFields() {
        return fields.values().stream()
            .filter(f -> !f.name().equals(primaryKey) && !isSystemManaged(f))
            .map(FieldDefinition::name)
            .toList();
    }

    /**
     * Normalizes an instance id: temporal entities are identified by UUIDs (they are registered in
     * {@code entity_registry}); other entities keep the id as given.
     *
     * @throws IllegalArgumentException if the id of a temporal entity is not a UUID
     */
    public Object normalizeId(Object id) {
        if (!temporal || id == null || id instanceof UUID) {
            return id;
        }
        try {
            return UUID.fromString(id.toString().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + id + "' is not a UUID; " + name + " is identified by UUIDs", e);
        }
    }

    /** Physical column of a temporal system field. */
    public String systemColumn(String systemField) {
        if (!temporal) {
            throw new IllegalStateException(name + " is not temporal");
        }
        return physicalColumn(systemField);
    }

    public boolean allowsTransition(String from, String to) {
        return transitions.stream()
            .filter(rule -> rule.from().equals(from))
            .anyMatch(rule -> rule.canTransitionTo(to));
    }

    /** Guards of the change from {@code from} (null on insert) to {@code to}, in declaration order. */
    public List<GuardDefinition> guardsFor(String from, String to) {
        return guards.stream().filter(g -> g.appliesTo(from, to)).toList();
    }

    public Optional<ListViewDefinition> listView(String viewName) {
        return Optional.ofNullable(listViews.get(viewName));
    }

    /** Dictionaries referenced by the Code fields, in declaration order. */
    public List<String> dictionaryUrns() {
        return fields.values().stream()
            .map(FieldDefinition::kind)
            .filter(SemanticKind.Code.class::isInstance)
            .map(k -> ((SemanticKind.Code) k).dictUrn())
            .distinct()
            .toList();
    }

    private static Set<String> computeInitialStates(List<StateTransitionRule> transitions) {
        Set<String> sources = new LinkedHashSet<>();
        Set<String> targets = new LinkedHashSet<>();
        for (StateTransitionRule rule : transitions) {
            sources.add(rule.from());
            targets.addAll(rule.to());
        }
        sources.removeAll(targets);
        return Collections.unmodifiableSet(sources);
    }
}

