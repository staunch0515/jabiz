package com.jabiz.file;

import com.jabiz.entity.CustomKindSupport;
import com.jabiz.query.QueryOperator;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Behaviour of {@link FileKind}: values are {@code fileId}s, canonical {@link UUID}s stored in a {@code uuid} column.
 * Whether the file exists and belongs to the field's policy needs the database; the runtime checks it on write
 * (docs/design/14-files.md section 4). The export names the policy only: this class has no access to the registered
 * policies, the runtime adds their details to the exported metadata.
 */
public final class FileKindSupport implements CustomKindSupport {

    /** The textual form accepted from clients: the canonical 8-4-4-4-12 hexadecimal form, either case. */
    private static final Pattern UUID_TEXT =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    public String kindId() {
        return FileKind.KIND_ID;
    }

    @Override
    public Object coerce(Map<String, Object> params, Object raw, boolean forInput) {
        if (raw instanceof UUID id) {
            return id;
        }
        if (raw instanceof CharSequence text && UUID_TEXT.matcher(text).matches()) {
            return UUID.fromString(text.toString());
        }
        throw new IllegalArgumentException("a file is referred to by its id (a UUID)");
    }

    @Override
    public Class<?> javaType(Map<String, Object> params) {
        return UUID.class;
    }

    @Override
    public Set<QueryOperator> allowedOperators(Map<String, Object> params) {
        return EnumSet.of(QueryOperator.EQ, QueryOperator.NE, QueryOperator.IN, QueryOperator.IS_NULL,
            QueryOperator.IS_NOT_NULL);
    }

    @Override
    public Map<String, Object> export(Map<String, Object> params) {
        return Map.of(FileKind.POLICY, String.valueOf(params.get(FileKind.POLICY)));
    }
}
