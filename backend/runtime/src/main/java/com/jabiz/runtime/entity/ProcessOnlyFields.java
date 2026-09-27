package com.jabiz.runtime.entity;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.util.List;
import java.util.Map;

/**
 * Fields only processes change ({@link com.jabiz.entity.FieldBuilder#processOnly()},
 * docs/design/16-content-authoring.md section 5).
 */
public final class ProcessOnlyFields {

    private ProcessOnlyFields() {}

    /**
     * The dataset API and the generic entity processes refuse process-only fields, even set to null
     * (400 {@code PROCESS_ONLY_FIELD}); processes write them through their change sets.
     */
    public static void rejectWrites(EntityDefinition def, Map<String, Object> attributes) {
        List<Violation> violations = def.processOnlyFields().stream().filter(attributes::containsKey)
            .map(field -> new Violation(field, PlatformErrorCodes.PROCESS_ONLY_FIELD,
                "Field " + field + " of " + def.name + " is changed by processes only"))
            .toList();
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }
}
