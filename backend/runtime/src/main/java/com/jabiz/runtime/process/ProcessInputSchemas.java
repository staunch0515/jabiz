package com.jabiz.runtime.process;

import com.jabiz.entity.RuleKinds;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JSON Schema (draft 2020-12) of a process input type, from which clients generate the process form
 * (docs/design/12-frontend.md, decision D15). Records become objects with their components in declaration order;
 * Bean Validation constraints become the matching keywords ({@code @NotNull}/{@code @NotBlank}/{@code @NotEmpty}
 * make a property required); {@link Sensitive} components are {@code writeOnly} with {@code format: password}.
 *
 * <p>The schema only helps the client: the process API converts and validates the body on its own
 * ({@link ProcessInputs}). A type the schema cannot describe becomes an unconstrained schema and is reported as a
 * problem, so the client falls back to raw JSON for that property.
 */
public final class ProcessInputSchemas {

    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    /** The schema and the types it could not describe (as {@code path: type}). */
    public record Result(Map<String, Object> schema, List<String> problems) {}

    private ProcessInputSchemas() {}

    public static Result of(Class<?> inputType) {
        List<String> problems = new ArrayList<>();
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", DIALECT);
        schema.putAll(schema(inputType, "$", problems, new HashSet<>()));
        return new Result(schema, List.copyOf(problems));
    }

    private static Map<String, Object> schema(Type type, String path, List<String> problems, Set<Class<?>> open) {
        Class<?> raw = rawClass(type);
        Map<String, Object> json = new LinkedHashMap<>();
        if (raw == null) {
            problems.add(path + ": " + type.getTypeName());
            return json;
        }
        if (raw == String.class || raw == Character.class || raw == char.class) {
            json.put("type", "string");
        } else if (raw == UUID.class) {
            json.put("type", "string");
            json.put("format", "uuid");
        } else if (raw == Instant.class || raw == OffsetDateTime.class) {
            json.put("type", "string");
            json.put("format", "date-time");
        } else if (raw == LocalDate.class) {
            json.put("type", "string");
            json.put("format", "date");
        } else if (raw == BigDecimal.class) {
            // Sent as text so no digit is lost on the way (the process API accepts numbers as well).
            json.put("type", List.of("string", "number"));
            json.put("format", "decimal");
        } else if (raw == Long.class || raw == long.class || raw == Integer.class || raw == int.class
                   || raw == Short.class || raw == short.class || raw == BigInteger.class) {
            json.put("type", "integer");
        } else if (raw == Double.class || raw == double.class || raw == Float.class || raw == float.class) {
            json.put("type", "number");
        } else if (raw == Boolean.class || raw == boolean.class) {
            json.put("type", "boolean");
        } else if (raw.isEnum()) {
            json.put("type", "string");
            json.put("enum", java.util.Arrays.stream(raw.getEnumConstants()).map(c -> ((Enum<?>) c).name()).toList());
        } else if (Collection.class.isAssignableFrom(raw) || raw.isArray()) {
            json.put("type", "array");
            Type item = raw.isArray() ? raw.getComponentType() : typeArgument(type, 0);
            json.put("items", item == null ? Map.of() : schema(item, path + "[]", problems, open));
        } else if (Map.class.isAssignableFrom(raw)) {
            json.put("type", "object");
        } else if (raw == Object.class) {
            // Any JSON value (for example a parameter value whose type is decided at run time).
            return json;
        } else if (raw.isRecord()) {
            if (!open.add(raw)) {
                problems.add(path + ": recursive type " + raw.getName());
                return json;
            }
            json.put("type", "object");
            json.put("title", raw.getSimpleName());
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (RecordComponent component : raw.getRecordComponents()) {
                Map<String, Object> property = schema(component.getGenericType(), path + "." + component.getName(),
                    problems, open);
                if (constrain(property, component, field(raw, component))) {
                    required.add(component.getName());
                }
                properties.put(component.getName(), property);
            }
            open.remove(raw);
            json.put("properties", properties);
            if (!required.isEmpty()) {
                json.put("required", required);
            }
            json.put("additionalProperties", false);
        } else {
            problems.add(path + ": " + raw.getName());
        }
        return json;
    }

    /** Adds the constraint keywords of one component; returns whether it is required. */
    private static boolean constrain(Map<String, Object> property, RecordComponent component, Field field) {
        boolean required = false;
        boolean text = "string".equals(property.get("type"));
        boolean array = "array".equals(property.get("type"));
        if (has(component, field, Sensitive.class)) {
            property.put("writeOnly", true);
            property.put("format", "password");
        }
        if (has(component, field, NotNull.class)) {
            required = true;
        }
        if (has(component, field, NotBlank.class)) {
            required = true;
            property.put("minLength", 1);
            property.put("pattern", "\\S");
        }
        if (has(component, field, NotEmpty.class)) {
            required = true;
            property.put(array ? "minItems" : text ? "minLength" : "minProperties", 1);
        }
        Size size = get(component, field, Size.class);
        if (size != null) {
            if (size.min() > 0) {
                property.put(array ? "minItems" : "minLength", size.min());
            }
            if (size.max() < Integer.MAX_VALUE) {
                property.put(array ? "maxItems" : "maxLength", size.max());
            }
        }
        Pattern pattern = get(component, field, Pattern.class);
        if (pattern != null && portable(pattern.regexp())) {
            // Bean Validation matches the whole text; JSON Schema patterns match anywhere.
            property.put("pattern", "^(?:" + pattern.regexp() + ")$");
        }
        Min min = get(component, field, Min.class);
        if (min != null) {
            property.put("minimum", min.value());
        }
        Max max = get(component, field, Max.class);
        if (max != null) {
            property.put("maximum", max.value());
        }
        if (has(component, field, Positive.class)) {
            property.put("exclusiveMinimum", 0);
        }
        if (has(component, field, PositiveOrZero.class)) {
            property.put("minimum", 0);
        }
        if (has(component, field, Email.class)) {
            property.put("format", "email");
        }
        return required;
    }

    private static boolean portable(String regex) {
        try {
            RuleKinds.checkPortablePattern("pattern", regex);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Constraint annotations cannot target record components, so Java puts them on the generated field (and
     * accessor); {@link Sensitive} may sit on the component itself.
     */
    private static <A extends java.lang.annotation.Annotation> A get(RecordComponent component, Field field,
        Class<A> annotation) {
        A found = component.getAnnotation(annotation);
        if (found == null && field != null) {
            found = field.getAnnotation(annotation);
        }
        if (found == null) {
            found = component.getAccessor().getAnnotation(annotation);
        }
        return found;
    }

    private static boolean has(RecordComponent component, Field field,
        Class<? extends java.lang.annotation.Annotation> annotation) {
        return get(component, field, annotation) != null;
    }

    private static Field field(Class<?> record, RecordComponent component) {
        try {
            return record.getDeclaredField(component.getName());
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            return p.getActualTypeArguments()[index];
        }
        return null;
    }
}
