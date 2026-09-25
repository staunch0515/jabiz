package com.jabiz.entity;

import com.jabiz.query.SqlIdentifiers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class EntityBuilder {
    private final String name;
    private String physicalTable;
    private String primaryKey;
    private String stateField;
    private final Map<String, FieldDefinition> fields = new LinkedHashMap<>();
    private final List<StateTransitionRule> transitions = new ArrayList<>();
    private final List<GuardDefinition> guards = new ArrayList<>();
    private final List<ReferenceDefinition> references = new ArrayList<>();
    private final List<UniqueConstraint> uniqueConstraints = new ArrayList<>();
    private final Map<String, ListViewDefinition> listViews = new LinkedHashMap<>();
    private TemporalBuilder temporal;

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

    /**
     * Attaches a guard to the transitions {@code from -> to} (docs/design/02-metamodel.md section 4).
     *
     * @param from source state, or {@link GuardDefinition#ANY} for every source state including insertion
     */
    public void guard(String code, String from, String to, TransitionGuard guard) {
        guards.add(new GuardDefinition(code, from, to, guard));
    }

    /**
     * Declares that {@code sourceField} holds the primary key of an instance of {@code targetEntity}.
     * The reference is verified whenever the field is written, and the target cannot be deleted while
     * instances still refer to it. Whether a value is mandatory is decided by the field's own
     * {@code required} flag. Fields of kind {@link SemanticKind.Reference} declare this implicitly.
     */
    public void reference(String sourceField, String targetEntity) {
        references.add(new ReferenceDefinition(sourceField, targetEntity));
    }

    /** Declares that the combination of {@code fieldNames} is unique (docs/design/02-metamodel.md section 6). */
    public void unique(String constraintName, String... fieldNames) {
        uniqueConstraints.add(new UniqueConstraint(constraintName, List.of(fieldNames)));
    }

    /** Makes the entity append-only and bitemporal with default settings (docs/design/04-temporal-append-only.md). */
    public void temporal() {
        temporal(t -> { });
    }

    /**
     * Makes the entity append-only and bitemporal. The system fields ({@link TemporalSpec#SYSTEM_FIELDS}) are
     * added by the platform and must not be declared.
     */
    public void temporal(Consumer<TemporalBuilder> block) {
        if (temporal != null) {
            throw invalid("temporal() is declared twice");
        }
        temporal = new TemporalBuilder();
        block.accept(temporal);
    }

    /** Declares a list view (docs/design/02-metamodel.md section 7). */
    public void listView(String viewName, Consumer<ListViewDefinition.Builder> block) {
        if (listViews.containsKey(viewName)) {
            throw invalid("list view '" + viewName + "' is declared twice");
        }
        ListViewDefinition.Builder builder = new ListViewDefinition.Builder(viewName);
        block.accept(builder);
        listViews.put(viewName, builder.build());
    }

    EntityDefinition build() {
        requireNotBlank(physicalTable, "physical table");
        requireNotBlank(primaryKey, "primary key");
        if (!fields.containsKey(primaryKey)) {
            throw invalid("primary key '" + primaryKey + "' is not a declared field");
        }
        TemporalSpec temporalSpec = temporal == null ? null : buildTemporal();
        validateUniqueColumns(temporalSpec);
        validateVersionField();
        validateLifecycle();
        addImplicitReferences();
        validateReferences();
        validateUniqueConstraints();
        validateListViews();

        return new EntityDefinition(
            name,
            physicalTable,
            primaryKey,
            Collections.unmodifiableMap(new LinkedHashMap<>(fields)),
            stateField,
            List.copyOf(transitions),
            List.copyOf(guards),
            List.copyOf(references),
            List.copyOf(uniqueConstraints),
            Collections.unmodifiableMap(new LinkedHashMap<>(listViews)),
            temporalSpec
        );
    }

    /**
     * Checks what a temporal entity must not declare itself and adds its system fields. The identity has to be a
     * {@link SemanticKind.SemanticIdentity}: all versions of an instance share it and it is registered in
     * {@code entity_registry}, whose key is a UUID.
     */
    private TemporalSpec buildTemporal() {
        FieldDefinition key = fields.get(primaryKey);
        if (!(key.kind() instanceof SemanticKind.SemanticIdentity)) {
            throw invalid("the primary key of a temporal entity must be a SemanticIdentity (a UUID)");
        }
        for (FieldDefinition field : fields.values()) {
            if (TemporalSpec.isSystemField(field.name())) {
                throw invalid("field '" + field.name() + "' is a temporal system field and is added by the platform");
            }
            if (field.kind() instanceof SemanticKind.Version) {
                throw invalid("a temporal entity must not declare a Version field; versionNo is added by the platform");
            }
            if (field.kind() instanceof SemanticKind.Temporal t && t.role() == TemporalRole.SYSTEM_RECORDED) {
                throw invalid("a temporal entity must not declare a SYSTEM_RECORDED field; createdTime is added "
                    + "by the platform");
            }
        }
        TemporalSpec spec = temporal.build();
        requireIdentifier(spec.rowIdColumn(), "row id column");
        Map<String, String> columns = temporal.columns();
        addSystemField(TemporalSpec.VERSION_NO, columns, f -> f.immutable(true).asVersion());
        addSystemField(TemporalSpec.EFFECT_START_TIME, columns, f -> f.asTemporal(TemporalRole.VALID_FROM));
        addSystemField(TemporalSpec.CREATED_TIME, columns, f -> f.asTemporal(TemporalRole.SYSTEM_RECORDED));
        addSystemField(TemporalSpec.PROCESS_SEQ_ID, columns, f -> f.asNumeric(19, 0));
        addSystemField(TemporalSpec.DELETED, columns, FieldBuilder::asBool);
        return spec;
    }

    private void addSystemField(String name, Map<String, String> columns, Consumer<FieldBuilder> kind) {
        String column = columns.get(name);
        requireIdentifier(column, "column of system field '" + name + "'");
        FieldBuilder fb = new FieldBuilder(name);
        fb.physicalColumn(column);
        kind.accept(fb);
        fields.put(name, fb.build());
    }

    private void requireIdentifier(String value, String what) {
        try {
            SqlIdentifiers.require(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid(what + " '" + value + "' is not a valid SQL identifier");
        }
    }

    private void validateUniqueColumns(TemporalSpec temporalSpec) {
        Set<String> seen = new HashSet<>();
        if (temporalSpec != null) {
            seen.add(temporalSpec.rowIdColumn().toLowerCase());
        }
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
            if (!guards.isEmpty()) {
                throw invalid("transition guards require a lifecycle (stateTransitions)");
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
        Set<String> codes = new HashSet<>();
        for (GuardDefinition guard : guards) {
            if (!codes.add(guard.code())) {
                throw invalid("guard code '" + guard.code() + "' is declared twice");
            }
            if (!GuardDefinition.ANY.equals(guard.from())) {
                requireAllowedState(code, guard.from());
            }
            requireAllowedState(code, guard.to());
        }
    }

    private void addImplicitReferences() {
        Set<String> explicit = new HashSet<>();
        references.forEach(ref -> explicit.add(ref.sourceField()));
        for (FieldDefinition field : fields.values()) {
            if (field.kind() instanceof SemanticKind.Reference ref) {
                if (explicit.contains(field.name())) {
                    throw invalid("field '" + field.name() + "' is a Reference and must not also be declared with reference()");
                }
                references.add(new ReferenceDefinition(field.name(), ref.targetEntity()));
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

    private void validateUniqueConstraints() {
        Set<String> names = new HashSet<>();
        for (UniqueConstraint unique : uniqueConstraints) {
            try {
                SqlIdentifiers.require(unique.name());
            } catch (IllegalArgumentException e) {
                throw invalid("unique constraint name '" + unique.name() + "' is not a valid SQL identifier");
            }
            if (!names.add(unique.name().toLowerCase())) {
                throw invalid("unique constraint '" + unique.name() + "' is declared twice");
            }
            if (new HashSet<>(unique.fields()).size() != unique.fields().size()) {
                throw invalid("unique constraint '" + unique.name() + "' lists a field twice");
            }
            unique.fields().forEach(f -> requireField(f, "unique constraint '" + unique.name() + "'"));
        }
    }

    private void validateListViews() {
        for (ListViewDefinition view : listViews.values()) {
            String where = "list view '" + view.name() + "'";
            view.columns().forEach(f -> requireField(f, where));
            view.filters().forEach(f -> requireField(f, where));
            view.sorts().forEach(f -> requireField(f, where));
            if (view.defaultSort() != null && !view.sorts().contains(view.defaultSort().field())) {
                throw invalid(where + ": default sort '" + view.defaultSort().field() + "' is not among its sorts");
            }
        }
    }

    private void requireField(String field, String where) {
        if (!fields.containsKey(field)) {
            throw invalid(where + " refers to unknown field '" + field + "'");
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
