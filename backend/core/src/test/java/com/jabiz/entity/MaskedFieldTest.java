package com.jabiz.entity;

import com.jabiz.audit.AuditDiff;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.query.template.PublicReadChecks;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Masked fields in the metamodel (docs/design/10-security.md section 13.1, decision D28 item 7). */
class MaskedFieldTest {

    private static final EntityDefinition SUPPLIER = EntityDefinition.define("Supplier", eb -> {
        eb.physicalTable("t_supplier");
        eb.primaryKey("supplierId");
        eb.field("supplierId", f -> f.physicalColumn("supplier_id").asSemanticIdentity("urn:test:supplier"));
        eb.field("name", f -> f.physicalColumn("name").asText(100));
        eb.field("taxId", f -> f.physicalColumn("tax_id").asText(20).masked("supplier.tax-id", MaskStyle.ALL));
        eb.field("iban", f -> f.physicalColumn("iban").asText(34).masked("supplier.bank", MaskStyle.LAST4));
    });

    @Test
    void last4ShowsTheLastFourCharactersOfLongValuesOnly() {
        assertThat(MaskStyle.LAST4.apply("DE44500105175407324931")).isEqualTo("****4931");
        assertThat(MaskStyle.LAST4.apply("12345678")).isEqualTo("****5678");
        // Shorter values would give most of themselves away.
        assertThat(MaskStyle.LAST4.apply("1234567")).isEqualTo("****");
        assertThat(MaskStyle.LAST4.apply("口座番号一二三四五六")).isEqualTo("****三四五六");
        assertThat(MaskStyle.LAST4.apply(null)).isNull();
        assertThat(MaskStyle.ALL.apply("123-45-6789")).isEqualTo("****");
        assertThat(MaskStyle.ALL.apply(null)).isNull();
    }

    @Test
    void taxIdShowsTheFormOfTheNumberAndItsLastFourDigits() {
        assertThat(MaskStyle.TAX_ID.apply("123-45-1234")).isEqualTo("***-**-1234");
        assertThat(MaskStyle.TAX_ID.apply("123451234")).isEqualTo("***-**-1234");
        assertThat(MaskStyle.TAX_ID.apply("12-3456789")).isEqualTo("**-***6789");
        // Not nine digits: nothing of it is shown.
        assertThat(MaskStyle.TAX_ID.apply("12345")).isEqualTo("****");
        assertThat(MaskStyle.TAX_ID.apply("DE123456789")).isEqualTo("****");
        assertThat(MaskStyle.TAX_ID.apply(null)).isNull();
    }

    @Test
    void theSqlFormMatchesTheJavaForm() {
        assertThat(MaskStyle.LAST4.sql("iban")).isEqualTo("CASE WHEN iban IS NULL THEN NULL WHEN char_length(iban) "
            + ">= 8 THEN '****' || right(iban, 4) ELSE '****' END");
        assertThat(MaskStyle.ALL.sql("tax_id")).isEqualTo("CASE WHEN tax_id IS NULL THEN NULL ELSE '****' END");
        assertThat(MaskStyle.TAX_ID.sql("tin")).isEqualTo("CASE WHEN tin IS NULL THEN NULL"
            + " WHEN tin ~ '^[0-9]{2}-[0-9]{7}$' THEN '**-***' || right(tin, 4)"
            + " WHEN tin ~ '^[0-9]{3}-?[0-9]{2}-?[0-9]{4}$' THEN '***-**-' || right(tin, 4) ELSE '****' END");
    }

    @Test
    void maskedFormsAreRecognised() {
        assertThat(MaskStyle.looksMasked("****4931")).isTrue();
        assertThat(MaskStyle.looksMasked("****")).isTrue();
        assertThat(MaskStyle.looksMasked("DE44")).isFalse();
        assertThat(MaskStyle.looksMasked(null)).isFalse();
        assertThat(MaskStyle.looksMasked(4931)).isFalse();
        assertThat(MaskStyle.looksMasked("***-**-1234")).isTrue();
        assertThat(MaskStyle.looksMasked("**-***6789")).isTrue();
        assertThat(MaskStyle.looksMasked("123-45-1234")).isFalse();
    }

    @Test
    void theDefinitionKnowsItsMaskedFieldsAndExportsThem() {
        assertThat(SUPPLIER.maskedFields()).extracting(FieldDefinition::name).containsExactly("taxId", "iban");
        assertThat(SUPPLIER.field("iban").masked()).isEqualTo(new MaskSpec("supplier.bank", MaskStyle.LAST4));
        assertThat(SUPPLIER.field("name").isMasked()).isFalse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) MetaModelExporter.export(SUPPLIER).get("fields");
        assertThat(fields.stream().filter(f -> f.get("name").equals("iban")).findFirst().orElseThrow())
            .containsEntry("masked", Map.of("permission", "supplier.bank", "style", "LAST4"));
        assertThat(fields.stream().filter(f -> f.get("name").equals("name")).findFirst().orElseThrow())
            .doesNotContainKey("masked");
    }

    @Test
    void onlyPlainTextFieldsCanBeMasked() {
        assertThatThrownBy(() -> entity(eb -> eb.field("amount", f -> f.physicalColumn("amount").asNumeric(9, 0)
            .masked("p", MaskStyle.ALL)))).hasMessageContaining("must be a text field");
        assertThatThrownBy(() -> entity(eb -> eb.field("secret", f -> f.physicalColumn("secret").asText(10)
            .sensitive().masked("p", MaskStyle.ALL)))).hasMessageContaining("masked and sensitive");
        assertThatThrownBy(() -> entity(eb -> eb.field("code", f -> f.physicalColumn("code").asText(10)
            .generated(true).masked("p", MaskStyle.ALL)))).hasMessageContaining("masked and generated");
        assertThatThrownBy(() -> new MaskSpec(" ", MaskStyle.ALL)).hasMessageContaining("permission");
        assertThatThrownBy(() -> EntityDefinition.define("Keyed", eb -> {
            eb.physicalTable("t_keyed");
            eb.primaryKey("code");
            eb.field("code", f -> f.physicalColumn("code").asText(10).masked("p", MaskStyle.ALL));
        })).hasMessageContaining("primary key 'code' cannot be masked");
    }

    @Test
    void aMaskedFieldIsNeitherTheDisplayFieldNorTheDefaultSort() {
        assertThatThrownBy(() -> entity(eb -> {
            eb.field("account", f -> f.physicalColumn("account").asText(34).masked("p", MaskStyle.LAST4));
            eb.display("account");
        })).hasMessageContaining("display field 'account' is masked");
        assertThatThrownBy(() -> entity(eb -> {
            eb.field("account", f -> f.physicalColumn("account").asText(34).masked("p", MaskStyle.LAST4));
            eb.listView("default", lv -> lv.columns("account").sorts("account").defaultSort("account", true));
        })).hasMessageContaining("default sort 'account' is masked");
    }

    @Test
    void theAuditTrailKeepsTheMaskedForm() {
        Map<String, AuditDiff.Change> changes = AuditDiff.of(SUPPLIER,
            Map.of("iban", "DE44500105175407324931", "taxId", "123-45-6789", "name", "A"),
            Map.of("iban", "NL91ABNA0417164300", "taxId", "987-65-4321", "name", "B"));
        assertThat(changes.get("iban")).isEqualTo(new AuditDiff.Change("****4931", "****4300"));
        assertThat(changes.get("taxId")).isEqualTo(new AuditDiff.Change("****", "****"));
        assertThat(changes.get("name")).isEqualTo(new AuditDiff.Change("A", "B"));
        Map<String, Object> cleared = new HashMap<>();
        cleared.put("iban", null);
        assertThat(AuditDiff.of(SUPPLIER, Map.of("iban", "DE44500105175407324931"), cleared).get("iban"))
            .isEqualTo(new AuditDiff.Change("****4931", null));
    }

    @Test
    void aMaskedFieldIsNeverPublic() {
        DatasetDefinition open = DatasetDefinition.define("urn:test:public:Supplier", d -> d
            .targetEntityType("Supplier")
            .publicRead(p -> p.allRows().fields("name", "iban"))
            .storage(s -> s.connectionPoolRef("default")));
        assertThat(PublicReadChecks.checkDataset(open, SUPPLIER,
            new PublicReadChecks.Limits(Duration.ofSeconds(2), 100)))
            .containsExactly("public field iban is masked and can never be public");
    }

    private static EntityDefinition entity(java.util.function.Consumer<EntityBuilder> fields) {
        return EntityDefinition.define("Sample", eb -> {
            eb.physicalTable("t_sample");
            eb.primaryKey("sampleId");
            eb.field("sampleId", f -> f.physicalColumn("sample_id").asSemanticIdentity("urn:test:sample"));
            fields.accept(eb);
        });
    }
}
