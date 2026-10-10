package com.jabiz.entity;

import com.jabiz.entity.i18n.I18nText;
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
    private final Set<String> declaredInitialStates = new java.util.LinkedHashSet<>();
    private final List<GuardDefinition> guards = new ArrayList<>();
    private final List<CheckDefinition> checks = new ArrayList<>();
    private final List<ReferenceDefinition> references = new ArrayList<>();
    private final List<UniqueConstraint> uniqueConstraints = new ArrayList<>();
    private final Map<String, ListViewDefinition> listViews = new LinkedHashMap<>();
    private TemporalBuilder temporal;
    private boolean publishChanges;
    private String displayField;

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
        declaredInitialStates.addAll(stb.initialStates());
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
     * Attaches a rule over the whole state of the entity, evaluated on every insert and every update that changes
     * something (docs/design/02-metamodel.md section 4.1). Codes are unique within the entity.
     */
    public void check(String code, EntityCheck check) {
        CheckDefinition definition = new CheckDefinition(code, check);
        if (checks.stream().anyMatch(existing -> existing.code().equals(code))) {
            throw invalid("check '" + code + "' is declared twice");
        }
        checks.add(definition);
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

    /**
     * Declares that the combination of the text fields {@code fieldNames} is unique regardless of case
     * (decision D36): {@code A@x.com} and {@code a@x.com} are the same. The supporting index is on
     * {@code lower(column)}.
     */
    public void uniqueIgnoreCase(String constraintName, String... fieldNames) {
        uniqueConstraints.add(new UniqueConstraint(constraintName, List.of(fieldNames), true));
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

    /**
     * Makes every committed write of the entity publish an entity change event through the outbox
     * (docs/design/11-ledger-events-jobs.md section 2): the event names the changed fields, never their values.
     */
    public void publishChanges() {
        publishChanges = true;
    }

    /**
     * The field that stands for an instance where it is referenced (a text or multilingual text, not sensitive):
     * used by lookups, labels of reference columns and reference pickers (docs/design/16-content-authoring.md
     * section 2).
     */
    public void display(String field) {
        if (displayField != null) {
            throw invalid("display is declared twice");
        }
        displayField = field;
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
        if (fields.get(primaryKey).isMasked()) {
            throw invalid("primary key '" + primaryKey + "' cannot be masked");
        }
        TemporalSpec temporalSpec = temporal == null ? null : buildTemporal();
        validateUniqueColumns(temporalSpec);
        validateVersionField();
        validateLifecycle();
        addImplicitReferences();
        validateReferences();
        validateUniqueConstraints();
        validateListViews();
        validateDisplay();
        validateMonetaryScales();

        return new EntityDefinition(
            name,
            physicalTable,
            primaryKey,
            Collections.unmodifiableMap(new LinkedHashMap<>(fields)),
            stateField,
            List.copyOf(transitions),
            List.copyOf(guards),
            List.copyOf(checks),
            List.copyOf(references),
            List.copyOf(uniqueConstraints),
            Collections.unmodifiableMap(new LinkedHashMap<>(listViews)),
            temporalSpec,
            publishChanges,
            displayField,
            new java.util.LinkedHashSet<>(declaredInitialStates)
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
        for (String initial : declaredInitialStates) {
            requireAllowedState(code, initial);
            if (transitions.stream().noneMatch(rule -> rule.from().equals(initial))) {
                throw invalid("initial state '" + initial + "' has no transition out of it");
            }
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
            if (unique.ignoreCase()) {
                unique.fields().stream().filter(f -> !(fields.get(f).kind() instanceof SemanticKind.Text))
                    .findFirst().ifPresent(f -> {
                        throw invalid("unique constraint '" + unique.name() + "' ignores case, but field '" + f
                            + "' is not text");
                    });
            }
        }
    }

    private void validateListViews() {
        for (ListViewDefinition view : listViews.values()) {
            String where = "list view '" + view.name() + "'";
            view.columns().forEach(f -> requireField(f, where));
            view.filters().forEach(f -> requireField(f, where));
            view.sorts().forEach(f -> requireField(f, where));
            for (List<String> names : List.of(view.columns(), view.filters(), view.sorts())) {
                names.stream().filter(f -> fields.get(f).sensitive()).findFirst().ifPresent(f -> {
                    throw invalid(where + " shows, filters or sorts sensitive field '" + f + "'");
                });
            }
            if (view.defaultSort() != null && fields.containsKey(view.defaultSort().field())
                && fields.get(view.defaultSort().field()).isMasked()) {
                // Everyone gets the default order, holders of the field's permission or not.
                throw invalid(where + ": default sort '" + view.defaultSort().field() + "' is masked");
            }
            if (view.defaultSort() != null && !view.sorts().contains(view.defaultSort().field())) {
                throw invalid(where + ": default sort '" + view.defaultSort().field() + "' is not among its sorts");
            }
        }
    }

    /**
     * A SCALE rule on a monetary field replaces the kind's own check (EntityValidator), so it may not allow more
     * digits than the currency's scale: the column could not hold them.
     */
    private void validateMonetaryScales() {
        for (FieldDefinition field : fields.values()) {
            if (!(field.kind() instanceof SemanticKind.Monetary monetary)) {
                continue;
            }
            for (RuleSpec spec : field.ruleSpecs()) {
                if (RuleKinds.SCALE.equals(spec.kind())
                    && ((Number) spec.params().get("scale")).intValue() > monetary.scale()) {
                    throw invalid("rule " + spec.code() + " of monetary field '" + field.name() + "' allows "
                        + spec.params().get("scale") + " digits after the point, more than the scale "
                        + monetary.scale() + " of " + monetary.currency());
                }
            }
        }
    }

    private void validateDisplay() {
        if (displayField == null) {
            return;
        }
        requireField(displayField, "display");
        FieldDefinition field = fields.get(displayField);
        if (!(field.kind() instanceof SemanticKind.Text) && !I18nText.is(field.kind())) {
            throw invalid("display field '" + displayField + "' must be a Text or " + I18nText.KIND_ID + " field");
        }
        if (field.sensitive()) {
            throw invalid("display field '" + displayField + "' is sensitive");
        }
        if (field.isMasked()) {
            // Display texts go wherever the instance is referenced (labels, lookups), unmasked.
            throw invalid("display field '" + displayField + "' is masked");
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
