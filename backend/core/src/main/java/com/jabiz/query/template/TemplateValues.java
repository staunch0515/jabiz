package com.jabiz.query.template;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.custom.QueryParameter;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Conversion of SQL template parameter values into what is bound (docs/design/05-sql-template.md section 4): the
 * canonical type of the parameter's semantic kind, except that identifiers of temporal entities are UUIDs (their
 * columns are {@code uuid}); a list becomes one typed array (decision D7).
 */
public final class TemplateValues {

    private TemplateValues() {}

    /** Java type a single value of the parameter is bound as. */
    public static Class<?> bindingType(QueryParameter parameter, Function<String, Optional<EntityDefinition>> entities) {
        return identifiedEntity(parameter.kind(), parameter.likeEntity(), parameter.likeField(), entities)
            .filter(def -> def.temporal)
            .<Class<?>>map(def -> UUID.class)
            .orElseGet(() -> FieldValueCoercer.javaType(parameter.kind()));
    }

    /**
     * Converts a caller-supplied value: a scalar to its binding type, a collection (for a list parameter) to an array
     * of it. Codes are checked against their dictionary's fixed values.
     *
     * @throws IllegalArgumentException if the value does not fit the parameter
     */
    public static Object bindable(QueryParameter parameter, Object value,
        Function<String, Optional<EntityDefinition>> entities) {
        Optional<EntityDefinition> identified =
            identifiedEntity(parameter.kind(), parameter.likeEntity(), parameter.likeField(), entities);
        Class<?> type = bindingType(parameter, entities);
        if (parameter.list()) {
            if (!(value instanceof Collection<?> collection)) {
                throw new IllegalArgumentException("a list is expected");
            }
            List<Object> items = new ArrayList<>(collection.size());
            for (Object item : collection) {
                if (item == null) {
                    throw new IllegalArgumentException("list must not contain null");
                }
                items.add(single(parameter.kind(), item, identified, type));
            }
            return toArray(type, items);
        }
        if (value instanceof Collection<?>) {
            throw new IllegalArgumentException("a single value is expected, not a list");
        }
        return single(parameter.kind(), value, identified, type);
    }

    /** A typed array ({@code String[]}, {@code UUID[]}, {@code BigDecimal[]} ...) of the values. */
    public static Object toArray(Class<?> elementType, List<?> values) {
        Object array = Array.newInstance(elementType, values.size());
        for (int i = 0; i < values.size(); i++) {
            Array.set(array, i, elementType.cast(values.get(i)));
        }
        return array;
    }

    private static Object single(SemanticKind kind, Object value, Optional<EntityDefinition> identified,
        Class<?> type) {
        Object coerced = FieldValueCoercer.coerce(kind, value, true);
        if (identified.isPresent()) {
            coerced = identified.get().normalizeId(coerced);
        }
        // Identifiers pass through unconverted; a number given for a text identifier is bound as its text.
        if (type == String.class && !(coerced instanceof String)) {
            return coerced.toString();
        }
        if (!type.isInstance(coerced)) {
            throw new IllegalArgumentException("expected a value of type " + type.getSimpleName());
        }
        return coerced;
    }

    /**
     * The entity whose instances the kind identifies: the target of a reference, or the entity of the primary key a
     * parameter is "like".
     */
    static Optional<EntityDefinition> identifiedEntity(SemanticKind kind, String likeEntity, String likeField,
        Function<String, Optional<EntityDefinition>> entities) {
        if (kind instanceof SemanticKind.Reference reference) {
            return entities.apply(reference.targetEntity());
        }
        if (kind instanceof SemanticKind.SemanticIdentity && likeEntity != null) {
            return entities.apply(likeEntity).filter(def -> def.primaryKey.equals(likeField));
        }
        return Optional.empty();
    }
}
