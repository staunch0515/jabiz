package com.jabiz.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

public final class EntityBuilder {
    private final String name;
    private String physicalTable;
    private String primaryKey;
    private String stateField;
    private final Map<String, FieldDefinition> fields = new LinkedHashMap<>();
    private final List<StateTransitionRule> transitions = new ArrayList<>();
    private final List<SpatialGuardRule> spatialGuards = new ArrayList<>();
    private final List<ReferenceDefinition> references = new ArrayList<>();

    EntityBuilder(String name) { this.name = name; }

    public void physicalTable(String table) { this.physicalTable = table; }
    public void primaryKey(String key) { this.primaryKey = key; }

    public void field(String fieldName, Consumer<FieldBuilder> block) {
        if (fields.containsKey(fieldName)) {
            throw new IllegalStateException("Duplicate field '" + fieldName + "' on entity " + name);
        }
        FieldBuilder fb = new FieldBuilder(fieldName);
        block.accept(fb);
        fields.put(fieldName, fb.build());
    }

    /** Declares the lifecycle of the given status field. An entity supports one lifecycle. */
    public void stateTransitions(String statusField, Consumer<StateTransitionBuilder> block) {
        if (stateField != null && !stateField.equals(statusField)) {
            throw new IllegalStateException("Entity " + name + " already declares lifecycle field '"
                                            + stateField + "'; only one is supported");
        }
        stateField = statusField;
        StateTransitionBuilder stb = new StateTransitionBuilder();
        block.accept(stb);
        transitions.addAll(stb.build());
    }

    public void spatialGuard(String targetStatus, String locationField, LongPredicate guard) {
        spatialGuards.add(new SpatialGuardRule(targetStatus, locationField, guard));
    }

    /**
     * Declares that {@code sourceField} holds the primary key of an instance of {@code targetEntity}.
     * The reference is verified whenever the field is written, and the target cannot be deleted while
     * instances still refer to it. Whether a value is mandatory is decided by the field's own
     * {@code required} flag.
     */
    public void reference(String sourceField, String targetEntity) {
        references.add(new ReferenceDefinition(sourceField, targetEntity));
    }

    EntityDefinition build() {
        requireNotBlank(physicalTable, "physical table");
        requireNotBlank(primaryKey, "primary key");
        if (!fields.containsKey(primaryKey)) {
            throw invalid("primary key '" + primaryKey + "' is not a declared field");
        }
        validateUniqueColumns();
        validateVersionField();
        validateLifecycle();
        validateReferences();

        return new EntityDefinition(
            name,
            physicalTable,
            primaryKey,
            Collections.unmodifiableMap(new LinkedHashMap<>(fields)),
            stateField,
            List.copyOf(transitions),
            List.copyOf(spatialGuards),
            List.copyOf(references)
        );
    }

    private void validateUniqueColumns() {
        Set<String> seen = new HashSet<>();
        for (FieldDefinition f : fields.values()) {
            if (!seen.add(f.physicalColumn().toLowerCase())) {
                throw invalid("physical column '" + f.physicalColumn() + "' is mapped by more than one field");
            }
        }
    }

    private void validateVersionField() {
        long count = fields.values().stream().filter(f -> f.kind() instanceof SemanticKind.Version).count();
        if (count > 1) {
            throw invalid("at most one Version field is allowed");
        }
    }

    private void validateLifecycle() {
        if (stateField == null) {
            if (!spatialGuards.isEmpty()) {
                throw invalid("spatial guards require a lifecycle (stateTransitions)");
            }
            return;
        }
        FieldDefinition state = fields.get(stateField);
        if (state == null || !(state.kind() instanceof SemanticKind.Code code)) {
            throw invalid("lifecycle field '" + stateField + "' must be a declared Code field");
        }
        for (StateTransitionRule rule : transitions) {
            requireAllowedState(code, rule.from());
            rule.to().forEach(target -> requireAllowedState(code, target));
        }
        for (SpatialGuardRule guard : spatialGuards) {
            requireAllowedState(code, guard.targetStatus());
            FieldDefinition location = fields.get(guard.locationField());
            if (location == null || !(location.kind() instanceof SemanticKind.SpatialH3)) {
                throw invalid("spatial guard location '" + guard.locationField() + "' must be a declared SpatialH3 field");
            }
        }
    }

    private void validateReferences() {
        Set<String> sources = new HashSet<>();
        for (ReferenceDefinition ref : references) {
            if (!fields.containsKey(ref.sourceField())) {
                throw invalid("reference source '" + ref.sourceField() + "' is not a declared field");
            }
            if (!sources.add(ref.sourceField())) {
                throw invalid("field '" + ref.sourceField() + "' declares more than one reference");
            }
        }
    }

    private void requireAllowedState(SemanticKind.Code code, String state) {
        if (!code.allowedValues().contains(state)) {
            throw invalid("state '" + state + "' is not among the allowed values " + code.allowedValues());
        }
    }

    private void requireNotBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw invalid(what + " is not set");
        }
    }

    private IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid entity definition '" + name + "': " + message);
    }
}

