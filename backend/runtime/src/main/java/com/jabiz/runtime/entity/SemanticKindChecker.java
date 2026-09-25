package com.jabiz.runtime.entity;

import com.jabiz.entity.CustomKinds;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup self-check (docs/design/07-quality.md section 1): every {@link SemanticKind.Custom} field has a
 * registered {@link com.jabiz.entity.CustomKindSupport}. Without it values could be neither converted nor
 * queried, so the application refuses to start rather than failing on the first request.
 */
@Component
public class SemanticKindChecker implements SmartInitializingSingleton {

    private final EntityDefinitionRegistry entities;

    public SemanticKindChecker(EntityDefinitionRegistry entities) {
        this.entities = entities;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = new ArrayList<>();
        for (EntityDefinition entity : entities.all()) {
            for (FieldDefinition field : entity.fields.values()) {
                if (field.kind() instanceof SemanticKind.Custom custom && CustomKinds.find(custom.kindId()).isEmpty()) {
                    problems.add("Entity " + entity.name + ": field " + field.name() + " uses custom kind "
                        + custom.kindId() + ", which has no registered CustomKindSupport");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsupported semantic kinds:\n - " + String.join("\n - ", problems));
        }
    }
}
