package com.jabiz.runtime.i18n;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldRule;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup self-check (docs/design/07-quality.md section 1): every platform error code and every rule
 * code of a registered entity has a text in every supported language. All gaps are reported at once.
 */
@Component
public class MessageCatalogChecker implements SmartInitializingSingleton {

    private final MessageCatalog messages;
    private final EntityDefinitionRegistry entities;

    public MessageCatalogChecker(MessageCatalog messages, EntityDefinitionRegistry entities) {
        this.messages = messages;
        this.entities = entities;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> missing = messages.missing(requiredCodes());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing error messages (" + missing.size() + "):\n  "
                + String.join("\n  ", missing));
        }
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
