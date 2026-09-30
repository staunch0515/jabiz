package com.jabiz.imports;

import com.jabiz.i18n.PlatformErrorCodes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The part of an import that needs no database (docs/design/20-imports.md section 5): resolving the mapping against
 * the file's columns, reading every cell, and - given the references imported before - finding duplicates, running
 * the checks of the whole file, building the process inputs and summing the control totals. Everything wrong is
 * collected, never only the first problem.
 */
public final class ImportPreparation {

    private ImportPreparation() {}

    /**
     * The fields' sources: a column of the file, or a constant.
     *
     * @param columns   field to the file's column; absent when the field has none
     * @param constants field to its constant text
     */
    public record Sources(Map<String, String> columns, Map<String, String> constants) {}

    /** The file read as the import's rows, with what could not be read. */
    public record Converted(Sources sources, List<ImportRow> rows, List<ImportIssue> issues,
        Map<String, String> header, int recordCount) {

        /** The external references of the rows read, for looking up those imported before; empty without refs. */
        public Set<String> refs(ImportDefinition<?> definition) {
            Set<String> refs = new LinkedHashSet<>();
            if (definition.externalRef() != null) {
                for (ImportRow row : rows) {
                    String ref = ref(definition, row);
                    if (ref != null) {
                        refs.add(ref);
                    }
                }
            }
            return refs;
        }
    }

    /** One call of the target process: the rows it is made of and its input. */
    public record Unit(String key, List<ImportRow> rows, Object input) {
        public Unit {
            rows = List.copyOf(rows);
        }

        /** The first row's number, where a failure of the unit is reported. */
        public int firstRow() {
            return rows.getFirst().number();
        }
    }

    /**
     * What to do: the units to process (only those whose rows are all sound), the rows left out as duplicates, the
     * issues found so far, and the control totals over the rows that will be imported.
     */
    public record Plan(Sources sources, List<Unit> units, List<ImportRow> duplicates, List<ImportIssue> issues,
        Map<String, BigDecimal> totals, int recordCount, int rowCount) {

        public boolean hasIssues() {
            return !issues.isEmpty();
        }
    }

    /** Resolves the mapping and reads every record. */
    public static Converted convert(ImportDefinition<?> definition, ParsedFile file, ImportMapping mapping) {
        Objects.requireNonNull(mapping, "mapping must not be null");
        List<ImportIssue> issues = new ArrayList<>();
        Sources sources = sources(definition, file.columns(), mapping, issues);
        List<ImportRow> rows = new ArrayList<>();
        if (issues.isEmpty()) {
            for (RawRecord record : file.records()) {
                ImportRow row = convert(definition, sources, record, issues);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        if (issues.isEmpty() && file.records().isEmpty()) {
            issues.add(ImportIssue.ofFile(ImportCodes.EMPTY, "The file has no rows", Map.of()));
        }
        return new Converted(sources, rows, issues, file.header(), file.records().size());
    }

    private static Sources sources(ImportDefinition<?> definition, List<String> fileColumns, ImportMapping mapping,
        List<ImportIssue> issues) {
        Map<String, String> byLowerCase = new HashMap<>();
        for (String column : fileColumns) {
            byLowerCase.putIfAbsent(column.toLowerCase(Locale.ROOT), column);
        }
        for (String field : mapping.columns().keySet()) {
            if (definition.field(field) == null) {
                issues.add(ImportIssue.ofFile(ImportCodes.UNKNOWN_FIELD, "The import has no field " + field,
                    Map.of("field", field)));
            }
        }
        for (String field : mapping.constants().keySet()) {
            if (definition.field(field) == null) {
                issues.add(ImportIssue.ofFile(ImportCodes.UNKNOWN_FIELD, "The import has no field " + field,
                    Map.of("field", field)));
            }
        }
        Map<String, String> columns = new LinkedHashMap<>();
        Map<String, String> constants = new LinkedHashMap<>();
        for (ImportField field : definition.fields()) {
            String constant = mapping.constants().get(field.name());
            String chosen = mapping.columns().get(field.name());
            if (constant != null && !constant.isBlank()) {
                constants.put(field.name(), constant);
            } else if (chosen != null && !chosen.isBlank()) {
                String column = byLowerCase.get(chosen.toLowerCase(Locale.ROOT));
                if (column == null) {
                    issues.add(ImportIssue.ofFile(ImportCodes.UNKNOWN_COLUMN, "The file has no column " + chosen,
                        Map.of("column", chosen)));
                } else {
                    columns.put(field.name(), column);
                }
            } else {
                field.columns().stream().map(name -> byLowerCase.get(name.toLowerCase(Locale.ROOT)))
                    .filter(Objects::nonNull).findFirst().ifPresent(column -> columns.put(field.name(), column));
            }
            if (field.required() && !columns.containsKey(field.name()) && !constants.containsKey(field.name())
                && (chosen == null || chosen.isBlank())) {
                issues.add(new ImportIssue(0, null, field.name(), null, ImportCodes.COLUMN_MISSING,
                    "No column of the file gives field " + field.name(), Map.of("field", field.name())));
            }
        }
        return new Sources(columns, constants);
    }

    private static ImportRow convert(ImportDefinition<?> definition, Sources sources, RawRecord record,
        List<ImportIssue> issues) {
        boolean sound = true;
        if (record.problem() != null) {
            issues.add(new ImportIssue(record.number(), record.location(), null, null, ImportCodes.EXTRA_CELLS,
                record.problem(), Map.of()));
            sound = false;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> formulas = new HashSet<>();
        for (ImportField field : definition.fields()) {
            String column = sources.columns().get(field.name());
            String text = sources.constants().containsKey(field.name()) ? sources.constants().get(field.name())
                : column == null ? null : record.cells().get(column);
            if (column != null && record.formulas().contains(column)) {
                formulas.add(field.name());
            }
            try {
                Object value = ImportValues.read(field.kind(), text, definition.zone(), definition.dateFormatters());
                if (value == null && field.required()) {
                    issues.add(new ImportIssue(record.number(), record.location(), field.name(), column,
                        PlatformErrorCodes.REQUIRED, "Field " + field.name() + " has no value", Map.of()));
                    sound = false;
                }
                values.put(field.name(), value);
            } catch (ImportValues.Invalid e) {
                issues.add(new ImportIssue(record.number(), record.location(), field.name(), column, e.code(),
                    "Field " + field.name() + ": " + e.getMessage(), e.params()));
                sound = false;
            }
        }
        return sound ? new ImportRow(record.number(), record.location(), values, formulas) : null;
    }

    /**
     * Finishes the preparation.
     *
     * @param known external references imported before (of those {@link Converted#refs} returned)
     */
    public static <P> Plan plan(ImportDefinition<P> definition, Converted converted, P params, Set<String> known) {
        List<ImportIssue> issues = new ArrayList<>(converted.issues());
        List<ImportRow> kept = new ArrayList<>();
        List<ImportRow> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ImportRow row : converted.rows()) {
            String ref = definition.externalRef() == null ? null : refOrIssue(definition, row, issues);
            if (ref != null && (known.contains(ref) || !seen.add(ref))) {
                if (definition.onDuplicate() == ImportDefinition.OnDuplicate.SKIP) {
                    duplicates.add(row);
                } else {
                    issues.add(new ImportIssue(row.number(), row.location(), null, null, ImportCodes.DUPLICATE_REF,
                        "Reference " + ref + (known.contains(ref) ? " was imported before" : " appears earlier in "
                            + "the file"), Map.of("ref", ref)));
                }
                continue;
            }
            kept.add(row);
        }
        boolean allRead = converted.issues().isEmpty();
        if (allRead) {
            ImportDefinition.FileContent<P> content = new ImportDefinition.FileContent<>(List.copyOf(kept),
                converted.header(), params);
            ImportDefinition.Issues sink = new ImportDefinition.Issues() {
                @Override
                public void file(String code, String message, Map<String, Object> p) {
                    issues.add(ImportIssue.ofFile(code, message, p));
                }

                @Override
                public void row(ImportRow row, String field, String code, String message, Map<String, Object> p) {
                    issues.add(new ImportIssue(row.number(), row.location(), field,
                        field == null ? null : converted.sources().columns().get(field), code, message, p));
                }
            };
            for (ImportDefinition.FileCheck<P> check : definition.fileChecks()) {
                try {
                    check.check(content, sink);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Import " + definition.id() + ": a file check failed", e);
                }
            }
        }
        List<Unit> units = units(definition, kept, params, issues);
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (String field : definition.totals()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (ImportRow row : kept) {
                if (row.decimal(field) != null) {
                    sum = sum.add(row.decimal(field));
                }
            }
            totals.put(field, sum);
        }
        return new Plan(converted.sources(), units, duplicates, issues, totals, converted.recordCount(),
            kept.size());
    }

    private static <P> List<Unit> units(ImportDefinition<P> definition, List<ImportRow> rows, P params,
        List<ImportIssue> issues) {
        List<Unit> units = new ArrayList<>();
        switch (definition.target()) {
            case ImportDefinition.PerRow<P> perRow -> {
                for (ImportRow row : rows) {
                    try {
                        units.add(new Unit(String.valueOf(row.number()), List.of(row),
                            Objects.requireNonNull(perRow.input().apply(row, params), "input")));
                    } catch (RuntimeException e) {
                        issues.add(inputIssue(row, e));
                    }
                }
            }
            case ImportDefinition.PerGroup<P> perGroup -> {
                Map<String, List<ImportRow>> groups = new LinkedHashMap<>();
                for (ImportRow row : rows) {
                    String key;
                    try {
                        key = perGroup.key().apply(row);
                    } catch (RuntimeException e) {
                        issues.add(inputIssue(row, e));
                        continue;
                    }
                    groups.computeIfAbsent(key == null ? "" : key, k -> new ArrayList<>()).add(row);
                }
                for (Map.Entry<String, List<ImportRow>> group : groups.entrySet()) {
                    try {
                        units.add(new Unit(group.getKey(), group.getValue(), Objects.requireNonNull(
                            perGroup.input().apply(List.copyOf(group.getValue()), params), "input")));
                    } catch (RuntimeException e) {
                        issues.add(inputIssue(group.getValue().getFirst(), e));
                    }
                }
            }
        }
        return units;
    }

    /** The input function refused the row: its message says why (a value it cannot map). */
    private static ImportIssue inputIssue(ImportRow row, RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new ImportIssue(row.number(), row.location(), null, null, PlatformErrorCodes.INVALID_VALUE, message,
            Map.of());
    }

    /** The row's external reference; null when the import has none, the row gives none or the function fails. */
    public static String ref(ImportDefinition<?> definition, ImportRow row) {
        if (definition.externalRef() == null) {
            return null;
        }
        try {
            String ref = definition.externalRef().apply(row);
            return ref == null || ref.isBlank() ? null : ref;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String refOrIssue(ImportDefinition<?> definition, ImportRow row, List<ImportIssue> issues) {
        try {
            String ref = definition.externalRef().apply(row);
            if (ref != null && ref.length() > ImportDefinition.MAX_REF_LENGTH) {
                issues.add(new ImportIssue(row.number(), row.location(), null, null, PlatformErrorCodes.TOO_LONG,
                    "The external reference is longer than " + ImportDefinition.MAX_REF_LENGTH + " characters",
                    Map.of("max", ImportDefinition.MAX_REF_LENGTH)));
                return null;
            }
            return ref == null || ref.isBlank() ? null : ref;
        } catch (RuntimeException e) {
            issues.add(inputIssue(row, e));
            return null;
        }
    }
}
