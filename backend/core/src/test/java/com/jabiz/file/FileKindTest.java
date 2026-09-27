package com.jabiz.file;

import com.jabiz.entity.CustomKinds;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.QueryOperator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileKindTest {

    private final FileKindSupport support = new FileKindSupport();

    @Test
    void declaresAPolicyAndIsFoundByServiceLoader() {
        SemanticKind kind = FileKind.of("commerce.image");
        assertThat(FileKind.policyOf(kind)).contains("commerce.image");
        assertThat(FileKind.policyOf(new SemanticKind.Text(10, false))).isEmpty();
        assertThat(FileKind.policyOf(new SemanticKind.Custom("other", Map.of()))).isEmpty();
        assertThat(CustomKinds.require(FileKind.KIND_ID)).isInstanceOf(FileKindSupport.class);
        assertThatThrownBy(() -> FileKind.of("Not A Policy")).hasMessageContaining("must match");
        assertThatThrownBy(() -> FileKind.of(null)).hasMessageContaining("must match");
    }

    @Test
    void coercesUuidsOnly() {
        UUID id = UUID.fromString("0190f2a6-5b7c-7d8e-9f00-112233445566");
        assertThat(support.coerce(Map.of(), id, true)).isEqualTo(id);
        assertThat(support.coerce(Map.of(), id.toString().toUpperCase(), true)).isEqualTo(id);
        assertThatThrownBy(() -> support.coerce(Map.of(), "1-2-3-4-5", true))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> support.coerce(Map.of(), 42, true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(support.javaType(Map.of())).isEqualTo(UUID.class);
        assertThat(support.allowedOperators(Map.of())).containsExactlyInAnyOrder(QueryOperator.EQ,
            QueryOperator.NE, QueryOperator.IN, QueryOperator.IS_NULL, QueryOperator.IS_NOT_NULL);
    }

    @Test
    void listsFileFieldsAndExportsThePolicy() {
        EntityDefinition def = EntityDefinition.define("Doc", eb -> {
            eb.physicalTable("t_doc");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("id").required(true).asSemanticIdentity("urn:doc"));
            eb.field("scan", f -> f.physicalColumn("scan").kind(FileKind.of("docs.scan")));
            eb.field("title", f -> f.physicalColumn("title").asText(10));
        });
        assertThat(FileKind.fieldsOf(def)).extracting(f -> f.name()).containsExactly("scan");
        Map<String, Object> json = MetaModelExporter.kindToJson(FileKind.of("docs.scan"));
        assertThat(json).containsEntry("type", "custom").containsEntry("kindId", "jabiz.file")
            .containsEntry("policy", "docs.scan");
    }
}
