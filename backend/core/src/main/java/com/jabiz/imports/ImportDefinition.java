package com.jabiz.imports;

import com.jabiz.entity.SemanticKind;
import com.jabiz.file.FilePolicy;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * An import (docs/design/20-imports.md, decision D26): which files it reads and how, the fields of a row, and the
 * process each row - or each group of rows - is handed to. Declared as a bean. The whole file is checked before
 * anything is written, every row is processed in one transaction, and a single failing row rejects the file.
 *
 * <pre>{@code
 * ImportDefinition.define("commerce.products", 1)
 *     .file("commerce.import", ImportFormat.csv())
 *     .field("sku", SemanticKinds.text(40), true, "SKU")
 *     .field("price", new SemanticKind.Monetary("USD", 2), true, "Price", "Unit price")
 *     .externalRef(row -> row.text("sku"), OnDuplicate.SKIP)
 *     .perRow("PRODUCT_CREATE", 1, (row, params) -> new CreateInput(row.text("sku"), row.decimal("price")))
 *     .permissions("commerce.product.import")
 *     .build();
 * }</pre>
 *
 * @param <P> the parameters given once per import (a record, its form generated from its JSON Schema);
 *            {@link NoParams} for none
 */
public final class ImportDefinition<P> {

    /** Import ids: dot-separated lower-case segments, like file policy names. */
    public static final java.util.regex.Pattern ID = FilePolicy.NAME;

    /** Parameters of an import that takes none. */
    public record NoParams() {}

    /** What a row whose external reference was already imported, or appears earlier in the file, does. */
    public enum OnDuplicate {
        /** It is left out and listed in the report (a bank statement line is stored once, however often sent). */
        SKIP,
        /** It is an error, and so the file is rejected. */
        REJECT
    }

    /** What rows are handed to. */
    public sealed interface Target<P> permits PerRow, PerGroup {
        String process();

        int version();
    }

    /** One call of the process per row. */
    public record PerRow<P>(String process, int version, BiFunction<ImportRow, P, ?> input) implements Target<P> {}

    /**
     * One call of the process per group of rows with the same key (the lines of one journal entry), in the order
     * the groups first appear.
     */
    public record PerGroup<P>(Function<ImportRow, String> key, String process, int version,
        BiFunction<List<ImportRow>, P, ?> input) implements Target<P> {}

    /** The rows and the values outside them, for checks of the whole file. */
    public record FileContent<P>(List<ImportRow> rows, Map<String, String> header, P params) {}

    /** Where a check of the whole file reports what it finds. */
    public interface Issues {
        void file(String code, String message, Map<String, Object> params);

        void row(ImportRow row, String field, String code, String message, Map<String, Object> params);
    }

    /**
     * A check of the whole file (opening balance plus lines equals closing balance; debits equal credits). Runs
     * only when every row could be read, so it sees all of them.
     */
    @FunctionalInterface
    public interface FileCheck<P> {
        void check(FileContent<P> content, Issues issues);
    }

    private final String id;
    private final int version;
    private final String filePolicy;
    private final ImportFormat format;
    private final Map<String, ImportField> fields;
    private final Class<P> paramsType;
    private final Function<ImportRow, String> externalRef;
    private final OnDuplicate onDuplicate;
    private final Target<P> target;
    private final List<FileCheck<P>> fileChecks;
    private final List<String> totals;
    private final String permission;
    private final String mappingPermission;
    private final ZoneId zone;
    private final List<String> datePatterns;
    private final List<DateTimeFormatter> dateFormatters;
    private final Integer maxRows;

    private ImportDefinition(Builder<P> b) {
        if (b.id == null || b.id.length() > FilePolicy.MAX_NAME_LENGTH || !ID.matcher(b.id).matches()) {
            throw new IllegalArgumentException("Import id '" + b.id + "' must match " + ID.pattern());
        }
        this.id = b.id;
        if (b.version < 1) {
            throw new IllegalArgumentException("Import " + id + ": version must be positive");
        }
        this.version = b.version;
        this.filePolicy = require(b.filePolicy, "a file policy (file(...))");
        this.format = Objects.requireNonNull(b.format, "Import " + id + ": format must not be null");
        if (b.fields.isEmpty()) {
            throw new IllegalArgumentException("Import " + id + " declares no field");
        }
        this.fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(b.fields));
        this.paramsType = Objects.requireNonNull(b.paramsType, "paramsType must not be null");
        this.externalRef = b.externalRef;
        this.onDuplicate = b.onDuplicate;
        this.target = Objects.requireNonNull(b.target, "Import " + id + " hands its rows to no process "
            + "(perRow(...) or perGroup(...))");
        this.fileChecks = List.copyOf(b.fileChecks);
        for (String total : b.totals) {
            ImportField field = fields.get(total);
            if (field == null || !(field.kind() instanceof SemanticKind.Monetary
                || field.kind() instanceof SemanticKind.Numeric)) {
                throw new IllegalArgumentException("Import " + id + ": total '" + total
                    + "' is not a monetary or numeric field");
            }
        }
        this.totals = List.copyOf(b.totals);
        this.permission = require(b.permission, "a permission (permissions(...)), default deny");
        this.mappingPermission = b.mappingPermission == null ? permission : b.mappingPermission;
        this.zone = b.zone;
        this.datePatterns = List.copyOf(b.datePatterns);
        List<DateTimeFormatter> formatters = new ArrayList<>();
        for (String pattern : datePatterns) {
            try {
                // Strict: 02/30/2024 is an error, not February 29th. Strict resolution needs the proleptic year.
                formatters.add(DateTimeFormatter.ofPattern(prolepticYears(pattern), java.util.Locale.ROOT)
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Import " + id + ": date pattern '" + pattern + "' is invalid", e);
            }
        }
        this.dateFormatters = List.copyOf(formatters);
        if (b.maxRows != null && b.maxRows < 1) {
            throw new IllegalArgumentException("Import " + id + ": maxRows must be positive");
        }
        this.maxRows = b.maxRows;
    }

    /** The pattern with year-of-era ({@code y}) outside quoted text as proleptic year ({@code u}). */
    static String prolepticYears(String pattern) {
        StringBuilder out = new StringBuilder(pattern.length());
        boolean quoted = false;
        for (char c : pattern.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            }
            out.append(!quoted && c == 'y' ? 'u' : c);
        }
        return out.toString();
    }

    private String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Import " + id + " declares no " + what);
        }
        return value;
    }

    public static Builder<NoParams> define(String id, int version) {
        return new Builder<>(id, version, NoParams.class);
    }

    public static <P> Builder<P> define(String id, int version, Class<P> params) {
        return new Builder<>(id, version, params);
    }

    public String id() {
        return id;
    }

    public int version() {
        return version;
    }

    public String filePolicy() {
        return filePolicy;
    }

    public ImportFormat format() {
        return format;
    }

    /** The fields, in declaration order. */
    public Collection<ImportField> fields() {
        return fields.values();
    }

    public ImportField field(String name) {
        return fields.get(name);
    }

    public Class<P> paramsType() {
        return paramsType;
    }

    public boolean hasParams() {
        return paramsType != NoParams.class;
    }

    /** The external reference of a row; null when the import declares none. */
    public Function<ImportRow, String> externalRef() {
        return externalRef;
    }

    public OnDuplicate onDuplicate() {
        return onDuplicate;
    }

    public Target<P> target() {
        return target;
    }

    public List<FileCheck<P>> fileChecks() {
        return fileChecks;
    }

    /** Monetary or numeric fields summed in the report (control totals). */
    public List<String> totals() {
        return totals;
    }

    /** Needed to import, besides the permissions of the process rows are handed to. */
    public String permission() {
        return permission;
    }

    /** Needed to save a mapping; the import permission unless declared. */
    public String mappingPermission() {
        return mappingPermission;
    }

    /** Where dates and date-times without an offset are. */
    public ZoneId zone() {
        return zone;
    }

    public List<String> datePatterns() {
        return datePatterns;
    }

    List<DateTimeFormatter> dateFormatters() {
        return dateFormatters;
    }

    /** At most this many records, when lower than the platform's limit; null for the platform's. */
    public Integer maxRows() {
        return maxRows;
    }

    @Override
    public String toString() {
        return "ImportDefinition[" + id + " v" + version + "]";
    }

    public static final class Builder<P> {
        private final String id;
        private final int version;
        private final Class<P> paramsType;
        private String filePolicy;
        private ImportFormat format;
        private final Map<String, ImportField> fields = new LinkedHashMap<>();
        private Function<ImportRow, String> externalRef;
        private OnDuplicate onDuplicate = OnDuplicate.REJECT;
        private Target<P> target;
        private final List<FileCheck<P>> fileChecks = new ArrayList<>();
        private final List<String> totals = new ArrayList<>();
        private String permission;
        private String mappingPermission;
        private ZoneId zone = ZoneOffset.UTC;
        private final List<String> datePatterns = new ArrayList<>();
        private Integer maxRows;

        private Builder(String id, int version, Class<P> paramsType) {
            this.id = id;
            this.version = version;
            this.paramsType = paramsType;
        }

        /** The file policy uploads for this import go under (import types only), and the file's layout. */
        public Builder<P> file(String policy, ImportFormat format) {
            this.filePolicy = policy;
            this.format = format;
            return this;
        }

        /**
         * A field.
         *
         * @param columns the column names it is found under by default, compared ignoring case
         */
        public Builder<P> field(String name, SemanticKind kind, boolean required, String... columns) {
            if (fields.containsKey(name)) {
                throw new IllegalArgumentException("Import " + id + ": field " + name + " is declared twice");
            }
            fields.put(name, new ImportField(name, kind, required, columns.length == 0 ? List.of(name)
                : List.of(columns)));
            return this;
        }

        /** Each row's reference in the system it comes from; the same reference is imported once. */
        public Builder<P> externalRef(Function<ImportRow, String> ref, OnDuplicate onDuplicate) {
            this.externalRef = Objects.requireNonNull(ref, "ref must not be null");
            this.onDuplicate = Objects.requireNonNull(onDuplicate, "onDuplicate must not be null");
            return this;
        }

        public Builder<P> perRow(String process, int version, BiFunction<ImportRow, P, ?> input) {
            this.target = new PerRow<>(process, version, Objects.requireNonNull(input, "input must not be null"));
            return this;
        }

        public Builder<P> perGroup(Function<ImportRow, String> key, String process, int version,
            BiFunction<List<ImportRow>, P, ?> input) {
            this.target = new PerGroup<>(Objects.requireNonNull(key, "key must not be null"), process, version,
                Objects.requireNonNull(input, "input must not be null"));
            return this;
        }

        public Builder<P> fileCheck(FileCheck<P> check) {
            fileChecks.add(Objects.requireNonNull(check, "check must not be null"));
            return this;
        }

        public Builder<P> totals(String... fieldNames) {
            totals.addAll(List.of(fieldNames));
            return this;
        }

        public Builder<P> permissions(String importPermission) {
            this.permission = importPermission;
            return this;
        }

        public Builder<P> permissions(String importPermission, String mappingPermission) {
            this.permission = importPermission;
            this.mappingPermission = mappingPermission;
            return this;
        }

        /** Where dates and date-times without an offset are; UTC by default. */
        public Builder<P> zone(ZoneId zone) {
            this.zone = Objects.requireNonNull(zone, "zone must not be null");
            return this;
        }

        /** Date patterns accepted besides ISO ({@code MM/dd/yyyy}); tried in order. */
        public Builder<P> datePatterns(String... patterns) {
            datePatterns.addAll(List.of(patterns));
            return this;
        }

        public Builder<P> maxRows(int rows) {
            this.maxRows = rows;
            return this;
        }

        public ImportDefinition<P> build() {
            return new ImportDefinition<>(this);
        }
    }
}
