package com.jabiz.query;

import com.jabiz.context.DataPeriod;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetScope;
import com.jabiz.dataset.DatasetPolicy;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.SemanticKinds;
import com.jabiz.entity.TemporalSpec;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.i18n.PlatformLanguages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Compiles logical queries into physical query plans for SQL dialects.
 * All values are bound as named parameters; identifiers come from metadata only.
 *
 * <p>Caller mistakes (unknown field, operator the field's semantic kind does not allow, value of the wrong
 * type) are reported as {@link ValidationException}.
 */
public class QueryCompiler {

    /** Finds the definitions of referenced entities; references to temporal entities are compared as UUIDs. */
    private final Function<String, Optional<EntityDefinition>> entities;

    /** A compiler that does not know other entities: reference values are bound as given. */
    public QueryCompiler() {
        this(name -> Optional.empty());
    }

    public QueryCompiler(Function<String, Optional<EntityDefinition>> entities) {
        this.entities = Objects.requireNonNull(entities, "entities must not be null");
    }

    /** Allocates unique named parameters and remembers their values. */
    public static final class Binder {
        private final String prefix;
        private final Map<String, BoundValue> params = new LinkedHashMap<>();
        private int next = 0;

        public Binder(String prefix) {
            this.prefix = prefix;
        }

        /** Registers the value under a fresh name and returns that name. */
        public String bind(BoundValue value) {
            String name = prefix + next++;
            params.put(name, value);
            return name;
        }

        /**
         * Registers a value under a fixed name, used for the platform's reserved parameters ({@code __asOf},
         * {@code __knownAt}). Binding the same name again must supply the same value.
         */
        public String bindNamed(String name, BoundValue value) {
            BoundValue existing = params.putIfAbsent(name, value);
            if (existing != null && !existing.equals(value)) {
                throw new IllegalStateException("Parameter " + name + " is already bound to another value");
            }
            return name;
        }

        public Map<String, BoundValue> params() {
            return params;
        }
    }

    /** Reserved parameter of the business time of a temporal read. */
    public static final String AS_OF_PARAM = "__asOf";
    /** Reserved parameter of the recording time of a temporal read. */
    public static final String KNOWN_AT_PARAM = "__knownAt";
    /** Alias of the sub-select of the versions in effect. */
    public static final String VERSIONS_ALIAS = "v";

    /**
     * Compiles an entity query against the dataset, applying the dataset scope and limits.
     * The entity must not be temporal: temporal reads need a {@link TimeSlice}.
     *
     * @param scopeValues the dataset scope resolved for the current request
     *                    ({@link com.jabiz.dataset.DatasetScope#resolve})
     */
    public PhysicalQueryPlan compile(DatasetDefinition dataset, EntityDefinition def, EntityQuery query,
        Map<String, Object> scopeValues) {
        return compile(dataset, def, query, scopeValues, null);
    }

    /**
     * Compiles an entity query against the dataset, applying the dataset scope and limits. For a temporal entity the
     * query reads the versions in effect at {@code slice} (decision D3): tombstones are excluded, then the scope and
     * then the query's own conditions are applied, all outside the sub-select that picks the versions, so an
     * entity moved out of the scope never shows an older version that was still within it.
     *
     * @param slice required for temporal entities, ignored for others
     */
    public PhysicalQueryPlan compile(DatasetDefinition dataset, EntityDefinition def, EntityQuery query,
        Map<String, Object> scopeValues, TimeSlice slice) {
        int safeLimit = Math.min(query.limit(), dataset.policy().maxQueryBatchSize());

        Binder binder = new Binder("p");
        List<String> fragments = new ArrayList<>();
        String source = source(dataset, def, slice, binder, innerCondition(query.predicate(), def, binder));
        if (def.temporal) {
            fragments.add(notDeleted(def));
        }

        String scope = scopeCondition(dataset, def, scopeValues, binder);
        if (!scope.isBlank()) {
            fragments.add(scope);
        }
        if (query.predicate() != null) {
            String compiled = compilePredicate(query.predicate(), def, binder);
            if (!compiled.isBlank()) {
                fragments.add(compiled);
            }
        }

        List<PhysicalQueryPlan.PhysicalSort> sorts = new ArrayList<>();
        for (SortOrder sort : query.sorts()) {
            FieldDefinition fd = resolveField(def, sort.field());
            sorts.add(new PhysicalQueryPlan.PhysicalSort(SqlIdentifiers.require(fd.physicalColumn()), sort.ascending()));
        }
        String primaryKeyColumn = SqlIdentifiers.require(def.primaryKeyColumn());
        if (sorts.stream().noneMatch(s -> s.physicalColumn().equalsIgnoreCase(primaryKeyColumn))) {
            // A deterministic order is required for stable pagination.
            sorts.add(new PhysicalQueryPlan.PhysicalSort(primaryKeyColumn, true));
        }

        return new PhysicalQueryPlan(
            source,
            String.join(" AND ", fragments),
            binder.params(),
            sorts,
            query.offset(),
            safeLimit,
            dataset.policy().queryTimeout()
        );
    }

    /** Most rows a lookup returns (docs/design/16-content-authoring.md section 2). */
    public static final int MAX_LOOKUP = 20;

    /**
     * Instances of the dataset's target entity whose display field contains {@code q}, ignoring case (any language
     * of a multilingual text), within the scope (for a temporal entity: versions in effect at {@code slice}, then the
     * scope, decision D3), ordered by the display text in {@code language}, then {@code defaultLanguage}, then any
     * language, then by primary key. The wildcards of {@code q} match literally.
     *
     * @param q     text to look for; null or blank matches every instance
     * @param limit capped at {@value #MAX_LOOKUP} and the dataset's batch size
     * @throws IllegalStateException if the entity declares no display field
     */
    public RawQueryPlan compileLookup(DatasetDefinition dataset, EntityDefinition def, String q, String language,
        String defaultLanguage, int limit, Map<String, Object> scopeValues, TimeSlice slice) {
        FieldDefinition display = requireDisplay(def);
        Binder binder = new Binder("p");
        String alias = def.temporal ? VERSIONS_ALIAS : "t";
        String column = alias + "." + SqlIdentifiers.require(display.physicalColumn());
        List<String> conditions = readConditions(dataset, def, scopeValues, binder);
        if (q != null && !q.isBlank()) {
            String pattern = ":" + binder.bind(BoundValue.of("%" + escapeLike(q.strip()) + "%"));
            conditions.add(I18nText.is(display.kind())
                ? "EXISTS (SELECT 1 FROM jsonb_each_text(" + column + ") AS l(lang, text) WHERE l.text ILIKE "
                    + pattern + " ESCAPE '\\')"
                : column + " ILIKE " + pattern + " ESCAPE '\\'");
        }
        String order = column;
        if (I18nText.is(display.kind())) {
            List<String> languages = new ArrayList<>();
            for (String candidate : concat(language, defaultLanguage)) {
                if (PlatformLanguages.isSupported(candidate) && !languages.contains(candidate)) {
                    languages.add(candidate);
                }
            }
            List<String> texts = new ArrayList<>();
            for (String candidate : languages) {
                texts.add(column + " ->> :" + binder.bind(BoundValue.of(candidate)));
            }
            order = "COALESCE(" + String.join(", ", texts) + ")";
        }
        int safeLimit = Math.max(1, Math.min(Math.min(limit, MAX_LOOKUP), dataset.policy().maxQueryBatchSize()));
        String sql = select(dataset, def, alias, conditions, binder, slice, null)
            + " ORDER BY " + order + " ASC, " + alias + "." + SqlIdentifiers.require(def.primaryKeyColumn())
            + " ASC LIMIT " + safeLimit;
        return new RawQueryPlan(sql, binder.params(), dataset.policy().queryTimeout());
    }

    /**
     * Instances of the dataset's target entity with the given primary keys, within the scope as for
     * {@link #compileLookup}; used to show the display texts of references. Keys that do not convert to the
     * entity's key type are reported as {@code INVALID_VALUE}.
     *
     * @throws IllegalStateException if the entity declares no display field
     */
    public RawQueryPlan compileLabels(DatasetDefinition dataset, EntityDefinition def, List<?> ids,
        Map<String, Object> scopeValues, TimeSlice slice) {
        requireDisplay(def);
        FieldDefinition key = def.field(def.primaryKey);
        List<Object> keys = new ArrayList<>(ids.size());
        for (Object id : ids) {
            if (id == null) {
                throw invalidValue(key, "ids must not contain null");
            }
            keys.add(coerce(def, key, id));
        }
        Binder binder = new Binder("p");
        String alias = def.temporal ? VERSIONS_ALIAS : "t";
        List<String> conditions = readConditions(dataset, def, scopeValues, binder);
        String keyColumn = SqlIdentifiers.require(def.primaryKeyColumn());
        String keyList = keys.isEmpty() ? null : ":" + binder.bind(BoundValue.of(keys));
        conditions.add(keyList == null ? "1 = 0" : alias + "." + keyColumn + " IN (" + keyList + ")");
        // The key never changes: only the versions of these instances are read (decision D29).
        String sql = select(dataset, def, alias, conditions, binder, slice,
            keyList == null ? null : keyColumn + " IN (" + keyList + ")") + " LIMIT " + Math.max(1, keys.size());
        return new RawQueryPlan(sql, binder.params(), dataset.policy().queryTimeout());
    }

    private static FieldDefinition requireDisplay(EntityDefinition def) {
        if (def.displayField == null) {
            throw new IllegalStateException(def.name + " declares no display field");
        }
        return def.field(def.displayField);
    }

    /** Tombstones excluded and the scope applied, as for every read of the dataset's target entity. */
    private List<String> readConditions(DatasetDefinition dataset, EntityDefinition def, Map<String, Object> scopeValues,
        Binder binder) {
        List<String> conditions = new ArrayList<>();
        if (def.temporal) {
            conditions.add(notDeleted(def));
        }
        String scope = scopeCondition(dataset, def, scopeValues, binder);
        if (!scope.isBlank()) {
            conditions.add(scope);
        }
        return conditions;
    }

    /**
     * SELECT over the entity's source, aliased {@code alias}, restricted by {@code conditions}; {@code inner}, if
     * given, is a condition on immutable columns that the versions are read with as well (decision D29).
     */
    private String select(DatasetDefinition dataset, EntityDefinition def, String alias, List<String> conditions,
        Binder binder, TimeSlice slice, String inner) {
        String source = source(dataset, def, slice, binder, inner);
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(source);
        if (!def.temporal) {
            // The temporal source is already aliased VERSIONS_ALIAS.
            sql.append(" ").append(alias);
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        return sql.toString();
    }

    /** Makes {@code %}, {@code _} and the escape character itself match literally in a LIKE pattern. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<String> concat(String language, String defaultLanguage) {
        List<String> candidates = new ArrayList<>();
        if (language != null) {
            candidates.add(language);
        }
        if (defaultLanguage != null) {
            candidates.add(defaultLanguage);
        }
        candidates.addAll(PlatformLanguages.CODES);
        return candidates;
    }

    /** Table for the entity within the dataset: the override applies to the target entity only. */
    public String resolveTable(DatasetDefinition dataset, EntityDefinition def) {
        if (dataset.isTarget(def.name)) {
            String override = dataset.storage().physicalTableOverride();
            if (override != null && !override.isBlank()) {
                return SqlIdentifiers.require(override);
            }
        }
        return SqlIdentifiers.require(def.physicalTable);
    }

    /**
     * FROM item of a read: the table, or for a temporal entity the sub-select of the versions in effect at
     * {@code slice}, aliased {@value #VERSIONS_ALIAS}. The sub-select filters by time only.
     */
    public String source(DatasetDefinition dataset, EntityDefinition def, TimeSlice slice, Binder binder) {
        return source(dataset, def, slice, binder, null);
    }

    /**
     * As {@link #source(DatasetDefinition, EntityDefinition, TimeSlice, Binder)}, with {@code inner} (unqualified
     * columns) also restricting the versions read (decision D29). It must hold for every version of an instance or
     * for none, as a condition on immutable fields does: then it selects whole instances, and the version in effect is
     * the same as without it. A write-once entity's versions are read directly: each instance has only one.
     */
    public String source(DatasetDefinition dataset, EntityDefinition def, TimeSlice slice, Binder binder,
        String inner) {
        String table = resolveTable(dataset, def);
        if (!def.temporal) {
            return table;
        }
        if (slice == null) {
            throw new IllegalArgumentException("Reading temporal entity " + def.name + " needs a time slice");
        }
        String id = SqlIdentifiers.require(def.primaryKeyColumn());
        String effective = SqlIdentifiers.require(def.systemColumn(TemporalSpec.EFFECT_START_TIME));
        String version = SqlIdentifiers.require(def.systemColumn(TemporalSpec.VERSION_NO));
        boolean writeOnce = def.temporalSpec.writeOnce();
        StringBuilder sql = new StringBuilder(writeOnce ? "(SELECT * FROM " : "(SELECT DISTINCT ON (" + id + ") * FROM ")
            .append(table).append(" WHERE ").append(effective).append(" <= :")
            .append(binder.bindNamed(AS_OF_PARAM, BoundValue.of(slice.asOf())));
        if (slice.knownAt() != null) {
            sql.append(" AND ").append(SqlIdentifiers.require(def.systemColumn(TemporalSpec.CREATED_TIME)))
                .append(" <= :").append(binder.bindNamed(KNOWN_AT_PARAM, BoundValue.of(slice.knownAt())));
        }
        if (inner != null && !inner.isBlank()) {
            sql.append(" AND ").append(inner);
        }
        if (!writeOnce) {
            sql.append(" ORDER BY ").append(id).append(", ").append(effective).append(" DESC, ").append(version)
                .append(" DESC");
        }
        sql.append(") ").append(VERSIONS_ALIAS);
        return sql.toString();
    }

    /** Excludes tombstones of a temporal entity; the first condition applied to its versions in effect. */
    public String notDeleted(EntityDefinition def) {
        return "NOT " + SqlIdentifiers.require(def.systemColumn(TemporalSpec.DELETED));
    }

    /**
     * Expression that stands for the entity in a SQL template ({@code {{Entity}}}, docs/design/05-sql-template.md
     * section 3): the table, the table restricted to the scope, or for a temporal entity the versions in effect
     * without tombstones, restricted to the scope in that order (decision D3). A public dataset is projected to its
     * whitelisted fields. Masked fields are projected in their masked form.
     */
    public String templateExpression(DatasetDefinition dataset, EntityDefinition def, Map<String, Object> scopeValues,
        TimeSlice slice, Binder binder) {
        return templateExpression(dataset, def, scopeValues, slice, binder, java.util.Set.of());
    }

    /**
     * As {@link #templateExpression(DatasetDefinition, EntityDefinition, Map, TimeSlice, Binder)}, with the masked
     * fields in {@code plainFields} in plain text. Every other masked field is replaced by its masked form inside
     * the expression (docs/design/10-security.md section 13.1), so no condition, sort or join of the template can
     * see the plain value either.
     */
    public String templateExpression(DatasetDefinition dataset, EntityDefinition def, Map<String, Object> scopeValues,
        TimeSlice slice, Binder binder, java.util.Set<String> plainFields) {
        String source = source(dataset, def, slice, binder);
        List<String> conditions = new ArrayList<>();
        if (def.temporal) {
            conditions.add(notDeleted(def));
        }
        String scope = scopeCondition(dataset, def, scopeValues, binder);
        if (!scope.isBlank()) {
            conditions.add(scope);
        }
        String columns = "*";
        if (dataset.isPublic() && dataset.isTarget(def.name)) {
            // Only the whitelist leaves a public dataset, so SQL naming any other column fails instead of reading it
            // (docs/design/15-public-access.md section 2).
            columns = String.join(", ", dataset.publicRead().fields().stream()
                .map(field -> SqlIdentifiers.require(def.physicalColumn(field))).toList());
        } else if (def.maskedFields().stream().anyMatch(field -> !plainFields.contains(field.name()))) {
            columns = maskedColumns(def, plainFields);
        } else if (conditions.isEmpty()) {
            return source;
        }
        return "(SELECT " + columns + " FROM " + source
            + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions)) + ")";
    }

    /**
     * Condition of a data period (docs/design/10-security.md section 13.2): the field's time within the period, or the
     * referenced instance's; the referenced time field is immutable, so any of its versions answers.
     */
    private String periodCondition(EntityDefinition def, FieldDefinition field, DatasetScope.PeriodCondition period,
        Binder binder) {
        String column = SqlIdentifiers.require(field.physicalColumn());
        if (period.referencedField() == null) {
            return range(column, period.period(), binder);
        }
        if (!(field.kind() instanceof SemanticKind.Reference reference)) {
            throw new IllegalStateException(def.name + "." + field.name() + " is not a reference");
        }
        EntityDefinition target = entities.apply(reference.targetEntity()).orElseThrow(
            () -> new IllegalStateException("Unknown entity " + reference.targetEntity()));
        return column + " IN (SELECT " + SqlIdentifiers.require(target.primaryKeyColumn()) + " FROM "
            + SqlIdentifiers.require(target.physicalTable) + " WHERE "
            + range(SqlIdentifiers.require(target.physicalColumn(period.referencedField())), period.period(), binder)
            + ")";
    }

    private static String range(String column, DataPeriod period, Binder binder) {
        List<String> bounds = new ArrayList<>(2);
        if (period.from() != null) {
            bounds.add(column + " >= :" + binder.bind(BoundValue.of(period.from())));
        }
        if (period.to() != null) {
            bounds.add(column + " < :" + binder.bind(BoundValue.of(period.to())));
        }
        return "(" + String.join(" AND ", bounds) + ")";
    }

    /** Every column of the entity, the masked fields outside {@code plainFields} replaced by their masked form. */
    private static String maskedColumns(EntityDefinition def, java.util.Set<String> plainFields) {
        List<String> columns = new ArrayList<>();
        for (FieldDefinition field : def.fields.values()) {
            String column = SqlIdentifiers.require(field.physicalColumn());
            columns.add(field.isMasked() && !plainFields.contains(field.name())
                ? field.masked().style().sql(column) + " AS " + column
                : column);
        }
        if (def.temporal) {
            columns.add(SqlIdentifiers.require(def.temporalSpec.rowIdColumn()));
        }
        return String.join(", ", columns);
    }

    /**
     * Condition enforced on every read of the dataset's target entity: the scope and the soft-delete
     * exclusion. Returns an empty string when nothing applies.
     *
     * @param scopeValues the dataset scope resolved for the current request
     */
    public String scopeCondition(DatasetDefinition dataset, EntityDefinition def, Map<String, Object> scopeValues,
        Binder binder) {
        if (!dataset.isTarget(def.name)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : scopeValues.entrySet()) {
            FieldDefinition fd = resolveField(def, entry.getKey());
            if (entry.getValue() instanceof DatasetScope.PeriodCondition period) {
                parts.add(periodCondition(def, fd, period, binder));
                continue;
            }
            Object value = FieldValueCoercer.coerce(fd, entry.getValue(), false);
            parts.add(SqlIdentifiers.require(fd.physicalColumn()) + " = :" + binder.bind(BoundValue.of(value)));
        }
        DatasetPolicy policy = dataset.policy();
        if (policy.softDelete()) {
            parts.add(SqlIdentifiers.require(def.physicalColumn(policy.softDeleteField())) + " IS NOT TRUE");
        }
        return String.join(" AND ", parts);
    }

    /**
     * The part of a query's condition that may restrict the versions read as well (decision D29): conditions on
     * immutable fields (the primary key included), which hold for every version of an instance or for none. Null
     * when there is none or the entity is not temporal.
     */
    String innerCondition(QueryPredicate predicate, EntityDefinition def, Binder binder) {
        if (!def.temporal || predicate == null) {
            return null;
        }
        QueryPredicate part = immutablePart(predicate, def);
        if (part == null) {
            return null;
        }
        String compiled = compilePredicate(part, def, binder);
        return compiled.isBlank() ? null : compiled;
    }

    /**
     * The conjuncts of {@code predicate} that read only immutable fields: all of it when it does, a subset of an AND's
     * conjuncts (each conjunct of a condition that holds also holds), an OR only when all of it does, else null.
     */
    static QueryPredicate immutablePart(QueryPredicate predicate, EntityDefinition def) {
        return switch (predicate) {
            case QueryPredicate.And and -> {
                List<QueryPredicate> parts = new ArrayList<>();
                for (QueryPredicate child : and.predicates()) {
                    QueryPredicate part = immutablePart(child, def);
                    if (part != null) {
                        parts.add(part);
                    }
                }
                yield parts.isEmpty() ? null : parts.size() == 1 ? parts.getFirst() : new QueryPredicate.And(parts);
            }
            case QueryPredicate.Or or -> or.predicates().isEmpty() || !or.predicates().stream()
                .allMatch(child -> immutablePart(child, def) == child) ? null : or;
            case QueryPredicate.KeyAfter after -> after;
            default -> immutableField(def, fieldOf(predicate)) ? predicate : null;
        };
    }

    private static String fieldOf(QueryPredicate predicate) {
        return switch (predicate) {
            case QueryPredicate.Eq p -> p.field();
            case QueryPredicate.Ne p -> p.field();
            case QueryPredicate.Gt p -> p.field();
            case QueryPredicate.Gte p -> p.field();
            case QueryPredicate.Lt p -> p.field();
            case QueryPredicate.Lte p -> p.field();
            case QueryPredicate.In p -> p.field();
            case QueryPredicate.Like p -> p.field();
            case QueryPredicate.IsNull p -> p.field();
            case QueryPredicate.IsNotNull p -> p.field();
            case QueryPredicate.Between p -> p.field();
            default -> null;
        };
    }

    private static boolean immutableField(EntityDefinition def, String name) {
        if (name == null) {
            return false;
        }
        if (name.equals(def.primaryKey)) {
            return true;
        }
        FieldDefinition field = def.fields.get(name);
        return field != null && field.immutable() && !TemporalSpec.isSystemField(name);
    }

    private String compilePredicate(QueryPredicate pred, EntityDefinition def, Binder binder) {
        return switch (pred) {
            case QueryPredicate.Eq eq -> eq.value() == null
                ? nullCheck(def, eq.field(), QueryOperator.IS_NULL)
                : comparison(QueryOperator.EQ, "=", def, eq.field(), eq.value(), binder);
            case QueryPredicate.Ne ne -> ne.value() == null
                ? nullCheck(def, ne.field(), QueryOperator.IS_NOT_NULL)
                : comparison(QueryOperator.NE, "<>", def, ne.field(), ne.value(), binder);
            case QueryPredicate.Gt gt -> comparison(QueryOperator.GT, ">", def, gt.field(), gt.value(), binder);
            case QueryPredicate.Gte gte -> comparison(QueryOperator.GTE, ">=", def, gte.field(), gte.value(), binder);
            case QueryPredicate.Lt lt -> comparison(QueryOperator.LT, "<", def, lt.field(), lt.value(), binder);
            case QueryPredicate.Lte lte -> comparison(QueryOperator.LTE, "<=", def, lte.field(), lte.value(), binder);
            case QueryPredicate.In in -> compileIn(in, def, binder);
            case QueryPredicate.Like like -> compileLike(like, def, binder);
            case QueryPredicate.IsNull isNull -> nullCheck(def, isNull.field(), QueryOperator.IS_NULL);
            case QueryPredicate.IsNotNull isNotNull -> nullCheck(def, isNotNull.field(), QueryOperator.IS_NOT_NULL);
            case QueryPredicate.Between between -> compileBetween(between, def, binder);
            case QueryPredicate.KeyAfter after -> {
                FieldDefinition key = resolveField(def, def.primaryKey);
                if (after.value() == null) {
                    throw invalidValue(key, "paging by key needs the last key");
                }
                yield SqlIdentifiers.require(key.physicalColumn()) + " > :"
                    + binder.bind(BoundValue.of(coerce(def, key, after.value())));
            }
            case QueryPredicate.And and -> {
                List<String> children = compileChildren(and.predicates(), def, binder);
                yield children.isEmpty() ? "" : "(" + String.join(" AND ", children) + ")";
            }
            case QueryPredicate.Or or -> {
                if (or.predicates().isEmpty()) {
                    yield "1 = 0";
                }
                List<String> children = new ArrayList<>();
                boolean alwaysTrue = false;
                for (QueryPredicate child : or.predicates()) {
                    // Every child is compiled, so that each is checked even when the result is already decided.
                    String compiled = compilePredicate(child, def, binder);
                    if (compiled.isBlank()) {
                        alwaysTrue = true;
                    }
                    children.add(compiled);
                }
                // A child that is always true makes the whole disjunction true.
                yield alwaysTrue ? "" : "(" + String.join(" OR ", children) + ")";
            }
        };
    }

    private List<String> compileChildren(List<QueryPredicate> predicates, EntityDefinition def, Binder binder) {
        List<String> children = new ArrayList<>();
        for (QueryPredicate child : predicates) {
            String compiled = compilePredicate(child, def, binder);
            if (!compiled.isBlank()) {
                children.add(compiled);
            }
        }
        return children;
    }

    private String compileIn(QueryPredicate.In in, EntityDefinition def, Binder binder) {
        FieldDefinition fd = requireOperator(def, in.field(), QueryOperator.IN);
        if (in.values() == null || in.values().isEmpty()) {
            return "1 = 0";
        }
        List<Object> coerced = new ArrayList<>(in.values().size());
        for (Object value : in.values()) {
            if (value == null) {
                throw invalidValue(fd, "IN list must not contain null");
            }
            coerced.add(coerce(def, fd, value));
        }
        String param = binder.bind(BoundValue.of(coerced));
        return SqlIdentifiers.require(fd.physicalColumn()) + " IN (:" + param + ")";
    }

    private String compileLike(QueryPredicate.Like like, EntityDefinition def, Binder binder) {
        FieldDefinition fd = requireOperator(def, like.field(), QueryOperator.LIKE);
        if (like.pattern() == null) {
            throw invalidValue(fd, "LIKE needs a pattern");
        }
        return SqlIdentifiers.require(fd.physicalColumn()) + " LIKE :" + binder.bind(BoundValue.of(like.pattern()));
    }

    private String compileBetween(QueryPredicate.Between between, EntityDefinition def, Binder binder) {
        FieldDefinition fd = requireOperator(def, between.field(), QueryOperator.BETWEEN);
        if (between.low() == null || between.high() == null) {
            throw invalidValue(fd, "BETWEEN needs both bounds");
        }
        String low = binder.bind(BoundValue.of(coerce(def, fd, between.low())));
        String high = binder.bind(BoundValue.of(coerce(def, fd, between.high())));
        return SqlIdentifiers.require(fd.physicalColumn()) + " BETWEEN :" + low + " AND :" + high;
    }

    private String nullCheck(EntityDefinition def, String field, QueryOperator operator) {
        FieldDefinition fd = requireOperator(def, field, operator);
        return SqlIdentifiers.require(fd.physicalColumn())
            + (operator == QueryOperator.IS_NULL ? " IS NULL" : " IS NOT NULL");
    }

    private String comparison(QueryOperator operator, String sql, EntityDefinition def, String field, Object value,
        Binder binder) {
        FieldDefinition fd = requireOperator(def, field, operator);
        if (value == null) {
            throw invalidValue(fd, "comparison " + operator + " with null is undefined");
        }
        return SqlIdentifiers.require(fd.physicalColumn()) + " " + sql + " :" + binder.bind(BoundValue.of(coerce(def, fd, value)));
    }

    private FieldDefinition requireOperator(EntityDefinition def, String field, QueryOperator operator) {
        FieldDefinition fd = resolveField(def, field);
        if (!SemanticKinds.allows(fd.kind(), operator)) {
            throw new ValidationException(List.of(new Violation(fd.name(), PlatformErrorCodes.OPERATOR_NOT_ALLOWED,
                "Operator " + operator + " is not allowed on field [" + fd.name() + "] with semantic " + fd.kind(),
                Map.of("operator", operator.name()))));
        }
        return fd;
    }

    private Object coerce(EntityDefinition def, FieldDefinition fd, Object value) {
        try {
            Object coerced = FieldValueCoercer.coerce(fd, value, false);
            if (fd.name().equals(def.primaryKey)) {
                return def.normalizeId(coerced);
            }
            if (fd.kind() instanceof SemanticKind.Reference ref) {
                return entities.apply(ref.targetEntity()).map(target -> target.normalizeId(coerced)).orElse(coerced);
            }
            return coerced;
        } catch (IllegalArgumentException e) {
            throw invalidValue(fd, e.getMessage());
        }
    }

    private static ValidationException invalidValue(FieldDefinition fd, String message) {
        return new ValidationException(List.of(new Violation(fd.name(), PlatformErrorCodes.INVALID_VALUE,
            "Field [" + fd.name() + "]: " + message)));
    }

    private FieldDefinition resolveField(EntityDefinition def, String fieldName) {
        return def.findField(fieldName).orElseThrow(() -> new ValidationException(List.of(new Violation(
            fieldName, PlatformErrorCodes.UNKNOWN_FIELD,
            "Field [" + fieldName + "] does not exist on entity [" + def.name + "]"))));
    }
}
