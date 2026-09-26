package com.jabiz.query.template;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which PostgreSQL types a semantic kind is compatible with (docs/design/05-sql-template.md section 6), keyed by the
 * kind's canonical Java type ({@link com.jabiz.entity.FieldValueCoercer#javaType}), so custom kinds are covered
 * through the type their support declares. Type names are those of {@code pg_type.typname} as JDBC metadata reports
 * them; arrays are the element name prefixed by {@code _}.
 */
public final class SqlTypeCompatibility {

    private static final Set<String> INTEGERS = Set.of("int2", "int4", "int8");

    /** Column types a result of the Java type may be read from. */
    private static final Map<Class<?>, Set<String>> RESULTS = Map.of(
        String.class, Set.of("text", "varchar", "bpchar", "name", "citext", "uuid"),
        BigDecimal.class, Set.of("numeric", "int2", "int4", "int8"),
        Long.class, INTEGERS,
        Integer.class, Set.of("int2", "int4"),
        Instant.class, Set.of("timestamptz", "timestamp"),
        Boolean.class, Set.of("bool"),
        UUID.class, Set.of("uuid"),
        Map.class, Set.of("jsonb", "json")
    );

    /**
     * Types the server may infer for a parameter bound as the Java type. The server converts between the numeric
     * types and between the text types, but not from text to {@code uuid}: identifiers of temporal entities are bound
     * as UUIDs.
     */
    private static final Map<Class<?>, Set<String>> PARAMETERS = Map.of(
        String.class, Set.of("text", "varchar", "bpchar", "name", "citext"),
        BigDecimal.class, Set.of("numeric", "int2", "int4", "int8"),
        Long.class, Set.of("int2", "int4", "int8", "numeric"),
        Integer.class, Set.of("int2", "int4", "int8", "numeric"),
        Instant.class, Set.of("timestamptz", "timestamp"),
        Boolean.class, Set.of("bool"),
        UUID.class, Set.of("uuid"),
        Map.class, Set.of("jsonb", "json")
    );

    private SqlTypeCompatibility() {}

    /** @return empty if compatible, else a description of the mismatch */
    public static Optional<String> checkResult(Class<?> javaType, String columnType) {
        return check(RESULTS, javaType, columnType, false);
    }

    /**
     * @param list whether the parameter is bound as an array
     * @return empty if compatible, else a description of the mismatch
     */
    public static Optional<String> checkParameter(Class<?> javaType, boolean list, String parameterType) {
        return check(PARAMETERS, javaType, parameterType, list);
    }

    private static Optional<String> check(Map<Class<?>, Set<String>> table, Class<?> javaType, String sqlType,
        boolean array) {
        Set<String> allowed = allowed(table, javaType);
        if (allowed == null) {
            return Optional.of("no database type is known to be compatible with " + javaType.getSimpleName());
        }
        String type = sqlType == null ? "" : sqlType.toLowerCase(Locale.ROOT);
        if (array != type.startsWith("_")) {
            return Optional.of(array
                ? "is a list, but the database expects a single " + type + " (write = ANY(:name))"
                : "is a single value, but the database expects an array " + type);
        }
        String element = array ? type.substring(1) : type;
        if (!allowed.contains(element)) {
            return Optional.of("database type " + type + " is not compatible with " + javaType.getSimpleName()
                + " (expected " + (array ? "an array of " : "") + String.join("/", allowed.stream().sorted().toList())
                + ")");
        }
        return Optional.empty();
    }

    private static Set<String> allowed(Map<Class<?>, Set<String>> table, Class<?> javaType) {
        Set<String> exact = table.get(javaType);
        if (exact != null) {
            return exact;
        }
        return table.entrySet().stream().filter(e -> e.getKey().isAssignableFrom(javaType)).map(Map.Entry::getValue)
            .findFirst().orElse(null);
    }
}
