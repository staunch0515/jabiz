package com.jabiz.runtime.i18n;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldRule;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup self-check (docs/design/07-quality.md section 1): every platform error code and every rule
 * code of a registered entity has a text in every supported language. All gaps are reported at once.
 */
@Component
public class MessageCatalogChecker implements PlatformCheck {

    private final MessageCatalog messages;
    private final EntityDefinitionRegistry entities;

    public MessageCatalogChecker(MessageCatalog messages, EntityDefinitionRegistry entities) {
        this.messages = messages;
        this.entities = entities;
    }

    @Override
    public List<CheckProblem> check() {
        return messages.missing(requiredCodes()).stream()
            .map(missing -> CheckProblem.error("MESSAGES", missing, "no error message"))
            .toList();
    }

    List<String> requiredCodes() {
        List<String> codes = new ArrayList<>(PlatformErrorCodes.ALL);
        for (EntityDefinition entity : entities.all()) {
            for (FieldDefinition field : entity.fields.values()) {
                field.rules().stream().map(FieldRule::code).forEach(codes::add);
            }
        }
        return codes;
    }
}
