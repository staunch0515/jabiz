package com.jabiz.runtime.process;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.SemanticKind;
import com.jabiz.process.ActsOn;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.stereotype.Component;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Startup checks of processes declared as actions on an entity (docs/design/16-content-authoring.md section 3),
 * category {@value ProcessChecks#CATEGORY}: the entity exists; the input is a record with the named component, whose
 * type holds the entity's primary key; the condition's field exists and is a {@code Code} or {@code Bool}, and its
 * values are among the field's values.
 */
@Component
public class ActsOnChecks implements PlatformCheck {

    private final ProcessRegistry processes;
    private final EntityDefinitionRegistry entities;

    public ActsOnChecks(ProcessRegistry processes, EntityDefinitionRegistry entities) {
        this.processes = processes;
        this.entities = entities;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        for (ProcessDefinition<?, ?, ?> definition : processes.all()) {
            ActsOn actsOn = definition.actsOn();
            if (actsOn == null) {
                continue;
            }
            String location = definition.name() + " v" + definition.version();
            Optional<EntityDefinition> entity = entities.find(actsOn.entity());
            if (entity.isEmpty()) {
                problems.add(error(location, "acts on unknown entity " + actsOn.entity()));
                continue;
            }
            EntityDefinition def = entity.get();
            checkInput(definition.inputType(), actsOn, def).ifPresent(message -> problems.add(error(location, message)));
            if (actsOn.whenField() != null) {
                checkCondition(actsOn, def).ifPresent(message -> problems.add(error(location, message)));
            }
        }
        return problems;
    }

    private static Optional<String> checkInput(Class<?> inputType, ActsOn actsOn, EntityDefinition def) {
        if (!inputType.isRecord()) {
            return Optional.of("acts on " + def.name + " but its input " + inputType.getSimpleName()
                + " is not a record");
        }
        Optional<RecordComponent> component = Arrays.stream(inputType.getRecordComponents())
            .filter(c -> c.getName().equals(actsOn.input())).findFirst();
        if (component.isEmpty()) {
            return Optional.of("acts on " + def.name + " through input '" + actsOn.input()
                + "', which " + inputType.getSimpleName() + " does not have");
        }
        Class<?> type = component.get().getType();
        if (!holdsKey(type, def)) {
            return Optional.of("input '" + actsOn.input() + "' is a " + type.getSimpleName()
                + ", which cannot hold the primary key of " + def.name);
        }
        return Optional.empty();
    }

    /** Temporal entities are identified by UUIDs (a text works as well); others by their key's canonical type. */
    private static boolean holdsKey(Class<?> type, EntityDefinition def) {
        if (def.temporal) {
            return type == UUID.class || type == String.class;
        }
        return box(type) == FieldValueCoercer.javaType(def.field(def.primaryKey).kind());
    }

    private static Class<?> box(Class<?> type) {
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        return type;
    }

    private static Optional<String> checkCondition(ActsOn actsOn, EntityDefinition def) {
        FieldDefinition field = def.fields.get(actsOn.whenField());
        if (field == null) {
            return Optional.of("condition field '" + actsOn.whenField() + "' is not a field of " + def.name);
        }
        List<String> allowed;
        if (field.kind() instanceof SemanticKind.Code code) {
            allowed = code.allowedValues();
        } else if (field.kind() instanceof SemanticKind.Bool) {
            allowed = List.of("true", "false");
        } else {
            return Optional.of("condition field '" + field.name() + "' must be a Code or Bool field");
        }
        if (!allowed.isEmpty()) {
            List<String> unknown = actsOn.whenValues().stream().filter(v -> !allowed.contains(v)).toList();
            if (!unknown.isEmpty()) {
                return Optional.of("condition values " + unknown + " are not values of " + def.name + "."
                    + field.name() + " " + allowed);
            }
        }
        return Optional.empty();
    }

    private static CheckProblem error(String location, String message) {
        return CheckProblem.error(ProcessChecks.CATEGORY, location, message);
    }
}
