package com.jabiz.security;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.JsonSchemaExporter;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.template.TemplateChecks;
import com.jabiz.query.template.TemplateProblem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sensitive fields (docs/design/10-security.md section 6): declared once, never read back. */
class SensitiveFieldTest {

    private static final EntityDefinition ACCOUNT = EntityDefinition.define("Account", eb -> {
        eb.physicalTable("t_account");
        eb.primaryKey("accountId");
        eb.field("accountId", f -> f.physicalColumn("account_id").required(true).asSemanticIdentity("urn:account"));
        eb.field("name", f -> f.physicalColumn("name").required(true).asText(50));
        eb.field("secretHash", f -> f.physicalColumn("secret_hash").asText(100).sensitive());
        eb.listView("default", lv -> lv.columns("name").filters("name").sorts("name"));
    });

    private static final Function<String, Optional<EntityDefinition>> ENTITIES =
        name -> "Account".equals(name) ? Optional.of(ACCOUNT) : Optional.empty();

    @Test
    void theFlagIsPartOfTheDefinition() {
        assertThat(ACCOUNT.field("secretHash").sensitive()).isTrue();
        assertThat(ACCOUNT.field("name").sensitive()).isFalse();
        assertThat(ACCOUNT.sensitiveFields()).containsExactly("secretHash");
        // Definitions built without the flag are not sensitive.
        assertThat(new FieldDefinition("x", "x", false, false, false, new SemanticKind.Bool(), List.of(), List.of())
            .sensitive()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportsMarkTheFieldWriteOnly() {
        List<Map<String, Object>> fields = (List<Map<String, Object>>) MetaModelExporter.export(ACCOUNT).get("fields");
        assertThat(fields).filteredOn(f -> f.get("name").equals("secretHash")).singleElement()
            .satisfies(f -> assertThat(f).containsEntry("sensitive", true));
        assertThat(fields).filteredOn(f -> f.get("name").equals("name")).singleElement()
            .satisfies(f -> assertThat(f).containsEntry("sensitive", false));

        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>)
            JsonSchemaExporter.export(ACCOUNT).get("properties");
        assertThat(properties.get("secretHash")).containsEntry("writeOnly", true);
        assertThat(properties.get("name")).doesNotContainKey("writeOnly");
    }

    @Test
    void listViewsCannotShowFilterOrSortSensitiveFields() {
        for (String part : List.of("columns", "filters", "sorts")) {
            assertThatThrownBy(() -> EntityDefinition.define("Leaky", eb -> {
                eb.physicalTable("t_leaky");
                eb.primaryKey("id");
                eb.field("id", f -> f.physicalColumn("id").asSemanticIdentity("urn:leaky"));
                eb.field("pin", f -> f.physicalColumn("pin").asText(10).sensitive());
                eb.listView("default", lv -> {
                    switch (part) {
                        case "columns" -> lv.columns("pin");
                        case "filters" -> lv.filters("pin");
                        default -> lv.sorts("pin");
                    }
                });
            })).hasMessageContaining("sensitive field 'pin'");
        }
    }

    @Test
    void templatesCannotReadSensitiveFields() {
        AdvancedQueryDefinition query = AdvancedQueryDefinition.define("leak", q -> q
            .fromEntities("Account")
            .permissions("p")
            .returnsFrom("hash", "Account", "secretHash")
            .sqlTemplate("SELECT a.{{Account.secretHash}} AS hash FROM {{Account}} a"));

        TemplateChecks.Resolved resolved = TemplateChecks.resolve(query, ENTITIES);
        List<TemplateProblem> problems = new ArrayList<>(resolved.problems());
        problems.addAll(TemplateChecks.check(resolved.query(), ENTITIES));

        assertThat(problems).extracting(TemplateProblem::message).contains(
            "result hash refers to sensitive field Account.secretHash",
            "field Account.secretHash is sensitive and cannot be read");
    }
}
