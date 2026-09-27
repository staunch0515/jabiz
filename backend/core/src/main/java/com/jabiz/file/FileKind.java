package com.jabiz.file;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The semantic kind {@value #KIND_ID}: a field holding the {@code fileId} of an uploaded file of one policy
 * (docs/design/14-files.md section 4). Declared as {@code f.kind(FileKind.of("commerce.image"))}; one field refers
 * to one file, several files are rows of a child entity.
 */
public final class FileKind {

    public static final String KIND_ID = "jabiz.file";
    public static final String POLICY = "policy";

    private FileKind() {}

    /** The kind of a field referring to a file uploaded under {@code policy}. */
    public static SemanticKind of(String policy) {
        if (policy == null || !FilePolicy.NAME.matcher(policy).matches()) {
            throw new IllegalArgumentException("File policy name '" + policy + "' must match "
                + FilePolicy.NAME.pattern());
        }
        return new SemanticKind.Custom(KIND_ID, Map.of(POLICY, policy));
    }

    /** The policy of a file field; empty when the kind is not {@value #KIND_ID}. */
    public static Optional<String> policyOf(SemanticKind kind) {
        if (kind instanceof SemanticKind.Custom custom && KIND_ID.equals(custom.kindId())) {
            return Optional.of(String.valueOf(custom.params().get(POLICY)));
        }
        return Optional.empty();
    }

    /** The file fields of an entity, in declaration order. */
    public static List<FieldDefinition> fieldsOf(EntityDefinition def) {
        return def.fields.values().stream().filter(field -> policyOf(field.kind()).isPresent()).toList();
    }
}
