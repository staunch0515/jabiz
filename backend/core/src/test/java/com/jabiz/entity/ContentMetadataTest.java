package com.jabiz.entity;

import com.jabiz.entity.i18n.I18nText;
import com.jabiz.testkinds.TestI18nText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Display fields and process-only fields (docs/design/16-content-authoring.md sections 2 and 5). */
class ContentMetadataTest {

    static {
        TestI18nText.register();
    }

    private static EntityDefinition story(Consumer<EntityBuilder> extra) {
        return EntityDefinition.define("Story", eb -> {
            eb.physicalTable("t_story");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:test:story"));
            eb.field("title", f -> f.physicalColumn("f_title").asText(100));
            eb.field("headline", f -> f.physicalColumn("f_headline").apply(I18nText.of(100)));
            eb.field("secret", f -> f.physicalColumn("f_secret").asText(100).sensitive());
            eb.field("status", f -> f.physicalColumn("f_status").asCode(null, "DRAFT", "PUBLISHED").processOnly());
            eb.field("version", f -> f.physicalColumn("f_version").asVersion());
            eb.stateTransitions("status", st -> st.from("DRAFT").to("PUBLISHED"));
            extra.accept(eb);
        });
    }

    @Test
    void textAndMultilingualFieldsCanBeDisplayFields() {
        assertThat(story(eb -> eb.display("title")).displayField).isEqualTo("title");
        assertThat(story(eb -> eb.display("headline")).displayField).isEqualTo("headline");
        assertThat(story(eb -> { }).displayField).isNull();
        assertThat(MetaModelExporter.export(story(eb -> { }))).doesNotContainKey("display");
    }

    @Test
    void displayFieldsMustBeReadableTexts() {
        assertThatThrownBy(() -> story(eb -> eb.display("missing")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown field 'missing'");
        assertThatThrownBy(() -> story(eb -> eb.display("status")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be a Text or jabiz.i18n-text");
        assertThatThrownBy(() -> story(eb -> eb.display("secret")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("is sensitive");
        assertThatThrownBy(() -> story(eb -> {
            eb.display("title");
            eb.display("headline");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("declared twice");
    }

    @Test
    void processOnlyFieldsAreListedExportedAndReadOnlyInTheSchema() {
        EntityDefinition def = story(eb -> { });
        assertThat(def.processOnlyFields()).containsExactly("status");
        assertThat(def.field("status").processOnly()).isTrue();
        assertThat(def.field("title").processOnly()).isFalse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) MetaModelExporter.export(def).get("fields");
        assertThat(fields).filteredOn(f -> f.get("name").equals("status")).singleElement()
            .satisfies(f -> assertThat(f).containsEntry("processOnly", true));
        assertThat(fields).filteredOn(f -> f.get("name").equals("title")).singleElement()
            .satisfies(f -> assertThat(f).containsEntry("processOnly", false));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) JsonSchemaExporter.export(def)
            .get("properties");
        assertThat(properties.get("status")).containsEntry("readOnly", true);
        assertThat(properties.get("title")).doesNotContainKey("readOnly");
    }

    @Test
    void processOnlyExcludesSensitiveAndGenerated() {
        assertThatThrownBy(() -> story(eb -> eb.field("hash", f -> f.physicalColumn("f_hash").asText(10)
            .sensitive().processOnly())))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("process-only and sensitive");
        assertThatThrownBy(() -> story(eb -> eb.field("code", f -> f.physicalColumn("f_code").asText(10)
            .generated(true).processOnly())))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("process-only and generated");
    }

    @Test
    void theSoleInitialStateIsKnown() {
        assertThat(story(eb -> { }).soleInitialState()).isEqualTo("DRAFT");
        EntityDefinition twoStarts = EntityDefinition.define("Flow", eb -> {
            eb.physicalTable("t_flow");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:test:flow"));
            eb.field("status", f -> f.physicalColumn("f_status").asCode(null, "A", "B", "C"));
            eb.stateTransitions("status", st -> st.from("A").to("C").from("B").to("C"));
        });
        assertThat(twoStarts.soleInitialState()).isNull();
        assertThat(EntityDefinition.define("Plain", eb -> {
            eb.physicalTable("t_plain");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:test:plain"));
        }).soleInitialState()).isNull();
    }
}
