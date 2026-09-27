package com.jabiz.runtime.file;

import com.jabiz.file.FileKind;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adds what an upload control needs to the exported {@code jabiz.file} fields (docs/design/14-files.md section 4):
 * {@code accept} (content types and extensions), {@code maxBytes}, {@code image} and the image {@code variants}.
 * The kind itself only knows the policy's name.
 */
@Component
public class FilePolicyExport {

    private final FilePolicyRegistry policies;

    public FilePolicyExport(FilePolicyRegistry policies) {
        this.policies = policies;
    }

    /** Completes the file fields of an exported entity ({@code fields}: the export's field list), in place. */
    @SuppressWarnings("unchecked")
    public void complete(Map<String, Object> exportedEntity) {
        Object fields = exportedEntity.get("fields");
        if (!(fields instanceof List<?> list)) {
            return;
        }
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> field && FileKind.KIND_ID.equals(field.get("kindId"))) {
                policies.find(String.valueOf(field.get(FileKind.POLICY)))
                    .ifPresent(policy -> describe((Map<String, Object>) field, policy));
            }
        }
    }

    static void describe(Map<String, Object> field, FilePolicy policy) {
        List<String> accept = new ArrayList<>(policy.contentTypes());
        for (MediaTypes type : policy.allowed()) {
            accept.addAll(type.extensions());
        }
        field.put("accept", List.copyOf(accept));
        field.put("maxBytes", policy.maxBytes());
        field.put("image", policy.acceptsImages());
        field.put("variants", policy.acceptsImages()
            ? policy.image().variants().stream().map(FilePolicy.ImageOptions::variantName).toList()
            : List.of());
    }
}
