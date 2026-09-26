package com.jabiz.runtime.security;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.security.Sensitive;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps secrets out of what the platform records, logs and returns (docs/design/10-security.md section 6).
 *
 * <p>A value is masked by the name of the property holding it, at any depth of its JSON form. The names are those of
 * the {@linkplain com.jabiz.entity.FieldBuilder#sensitive() sensitive entity fields}, of the process input and output
 * components marked {@link Sensitive}, and every name containing one of the configured fragments (by default
 * {@code password}, {@code secret}, {@code token}, {@code credential}), which also covers maps of attributes such as
 * the input of the generic entity processes.
 */
public class SensitiveDataMasker {

    /** Replacement of masked values in records meant for people ({@code input_summary}). */
    public static final String MASK = "***";

    /** Longest {@code input_summary} kept; longer inputs are recorded by their size only. */
    public static final int MAX_SUMMARY_LENGTH = 16 * 1024;

    private static final int MAX_TYPE_DEPTH = 8;

    private final JsonMapper json;
    private final EntityDefinitionRegistry entities;
    private final Set<String> names;
    private final List<String> fragments;

    public SensitiveDataMasker(JsonMapper json, EntityDefinitionRegistry entities,
        Collection<ProcessDefinition<?, ?, ?>> processes, Collection<String> fragments) {
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.entities = Objects.requireNonNull(entities, "entities must not be null");
        Set<String> found = new HashSet<>();
        for (EntityDefinition def : entities.all()) {
            def.sensitiveFields().forEach(name -> found.add(name.toLowerCase(Locale.ROOT)));
        }
        for (ProcessDefinition<?, ?, ?> process : processes) {
            collectSensitive(process.inputType(), found, 0);
            collectSensitive(process.outputType(), found, 0);
        }
        this.names = Set.copyOf(found);
        this.fragments = fragments.stream().filter(f -> f != null && !f.isBlank())
            .map(f -> f.trim().toLowerCase(Locale.ROOT)).distinct().toList();
    }

    /** Whether values held under this property name are masked. */
    public boolean isSensitive(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return names.contains(lower) || fragments.stream().anyMatch(lower::contains);
    }

    /**
     * The value as JSON for {@code op_process.input_summary}: secrets replaced by {@value #MASK}; an input longer than
     * {@value #MAX_SUMMARY_LENGTH} characters is recorded by its length only. Null for a null value.
     */
    public String summary(Object value) {
        if (value == null) {
            return null;
        }
        String text = json.writeValueAsString(mask(json.valueToTree(value), json.getNodeFactory().stringNode(MASK)));
        if (text.length() <= MAX_SUMMARY_LENGTH) {
            return text;
        }
        Map<String, Object> truncated = new LinkedHashMap<>();
        truncated.put("truncated", true);
        truncated.put("length", text.length());
        return json.writeValueAsString(truncated);
    }

    /**
     * The value as JSON with secrets replaced by null: outputs kept for idempotent replay ({@code op_process_result})
     * still convert back to their type.
     */
    public String withoutSecrets(Object value) {
        return json.writeValueAsString(mask(json.valueToTree(value), json.getNodeFactory().nullNode()));
    }

    /** The value converted to JSON types with secrets removed (null), for responses of generic endpoints. */
    public Object toJsonWithoutSecrets(Object value) {
        if (value == null) {
            return null;
        }
        return json.treeToValue(mask(json.valueToTree(value), json.getNodeFactory().nullNode()), Object.class);
    }

    /** The instance without the values of its entity's sensitive fields: what read APIs return. */
    public EntityInstance hide(EntityInstance instance) {
        if (instance == null) {
            return null;
        }
        List<String> hidden = entities.find(instance.entityType()).map(EntityDefinition::sensitiveFields)
            .orElse(List.of());
        if (hidden.isEmpty() || hidden.stream().noneMatch(instance.attributes()::containsKey)) {
            return instance;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(instance.attributes());
        hidden.forEach(attributes::remove);
        return new EntityInstance(instance.id(), instance.entityType(), instance.version(), instance.state(),
            attributes);
    }

    /** The attributes without the sensitive fields of the entity. */
    public Map<String, Object> hide(EntityDefinition def, Map<String, Object> attributes) {
        List<String> hidden = def.sensitiveFields();
        if (attributes == null || hidden.isEmpty()) {
            return attributes;
        }
        Map<String, Object> visible = new LinkedHashMap<>(attributes);
        hidden.forEach(visible::remove);
        return visible;
    }

    /**
     * Sensitive fields are set by processes only: the dataset API and the generic entity processes refuse them
     * (400 {@code SENSITIVE_FIELD}).
     */
    public static void rejectWrites(EntityDefinition def, Map<String, Object> attributes) {
        List<Violation> violations = def.sensitiveFields().stream().filter(attributes::containsKey)
            .map(field -> new Violation(field, PlatformErrorCodes.SENSITIVE_FIELD,
                "Field " + field + " of " + def.name + " cannot be written through this API"))
            .toList();
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    private JsonNode mask(JsonNode node, JsonNode replacement) {
        if (node instanceof ObjectNode object) {
            List<String> keys = new ArrayList<>();
            object.properties().forEach(entry -> keys.add(entry.getKey()));
            for (String key : keys) {
                JsonNode child = object.get(key);
                object.set(key, isSensitive(key) && !child.isNull() ? replacement : mask(child, replacement));
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, mask(array.get(i), replacement));
            }
        }
        return node;
    }

    private static void collectSensitive(Type type, Set<String> found, int depth) {
        if (depth > MAX_TYPE_DEPTH) {
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                collectSensitive(argument, found, depth + 1);
            }
            collectSensitive(parameterized.getRawType(), found, depth + 1);
        } else if (type instanceof Class<?> cls && cls.isRecord()) {
            for (RecordComponent component : cls.getRecordComponents()) {
                if (component.isAnnotationPresent(Sensitive.class)) {
                    found.add(component.getName().toLowerCase(Locale.ROOT));
                }
                collectSensitive(component.getGenericType(), found, depth + 1);
            }
        }
    }
}
