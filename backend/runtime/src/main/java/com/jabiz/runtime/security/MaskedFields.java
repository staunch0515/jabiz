package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.MaskStyle;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks of {@linkplain com.jabiz.entity.FieldBuilder#masked masked fields} at the entry points
 * (docs/design/10-security.md section 13.1, decision D28 item 7): only holders of a field's permission write it,
 * filter or sort by it, and nobody writes a masked form back.
 */
public final class MaskedFields {

    private MaskedFields() {}

    /** The masked fields of the entity the caller may read in plain text. */
    public static Set<String> plainFor(RequestContext context, EntityDefinition def) {
        return def.maskedFields().stream().filter(field -> context.hasPermission(field.masked().permission()))
            .map(FieldDefinition::name).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Writing a masked field needs its permission (403); a value in the masked form is refused (400
     * {@code MASKED_VALUE}), so a form sent back unchanged never replaces the plain value.
     */
    public static void checkWrites(RequestContext context, EntityDefinition def, Map<String, Object> attributes) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDefinition field : def.maskedFields()) {
            if (!attributes.containsKey(field.name())) {
                continue;
            }
            Permissions.require(context, field.masked().permission(),
                "Writing " + def.name + "." + field.name());
            if (MaskStyle.looksMasked(attributes.get(field.name()))) {
                violations.add(new Violation(field.name(), PlatformErrorCodes.MASKED_VALUE,
                    "Field " + field.name() + " of " + def.name + " was given its masked form"));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    /**
     * Whether the caller may filter or sort by the field: always for unmasked fields; for a masked one only with its
     * permission, since the order or a match would give the value away.
     */
    public static boolean mayCompare(RequestContext context, EntityDefinition def, String field) {
        FieldDefinition definition = def.fields.get(field);
        return definition == null || !definition.isMasked()
            || context.hasPermission(definition.masked().permission());
    }
}
