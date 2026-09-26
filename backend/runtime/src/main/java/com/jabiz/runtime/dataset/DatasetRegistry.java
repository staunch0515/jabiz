package com.jabiz.runtime.dataset;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetPolicy;
import com.jabiz.dataset.DatasetScope;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.ListViewDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.SemanticKinds;
import com.jabiz.entity.TemporalRole;
import com.jabiz.query.QueryOperator;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of all dataset definitions, populated once at startup from every {@link DatasetDefinition} bean
 * (docs/design/03-dataset.md).
 *
 * <p>An entity may be served by several datasets, exactly one of which is its default; generic lookups by
 * entity type (URN resolution, reference checks) use the default. Misconfiguration is reported at startup, all
 * problems at once (section 4): unknown target entity or storage engine, missing or duplicate default,
 * scope fields that do not exist or cannot be compared for equality, soft-delete fields of the wrong kind,
 * unknown list views, and undeclared permissions (a warning in the {@code dev} profile). The problems are reported
 * by {@link com.jabiz.runtime.check.PlatformCheckRunner} together with those of the other startup checks.
 */
@Component
public final class DatasetRegistry implements PlatformCheck {

    private final Map<String, DatasetDefinition> byResourceId = new LinkedHashMap<>();
    private final Map<String, DatasetDefinition> defaults = new LinkedHashMap<>();
    private final List<CheckProblem> problems;

    public static final String CATEGORY = "DATASET";

    public DatasetRegistry(
        ObjectProvider<DatasetDefinition> beans,
        EntityDefinitionRegistry entities,
        StorageAdapterRegistry storage,
        Environment environment
    ) {
        boolean development = environment.acceptsProfiles(Profiles.of("dev"));
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        beans.orderedStream().forEach(dataset -> {
            String label = "Dataset " + dataset.resourceId();
            if (byResourceId.putIfAbsent(dataset.resourceId(), dataset) != null) {
                problems.add(label + ": duplicate resourceId");
                return;
            }
            requireEngine(storage, dataset.storage().connectionPoolRef(), label, problems);
            String replica = dataset.storage().readReplicaRef();
            if (replica != null && !replica.isBlank()) {
                requireEngine(storage, replica, label, problems);
            }
            Optional<EntityDefinition> target = entities.find(dataset.targetEntityType());
            if (target.isEmpty()) {
                problems.add(label + ": targets unregistered entity type " + dataset.targetEntityType());
            } else {
                checkAgainstEntity(dataset, target.get(), label, problems);
            }
            if (dataset.isDefault()) {
                DatasetDefinition other = defaults.putIfAbsent(dataset.targetEntityType(), dataset);
                if (other != null) {
                    problems.add(label + ": entity type " + dataset.targetEntityType()
                        + " already has default dataset " + other.resourceId());
                }
            }
            if (!dataset.permissions().isDeclared()) {
                String message = label + ": read and write permissions are not declared";
                if (development) {
                    warnings.add(message + " (allowed in the dev profile only)");
                } else {
                    problems.add(message);
                }
            }
        });
        for (EntityDefinition entity : entities.all()) {
            if (!defaults.containsKey(entity.name)) {
                problems.add("Entity " + entity.name + " has no default dataset");
            }
        }
        List<CheckProblem> found = new ArrayList<>();
        problems.forEach(text -> found.add(CheckProblem.error(CATEGORY, text)));
        warnings.forEach(text -> {
            CheckProblem error = CheckProblem.error(CATEGORY, text);
            found.add(CheckProblem.warning(CATEGORY, error.location(), error.message()));
        });
        this.problems = List.copyOf(found);
    }

    /** Problems of the definitions, reported with the other startup checks rather than failing bean creation. */
    @Override
    public List<CheckProblem> check() {
        return problems;
    }

    public Optional<DatasetDefinition> findById(String resourceId) {
        return Optional.ofNullable(byResourceId.get(resourceId));
    }

    /** The default dataset of the entity type. */
    public Optional<DatasetDefinition> findForEntity(String entityType) {
        return Optional.ofNullable(defaults.get(entityType));
    }

    public Collection<DatasetDefinition> all() {
        return Collections.unmodifiableCollection(byResourceId.values());
    }

    private static void checkAgainstEntity(DatasetDefinition dataset, EntityDefinition entity, String label,
        List<String> problems) {
        for (DatasetScope.Entry entry : dataset.scope().entries()) {
            FieldDefinition field = entity.fields.get(entry.field());
            if (field == null) {
                problems.add(label + ": scope field " + entry.field() + " does not exist on " + entity.name);
            } else if (!allowsEquality(field.kind())) {
                problems.add(label + ": scope field " + entry.field() + " cannot be compared for equality");
            }
        }
        DatasetPolicy policy = dataset.policy();
        String override = dataset.storage().physicalTableOverride();
        if (entity.temporal && override != null && !override.isBlank()) {
            problems.add(label + ": temporal entity " + entity.name + " cannot be stored in an override table; "
                + "its versions, operation items and reverts all refer to " + entity.physicalTable);
        }
        if (policy.softDelete() && entity.temporal) {
            problems.add(label + ": soft delete is not available for temporal entity " + entity.name
                + "; deleting it writes a tombstone version");
        }
        if (policy.softDelete()) {
            requireFieldKind(entity, policy.softDeleteField(), SemanticKind.Bool.class::isInstance,
                "soft-delete field", "Bool", label, problems);
        }
        if (policy.softDeleteTimeField() != null) {
            requireFieldKind(entity, policy.softDeleteTimeField(),
                kind -> kind instanceof SemanticKind.Temporal t && t.role() == TemporalRole.SYSTEM_RECORDED,
                "soft-delete time field", "Temporal(SYSTEM_RECORDED)", label, problems);
        }
        boolean explicitListView = !ListViewDefinition.DEFAULT.equals(dataset.listView());
        if (explicitListView && entity.listView(dataset.listView()).isEmpty()) {
            problems.add(label + ": list view " + dataset.listView() + " does not exist on " + entity.name);
        }
    }

    private static boolean allowsEquality(SemanticKind kind) {
        try {
            return SemanticKinds.allows(kind, QueryOperator.EQ);
        } catch (IllegalArgumentException unregisteredCustomKind) {
            return true; // reported by SemanticKindChecker
        }
    }

    private static void requireFieldKind(EntityDefinition entity, String fieldName,
        java.util.function.Predicate<SemanticKind> accepted, String role, String expected, String label,
        List<String> problems) {
        FieldDefinition field = entity.fields.get(fieldName);
        if (field == null) {
            problems.add(label + ": " + role + " " + fieldName + " does not exist on " + entity.name);
        } else if (!accepted.test(field.kind())) {
            problems.add(label + ": " + role + " " + fieldName + " must be " + expected);
        }
    }

    private static void requireEngine(StorageAdapterRegistry storage, String poolRef, String label,
        List<String> problems) {
        if (!storage.hasEngine(poolRef)) {
            problems.add(label + ": refers to unregistered storage engine " + poolRef);
        }
    }
}
