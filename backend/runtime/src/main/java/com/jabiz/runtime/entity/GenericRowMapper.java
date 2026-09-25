package com.jabiz.runtime.entity;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldValueCoercer;
import io.r2dbc.spi.Row;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic mapping from a database row to a map keyed by logical field name.
 *
 * The column of each logical field is read from the physicalColumn declared in the
 * EntityDefinition, so no per-entity row mapper is needed. Values are normalized to the
 * canonical type of the field's semantic kind (see {@link FieldValueCoercer}).
 *
 * Entities that have a dedicated strongly typed record mapped with Spring Data
 * annotations do not need this mapper; both approaches can coexist per entity.
 */
public final class GenericRowMapper {

    private GenericRowMapper() {}

    /**
     * Converts a row to a map of logical field name to value, in declaration order.
     * Fields whose column is not part of the result set are skipped, so callers may
     * select a subset of columns.
     */
    public static Map<String, Object> toMap(EntityDefinition def, Row row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (FieldDefinition field : def.fields.values()) {
            if (!row.getMetadata().contains(field.physicalColumn())) {
                continue;
            }
            Object raw = row.get(field.physicalColumn());
            result.put(field.name(), FieldValueCoercer.coerce(field, raw, false));
        }
        return result;
    }
}
