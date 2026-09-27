package com.jabiz.query;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.testkinds.TestI18nText;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lookups and labels by display field (docs/design/16-content-authoring.md section 2). */
class LookupQueryCompilerTest {

    static {
        TestI18nText.register();
    }

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    private static final EntityDefinition CARRIER = EntityDefinition.define("Carrier", eb -> {
        eb.physicalTable("t_carrier");
        eb.primaryKey("code");
        eb.field("code", f -> f.physicalColumn("carrier_code").asText(10));
        eb.field("name", f -> f.physicalColumn("carrier_name").asText(100));
        eb.field("region", f -> f.physicalColumn("region").asText(8));
        eb.display("name");
    });

    private static final EntityDefinition STORY = EntityDefinition.define("Story", eb -> {
        eb.physicalTable("t_story");
        eb.primaryKey("storyId");
        eb.field("storyId", f -> f.physicalColumn("story_id").asSemanticIdentity("urn:test:story"));
        eb.field("title", f -> f.physicalColumn("title").apply(I18nText.of(100)));
        eb.field("region", f -> f.physicalColumn("region").asText(8));
        eb.display("title");
        eb.temporal();
    });

    private static final EntityDefinition PLAIN = EntityDefinition.define("Plain", eb -> {
        eb.physicalTable("t_plain");
        eb.primaryKey("id");
        eb.field("id", f -> f.physicalColumn("id").asText(10));
    });

    private static DatasetDefinition dataset(String entity, int maxBatch) {
        return DatasetDefinition.define("urn:jabiz:dataset:test:" + entity, d -> d
            .targetEntityType(entity)
            .scope(s -> s.fixed("region", "JP"))
            .policy(p -> p.maxQueryBatchSize(maxBatch))
            .storage(s -> s.connectionPoolRef("default")));
    }

    private final QueryCompiler compiler = new QueryCompiler();

    @Test
    void textLookupsMatchCaseInsensitivelyWithinTheScope() {
        RawQueryPlan plan = compiler.compileLookup(dataset("Carrier", 1000), CARRIER, " Fast ", "en", "en", 50,
            Map.of("region", "JP"), null);

        assertThat(plan.sql()).isEqualTo("SELECT * FROM t_carrier t WHERE region = :p0"
            + " AND t.carrier_name ILIKE :p1 ESCAPE '\\' ORDER BY t.carrier_name ASC, t.carrier_code ASC LIMIT 20");
        assertThat(plan.bindParams().get("p0").value()).isEqualTo("JP");
        assertThat(plan.bindParams().get("p1").value()).isEqualTo("%Fast%");
    }

    @Test
    void wildcardsInTheSearchTextMatchLiterally() {
        RawQueryPlan plan = compiler.compileLookup(dataset("Carrier", 1000), CARRIER, "50%_a\\b", null, null, 5,
            Map.of("region", "JP"), null);
        assertThat(plan.bindParams().get("p1").value()).isEqualTo("%50\\%\\_a\\\\b%");
        assertThat(plan.sql()).endsWith("LIMIT 5");
    }

    @Test
    void anEmptySearchTextMatchesEverythingUpToTheBatchSize() {
        RawQueryPlan plan = compiler.compileLookup(dataset("Carrier", 3), CARRIER, "  ", null, null, 20,
            Map.of("region", "JP"), null);
        assertThat(plan.sql()).doesNotContain("ILIKE").endsWith("LIMIT 3");
        assertThat(compiler.compileLookup(dataset("Carrier", 1000), CARRIER, null, null, null, 0,
            Map.of("region", "JP"), null).sql()).endsWith("LIMIT 1");
    }

    @Test
    void multilingualLookupsMatchAnyLanguageAndSortByTheRequestLanguage() {
        RawQueryPlan plan = compiler.compileLookup(dataset("Story", 1000), STORY, "tea", "ja", "en", 10,
            Map.of("region", "JP"), TimeSlice.asOf(NOW));

        assertThat(plan.sql()).startsWith("SELECT * FROM (SELECT DISTINCT ON (story_id) * FROM t_story")
            .contains(") v WHERE NOT is_deleted AND region = :p0 AND EXISTS (SELECT 1 FROM jsonb_each_text(v.title)"
                + " AS l(lang, text) WHERE l.text ILIKE :p1 ESCAPE '\\')")
            .endsWith("ORDER BY COALESCE(v.title ->> :p2, v.title ->> :p3, v.title ->> :p4) ASC, v.story_id ASC"
                + " LIMIT 10");
        // Request language, default language, then the remaining languages in platform order.
        assertThat(List.of(plan.bindParams().get("p2").value(), plan.bindParams().get("p3").value(),
            plan.bindParams().get("p4").value())).containsExactly("ja", "en", "zh");
        assertThat(plan.bindParams().get("__asOf").value()).isEqualTo(NOW);
    }

    @Test
    void unsupportedRequestLanguagesAreSkipped() {
        RawQueryPlan plan = compiler.compileLookup(dataset("Story", 1000), STORY, null, "fr", "en", 10,
            Map.of("region", "JP"), TimeSlice.asOf(NOW));
        assertThat(plan.sql()).contains("COALESCE(v.title ->> :p1, v.title ->> :p2, v.title ->> :p3)");
        assertThat(plan.bindParams().get("p1").value()).isEqualTo("en");
    }

    @Test
    void labelsReadTheGivenKeysWithinTheScope() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RawQueryPlan plan = compiler.compileLabels(dataset("Story", 1000), STORY,
            List.of(first.toString(), second), Map.of("region", "JP"), TimeSlice.asOf(NOW));

        assertThat(plan.sql()).contains(") v WHERE NOT is_deleted AND region = :p0 AND v.story_id IN (:p1)")
            .endsWith("LIMIT 2");
        assertThat(plan.bindParams().get("p1").value()).isEqualTo(List.of(first, second));

        RawQueryPlan none = compiler.compileLabels(dataset("Carrier", 1000), CARRIER, List.of(),
            Map.of("region", "JP"), null);
        assertThat(none.sql()).contains("AND 1 = 0").endsWith("LIMIT 1");
    }

    @Test
    void labelKeysMustConvert() {
        assertThatThrownBy(() -> compiler.compileLabels(dataset("Story", 1000), STORY, List.of("S-1"),
            Map.of("region", "JP"), TimeSlice.asOf(NOW)))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                .extracting(Violation::ruleCode).containsExactly("INVALID_VALUE"));
        assertThatThrownBy(() -> compiler.compileLabels(dataset("Carrier", 1000), CARRIER,
            java.util.Arrays.asList("A", null), Map.of("region", "JP"), null))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void entitiesWithoutDisplayFieldsAreRefused() {
        DatasetDefinition plain = DatasetDefinition.define("urn:jabiz:dataset:test:Plain", d -> d
            .targetEntityType("Plain").storage(s -> s.connectionPoolRef("default")));
        assertThatThrownBy(() -> compiler.compileLookup(plain, PLAIN, "x", null, null, 5, Map.of(), null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("declares no display field");
        assertThatThrownBy(() -> compiler.compileLabels(plain, PLAIN, List.of("x"), Map.of(), null))
            .isInstanceOf(IllegalStateException.class);
    }
}
