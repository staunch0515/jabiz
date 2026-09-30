package com.jabiz.imports;

import com.jabiz.entity.SemanticKind;
import com.jabiz.i18n.PlatformErrorCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportPreparationTest {

    record Params(String currency) {}

    record Line(String sku, BigDecimal price) {}

    record Entry(String number, List<Line> lines) {}

    private static final SemanticKind USD = new SemanticKind.Monetary("USD", 2);

    private static ImportDefinition<Params> products(ImportDefinition.OnDuplicate onDuplicate) {
        return ImportDefinition.define("test.products", 1, Params.class)
            .file("test.import", ImportFormat.csv())
            .field("sku", new SemanticKind.Text(10, false), true, "SKU", "Item")
            .field("price", USD, true, "Price")
            .field("note", new SemanticKind.Text(null, false), false)
            .externalRef(row -> row.text("sku"), onDuplicate)
            .perRow("PRODUCT_CREATE", 1, (row, params) -> {
                if (row.text("sku").startsWith("BAD")) {
                    throw new IllegalArgumentException("SKU prefix BAD is not accepted");
                }
                return new Line(row.text("sku"), row.decimal("price"));
            })
            .totals("price")
            .permissions("test.import")
            .build();
    }

    private static RawRecord record(int n, String... cells) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < cells.length; i += 2) {
            values.put(cells[i], cells[i + 1]);
        }
        return new RawRecord(n, "line " + (n + 1), values);
    }

    private static ParsedFile file(List<String> columns, RawRecord... records) {
        return new ParsedFile(columns, Map.of(), List.of(records));
    }

    @Test
    void convertsGroupsNothingAndTotalsTheKeptRows() {
        ImportDefinition<Params> definition = products(ImportDefinition.OnDuplicate.SKIP);
        ParsedFile file = file(List.of("item", "Price"),
            record(1, "item", "A", "Price", "1.50"),
            record(2, "item", "B", "Price", "(2.00)"),
            record(3, "item", "A", "Price", "9.00"),
            record(4, "item", "C", "Price", "4"));
        ImportPreparation.Converted converted = ImportPreparation.convert(definition, file, ImportMapping.DEFAULT);
        assertThat(converted.sources().columns()).containsEntry("sku", "item").containsEntry("price", "Price")
            .doesNotContainKey("note");
        assertThat(converted.refs(definition)).containsExactly("A", "B", "C");
        ImportPreparation.Plan plan = ImportPreparation.plan(definition, converted, new Params("USD"), Set.of("C"));
        assertThat(plan.hasIssues()).isFalse();
        assertThat(plan.duplicates()).extracting(ImportRow::number).containsExactly(3, 4);
        assertThat(plan.units()).extracting(ImportPreparation.Unit::input)
            .containsExactly(new Line("A", new BigDecimal("1.50")), new Line("B", new BigDecimal("-2.00")));
        assertThat(plan.units().getFirst().firstRow()).isEqualTo(1);
        assertThat(plan.totals()).containsEntry("price", new BigDecimal("-0.50"));
        assertThat(plan.recordCount()).isEqualTo(4);
        assertThat(plan.rowCount()).isEqualTo(2);
    }

    @Test
    void reportsEveryProblemNotOnlyTheFirst() {
        ImportDefinition<Params> definition = products(ImportDefinition.OnDuplicate.REJECT);
        ParsedFile file = file(List.of("SKU", "Price"),
            record(1, "SKU", "A", "Price", "1.001"),
            record(2, "SKU", "", "Price", "x"),
            new RawRecord(3, "line 4", Map.of("SKU", "D", "Price", "1"), Set.of(), "too many cells"),
            record(4, "SKU", "BAD-1", "Price", "1"),
            record(5, "SKU", "E", "Price", "1"),
            record(6, "SKU", "E", "Price", "2"),
            record(7, "SKU", "F", "Price", "3"));
        ImportPreparation.Converted converted = ImportPreparation.convert(definition, file, ImportMapping.DEFAULT);
        ImportPreparation.Plan plan = ImportPreparation.plan(definition, converted, new Params("USD"), Set.of("F"));
        List<String> found = new ArrayList<>();
        plan.issues().forEach(issue -> found.add(issue.row() + ":" + issue.field() + ":" + issue.code()));
        assertThat(found).containsExactly(
            "1:price:" + PlatformErrorCodes.MONETARY_SCALE,
            "2:sku:" + PlatformErrorCodes.REQUIRED,
            "2:price:" + ImportCodes.VALUE_INVALID,
            "3:null:" + ImportCodes.EXTRA_CELLS,
            "6:null:" + ImportCodes.DUPLICATE_REF,
            "7:null:" + ImportCodes.DUPLICATE_REF,
            "4:null:" + PlatformErrorCodes.INVALID_VALUE);
        assertThat(plan.issues().get(0).column()).isEqualTo("Price");
        assertThat(plan.issues().get(0).location()).isEqualTo("line 2");
        assertThat(plan.units()).hasSize(1);
    }

    @Test
    void mappingChoosesColumnsAndConstants() {
        ImportDefinition<Params> definition = products(ImportDefinition.OnDuplicate.REJECT);
        ParsedFile file = file(List.of("Code", "Amount", "SKU"), record(1, "Code", "Z", "Amount", "3", "SKU", "no"));
        ImportMapping mapping = new ImportMapping(Map.of("sku", "code", "price", "Amount"), Map.of("note", "batch 7"),
            null);
        ImportPreparation.Converted converted = ImportPreparation.convert(definition, file, mapping);
        assertThat(converted.rows().getFirst().values()).containsEntry("sku", "Z").containsEntry("note", "batch 7");
        assertThat(converted.issues()).isEmpty();

        ImportMapping wrong = new ImportMapping(Map.of("sku", "Nope", "ghost", "Code"), Map.of("spirit", "x"), null);
        ImportPreparation.Converted refused = ImportPreparation.convert(definition, file, wrong);
        assertThat(refused.issues()).extracting(ImportIssue::code).containsExactlyInAnyOrder(
            ImportCodes.UNKNOWN_FIELD, ImportCodes.UNKNOWN_FIELD, ImportCodes.UNKNOWN_COLUMN,
            ImportCodes.COLUMN_MISSING);
        assertThat(refused.rows()).isEmpty();

        ImportPreparation.Converted missing = ImportPreparation.convert(definition,
            file(List.of("Other"), record(1, "Other", "1")), ImportMapping.DEFAULT);
        assertThat(missing.issues()).extracting(ImportIssue::field).containsExactly("sku", "price");
        assertThat(missing.issues()).extracting(ImportIssue::code).containsOnly(ImportCodes.COLUMN_MISSING);

        // A required field mapped to nothing is missing once for the file, not once per row.
        ImportPreparation.Converted blank = ImportPreparation.convert(definition,
            file(List.of("Other", "Price"), record(1, "Other", "1", "Price", "1")),
            new ImportMapping(Map.of("sku", " "), Map.of(), null));
        assertThat(blank.issues()).extracting(ImportIssue::code).containsExactly(ImportCodes.COLUMN_MISSING);

        ImportPreparation.Converted empty = ImportPreparation.convert(definition, file(List.of("SKU", "Price")),
            ImportMapping.DEFAULT);
        assertThat(empty.issues()).extracting(ImportIssue::code).containsExactly(ImportCodes.EMPTY);
    }

    @Test
    void groupsRowsAndChecksTheWholeFile() {
        ImportDefinition<ImportDefinition.NoParams> journal = ImportDefinition.define("test.journal", 1)
            .file("test.import", ImportFormat.csv())
            .field("entry", new SemanticKind.Text(null, false), true)
            .field("sku", new SemanticKind.Text(null, false), true)
            .field("amount", USD, true)
            .perGroup(row -> row.text("entry"), "POST", 1, (rows, params) -> new Entry(rows.getFirst().text("entry"),
                rows.stream().map(r -> new Line(r.text("sku"), r.decimal("amount"))).toList()))
            .fileCheck((content, issues) -> {
                BigDecimal sum = content.rows().stream().map(r -> r.decimal("amount"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (sum.signum() != 0) {
                    issues.file("TEST_UNBALANCED", "does not balance", Map.of("difference", sum));
                }
                if (!"100".equals(content.header().get("opening"))) {
                    issues.file("TEST_HEADER", "no opening", Map.of());
                }
                issues.row(content.rows().getFirst(), "amount", "TEST_ROW", "first row flagged", Map.of());
            })
            .totals("amount")
            .permissions("test.import")
            .build();
        ParsedFile file = new ParsedFile(List.of("entry", "sku", "amount"), Map.of("opening", "100"), List.of(
            record(1, "entry", "J1", "sku", "a", "amount", "10"),
            record(2, "entry", "J2", "sku", "b", "amount", "5"),
            record(3, "entry", "J1", "sku", "c", "amount", "-10"),
            record(4, "entry", "J2", "sku", "d", "amount", "-4")));
        ImportPreparation.Plan plan = ImportPreparation.plan(journal,
            ImportPreparation.convert(journal, file, ImportMapping.DEFAULT), new ImportDefinition.NoParams(),
            Set.of());
        assertThat(plan.units()).extracting(ImportPreparation.Unit::key).containsExactly("J1", "J2");
        assertThat(plan.units().getFirst().rows()).extracting(ImportRow::number).containsExactly(1, 3);
        assertThat(plan.issues()).extracting(ImportIssue::code).containsExactly("TEST_UNBALANCED", "TEST_ROW");
        assertThat(plan.issues().get(1).column()).isEqualTo("amount");
        assertThat(plan.totals()).containsEntry("amount", new BigDecimal("1.00"));

        ImportDefinition<ImportDefinition.NoParams> failing = ImportDefinition.define("test.failing", 1)
            .file("test.import", ImportFormat.csv())
            .field("amount", USD, true)
            .perRow("POST", 1, (row, params) -> row)
            .fileCheck((content, issues) -> {
                throw new IllegalStateException("bug");
            })
            .permissions("test.import")
            .build();
        ParsedFile one = file(List.of("amount"), record(1, "amount", "1"));
        assertThatThrownBy(() -> ImportPreparation.plan(failing, ImportPreparation.convert(failing, one,
            ImportMapping.DEFAULT), new ImportDefinition.NoParams(), Set.of())).hasMessageContaining("file check");
    }

    @Test
    void fileChecksWaitUntilEveryRowIsRead() {
        boolean[] ran = {false};
        ImportDefinition<ImportDefinition.NoParams> definition = ImportDefinition.define("test.checked", 1)
            .file("test.import", ImportFormat.csv())
            .field("amount", USD, true)
            .perRow("POST", 1, (row, params) -> row)
            .fileCheck((content, issues) -> ran[0] = true)
            .permissions("test.import")
            .build();
        ImportPreparation.plan(definition, ImportPreparation.convert(definition,
            file(List.of("amount"), record(1, "amount", "x")), ImportMapping.DEFAULT),
            new ImportDefinition.NoParams(), Set.of());
        assertThat(ran[0]).isFalse();
    }

    @Test
    void marksFormulaValues() {
        ImportDefinition<Params> definition = products(ImportDefinition.OnDuplicate.SKIP);
        ParsedFile file = file(List.of("SKU", "Price"), new RawRecord(1, "Data row 2",
            Map.of("SKU", "A", "Price", "3"), Set.of("Price"), null));
        ImportRow row = ImportPreparation.convert(definition, file, ImportMapping.DEFAULT).rows().getFirst();
        assertThat(row.formulas()).containsExactly("price");
        assertThat(row.get("price")).isEqualTo(new BigDecimal("3.00"));
    }
}
