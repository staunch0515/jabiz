package com.jabiz.app.it.fixture;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * An entity that is not temporal, for content authoring (docs/design/16-content-authoring.md): a display field, a
 * multilingual headline, and two process-only fields, one of them the region its datasets are scoped by. Tables are
 * created by {@code db/testmigration/V1004__it_content.sql}.
 */
public final class ItContentFixtures extends BaseEntityDefinitions {

    public static final String ARTICLE = "ItArticle";
    public static final String JP_DATASET = "urn:jabiz:dataset:it:ItArticle";
    public static final String KR_DATASET = "urn:jabiz:dataset:it:ItArticleKr";
    public static final String PUBLISH = "IT_ARTICLE_PUBLISH";
    public static final String READ = "it.article.read";
    public static final String WRITE = "it.article.write";

    public static final EntityDefinition ARTICLE_ENTITY = EntityDefinition.define(ARTICLE, eb -> {
        eb.physicalTable("it_content_article");
        eb.primaryKey("articleId");
        eb.field("articleId", f -> f.physicalColumn("f_id").required(true).immutable(true).asText(64));
        eb.field("region", f -> f.physicalColumn("f_region").required(true).processOnly().asText(8));
        eb.field("title", f -> f.physicalColumn("f_title").required(true).asText(100));
        eb.field("headline", f -> f.physicalColumn("f_headline").apply(I18nText.of(20).required("en")));
        eb.field("status", f -> f.physicalColumn("f_status").required(true).processOnly()
            .asCode("urn:jabiz:dict:it_article_status", "DRAFT", "PUBLISHED"));
        eb.field("note", f -> f.physicalColumn("f_note").processOnly().asText(200));
        eb.field("rowVersion", rowVersion("f_version"));
        eb.stateTransitions("status", st -> st.from("DRAFT").to("PUBLISHED"));
        eb.display("title");
        eb.listView("default", lv -> lv.columns("title", "status").filters("status").sorts("title"));
    });

    public record PublishInput(@NotBlank String articleId, String note) {}

    public record PublishOutput(String status) {}

    public static final ProcessDefinition<PublishInput, PublishOutput, ProcessContext> PUBLISH_PROCESS =
        ProcessDefinition.define(PUBLISH, 1, PublishInput.class, PublishOutput.class, ProcessContext.class, pb -> pb
            .permissions(WRITE)
            .actsOn(ARTICLE, "articleId", a -> a.whenField("status", "DRAFT"))
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("id", input.articleId());
                ctx.put("note", input.note());
                return ctx;
            })
            .outputMapper(ctx -> new PublishOutput("PUBLISHED"))
            .step("Load", LoadEntity.by(JP_DATASET, "id", "article"))
            .compute("Publish", (metadata, ctx) -> {
                EntityInstance article = ctx.get("article", EntityInstance.class);
                Map<String, Object> changes = new HashMap<>();
                changes.put("status", "PUBLISHED");
                changes.put("note", ctx.get("note", String.class));
                ctx.changes().update(ARTICLE, article.id(), article.version(), changes);
            }));

    private ItContentFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        EntityDefinition itArticleEntity() {
            return ARTICLE_ENTITY;
        }

        @Bean
        DatasetDefinition itArticleDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(JP_DATASET, d -> d
                .targetEntityType(ARTICLE)
                .asDefault()
                .scope(s -> s.fixed("region", "JP"))
                .permissions(READ, WRITE)
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        DatasetDefinition itArticleKrDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(KR_DATASET, d -> d
                .targetEntityType(ARTICLE)
                .scope(s -> s.fixed("region", "KR"))
                .permissions(READ, WRITE)
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        ProcessDefinition<PublishInput, PublishOutput, ProcessContext> itArticlePublishProcess() {
            return PUBLISH_PROCESS;
        }
    }
}
