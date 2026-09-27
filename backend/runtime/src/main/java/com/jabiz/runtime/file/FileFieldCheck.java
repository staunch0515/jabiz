package com.jabiz.runtime.file;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.file.FileKind;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.entity.FieldWriteCheck;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Write check of {@code jabiz.file} fields (docs/design/14-files.md section 4): a changed value must be the id of an
 * existing file ({@code FILE_NOT_FOUND}) uploaded under the field's policy ({@code FILE_POLICY_MISMATCH}); both 400.
 * Values the write does not change are not checked again. The rows are read {@code FOR KEY SHARE}: a concurrent
 * deletion of the file (which locks the row before looking for references) waits for this write, then sees the new
 * reference; there is no foreign key to do this (history may point at deleted files).
 */
@Component
public class FileFieldCheck implements FieldWriteCheck {

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public FileFieldCheck(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> verify(EntityDefinition def, Map<String, Object> values, Collection<String> changedFields) {
        List<FieldDefinition> fields = new ArrayList<>();
        Set<UUID> ids = new LinkedHashSet<>();
        for (FieldDefinition field : FileKind.fieldsOf(def)) {
            if (changedFields.contains(field.name()) && values.get(field.name()) instanceof UUID id) {
                fields.add(field);
                ids.add(id);
            }
        }
        if (fields.isEmpty()) {
            return Mono.empty();
        }
        EntityDefinition files = FileEntities.SYS_FILE;
        String sql = "SELECT " + SqlIdentifiers.require(files.primaryKeyColumn()) + " AS file_id, "
            + SqlIdentifiers.require(files.physicalColumn(FileEntities.POLICY)) + " AS policy FROM "
            + SqlIdentifiers.require(files.physicalTable) + " WHERE "
            + SqlIdentifiers.require(files.primaryKeyColumn()) + " = ANY(:ids) FOR KEY SHARE";
        return storages.getEngine(poolRef)
            .select(sql, Map.of("ids", BoundValue.of(ids.toArray(UUID[]::new))))
            .collectMap(row -> (UUID) row.get("file_id"), row -> (String) row.get("policy"), HashMap::new)
            .flatMap(policies -> {
                List<Violation> violations = new ArrayList<>();
                for (FieldDefinition field : fields) {
                    UUID id = (UUID) values.get(field.name());
                    String expected = FileKind.policyOf(field.kind()).orElseThrow();
                    String actual = policies.get(id);
                    if (actual == null) {
                        violations.add(new Violation(field.name(), PlatformErrorCodes.FILE_NOT_FOUND,
                            "File " + id + " referenced by field '" + field.name() + "' does not exist",
                            Map.of("file", id.toString())));
                    } else if (!actual.equals(expected)) {
                        violations.add(new Violation(field.name(), PlatformErrorCodes.FILE_POLICY_MISMATCH,
                            "File " + id + " was uploaded under " + actual + ", field '" + field.name()
                                + "' takes " + expected,
                            Map.of("file", id.toString(), "policy", expected)));
                    }
                }
                return violations.isEmpty() ? Mono.<Void>empty()
                    : Mono.<Void>error(new ValidationException(violations));
            });
    }
}
