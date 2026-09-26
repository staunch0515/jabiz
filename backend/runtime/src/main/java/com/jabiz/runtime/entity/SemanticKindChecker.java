package com.jabiz.runtime.entity;

import com.jabiz.entity.CustomKinds;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup self-check (docs/design/07-quality.md section 1): every {@link SemanticKind.Custom} field has a
 * registered {@link com.jabiz.entity.CustomKindSupport}. Without it values could be neither converted nor
 * queried, so the application refuses to start rather than failing on the first request.
 */
@Component
public class SemanticKindChecker implements PlatformCheck {

    private final EntityDefinitionRegistry entities;

    public SemanticKindChecker(EntityDefinitionRegistry entities) {
        this.entities = entities;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        for (EntityDefinition entity : entities.all()) {
            for (FieldDefinition field : entity.fields.values()) {
                if (field.kind() instanceof SemanticKind.Custom custom && CustomKinds.find(custom.kindId()).isEmpty()) {
                    problems.add(CheckProblem.error("SEMANTIC_KIND", entity.name + "." + field.name(),
                        "uses custom kind " + custom.kindId() + ", which has no registered CustomKindSupport"));
                }
            }
        }
        return problems;
    }
}
