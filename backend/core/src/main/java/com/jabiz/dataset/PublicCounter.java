package com.jabiz.dataset;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A counter of what anonymous visitors do on public pages, by day (docs/design/15-public-access.md section 10;
 * decision D65), declared by the application as a bean: its name, the public dataset and whitelisted field its keys
 * come from (a key counts only while a row visible in that dataset has it) and the zone its days are told in.
 *
 * <pre>{@code
 * PublicCounter.define("commerce.product-view", c -> c
 *     .keys(CommerceEntities.PUBLIC_PRODUCT_DATASET, "productId")
 *     .zone("Asia/Tokyo"))
 * }</pre>
 *
 * @param name    the name in the path {@code /api/public/counters/{name}/{key}}
 * @param dataset the public dataset whose visible rows give the valid keys
 * @param field   the whitelisted field of that dataset holding the keys
 * @param zone    the zone in which a count's day is told
 */
public record PublicCounter(String name, String dataset, String field, ZoneId zone) {

    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9.-]{0,59}");

    /** Longest key (the column {@code counter_key}). */
    public static final int MAX_KEY_LENGTH = 100;

    public PublicCounter {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Public counter name '" + name + "' must match " + NAME.pattern());
        }
        Objects.requireNonNull(dataset, "Public counter " + name + " needs the public dataset of its keys");
        Objects.requireNonNull(field, "Public counter " + name + " needs the field of its keys");
        zone = zone == null ? ZoneOffset.UTC : zone;
    }

    public static PublicCounter define(String name, Consumer<Builder> spec) {
        Builder builder = new Builder();
        spec.accept(builder);
        return new PublicCounter(name, builder.dataset, builder.field, builder.zone);
    }

    /**
     * What is wrong with the counter's keys, given its dataset and the dataset's entity (null when not declared);
     * empty when nothing is.
     */
    public List<String> problems(DatasetDefinition definition, EntityDefinition entity) {
        List<String> problems = new ArrayList<>();
        if (definition == null) {
            problems.add("dataset " + dataset + " is not declared");
            return problems;
        }
        if (!definition.isPublic()) {
            problems.add("dataset " + dataset + " is not public (publicRead)");
            return problems;
        }
        if (!definition.publicRead().allows(field)) {
            problems.add("field " + field + " is not whitelisted in publicRead of " + dataset);
        }
        FieldDefinition declared = entity == null ? null : entity.fields.get(field);
        if (declared == null) {
            problems.add("field " + field + " is not a field of " + definition.targetEntityType());
        } else if (!(declared.kind() instanceof SemanticKind.Text || declared.kind() instanceof SemanticKind.Code
            || ids(declared)) || declared.sensitive()) {
            problems.add("field " + field + " is not a text, code, identity or reference field");
        }
        return problems;
    }

    /** Whether the field holds ids (UUIDs): an identity or a reference field. */
    public static boolean ids(FieldDefinition field) {
        return field.kind() instanceof SemanticKind.SemanticIdentity
            || field.kind() instanceof SemanticKind.Reference;
    }

    /**
     * The key as it is looked up and stored, or null when it cannot be one: blank, longer than
     * {@value #MAX_KEY_LENGTH} characters, or not a UUID written in full when the field holds ids (then in canonical
     * lower case, so that one id is always one key).
     */
    public static String normalizeKey(String key, boolean ids) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            return null;
        }
        if (!ids) {
            return key;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        try {
            // UUID.fromString also takes shortened groups (1-2-3-4-5), a different text than the id they parse to.
            return UUID.fromString(lower).toString().equals(lower) ? lower : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static final class Builder {
        private String dataset;
        private String field;
        private ZoneId zone;

        /** The public dataset and its whitelisted field whose values are the valid keys. */
        public Builder keys(String datasetId, String fieldName) {
            this.dataset = datasetId;
            this.field = fieldName;
            return this;
        }

        /** The zone in which days are told (default UTC). */
        public Builder zone(String zoneId) {
            try {
                this.zone = ZoneId.of(zoneId);
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("Public counter zone '" + zoneId + "' is not a zone", e);
            }
            return this;
        }
    }
}
