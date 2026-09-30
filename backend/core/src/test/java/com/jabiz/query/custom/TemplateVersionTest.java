package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;
import com.jabiz.query.template.SqlTemplateFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/design/19-reports.md section 2.3. */
class TemplateVersionTest {

    private static final String FILE = "/*---\nid: a\n---*/\nSELECT 1 AS one\n";

    @Test
    void aFileVersionIsTheHashOfItsWholeTextWhateverTheLineEndings() {
        String version = SqlTemplateFile.split("a.sql", FILE).version();

        assertThat(version).hasSize(64).matches("[0-9a-f]+");
        assertThat(SqlTemplateFile.split("a.sql", FILE.replace("\n", "\r\n")).version()).isEqualTo(version);
        assertThat(SqlTemplateFile.split("a.sql", "﻿" + FILE).version()).isEqualTo(version);
        assertThat(TemplateVersion.ofText(FILE.replace("\n", "\r"))).isEqualTo(version);
        // Any change, the header included, is a new version.
        assertThat(SqlTemplateFile.split("a.sql", FILE.replace("id: a", "id: a\ndescription: x")).version())
            .isNotEqualTo(version);
        assertThat(SqlTemplateFile.split("a.sql", FILE.replace("1 AS", "2 AS")).version()).isNotEqualTo(version);
    }

    @Test
    void aJavaDeclaredQueryIsVersionedByItsDefinition() {
        AdvancedQueryDefinition query = define("SELECT 1 AS one", null);

        assertThat(query.version()).isEqualTo(define("SELECT 1 AS one", null).version()).hasSize(64);
        assertThat(define("SELECT 2 AS one", null).version()).isNotEqualTo(query.version());
        assertThat(define("SELECT 1 AS one", ReportSpec.PLAIN).version()).isNotEqualTo(query.version());
        // Filling in inherited kinds or reading through another dataset keeps the version.
        assertThat(query.withDataset("Order", "urn:x").version()).isEqualTo(query.version());
        assertThat(query.withFields(query.parameters(), query.resultFields()).version()).isEqualTo(query.version());
    }

    @Test
    void anExplicitVersionWins() {
        assertThat(AdvancedQueryDefinition.define("a", q -> q.returns("one", new SemanticKind.Bool())
            .sqlTemplate("SELECT true AS one").version("v1")).version()).isEqualTo("v1");
    }

    private static AdvancedQueryDefinition define(String sql, ReportSpec report) {
        return AdvancedQueryDefinition.define("a", q -> q.fromEntities("Order")
            .returns("one", new SemanticKind.Numeric(9, 0)).report(report).sqlTemplate(sql));
    }
}
