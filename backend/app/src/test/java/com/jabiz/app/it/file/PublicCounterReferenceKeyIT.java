package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.PublicCounter;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.publicread.PublicCounters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A public counter keyed on the primary key of an ordinary table that is a reference to an append-only entity (a
 * product's card, identified by the product's id; ROADMAP 17zz2): the key column is a uuid, so the key is bound as one.
 * Before, it was bound as text and the counting request failed with 500 (QuizBuks: the cards of publications).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"jabiz.public.enabled=true"})
class PublicCounterReferenceKeyIT extends FileItSupport {

    static final String CARD = "ItProductCard";
    static final String CARD_DATASET = "urn:jabiz:dataset:default:ItProductCard";
    static final String PUBLIC_CARDS = "urn:jabiz:dataset:public:ItProductCard";
    static final String CARD_VIEWS = "it.card-view";

    @TestConfiguration
    static class Cards extends BaseEntityDefinitions {

        @Bean
        EntityDefinition itProductCardEntity() {
            return EntityDefinition.define(CARD, eb -> {
                eb.physicalTable("it_product_card");
                eb.primaryKey("productId");
                eb.field("productId", f -> f.physicalColumn("product_id").immutable(true).required(true)
                    .asReference(CommerceEntities.PRODUCT));
                eb.field("title", f -> f.physicalColumn("title").required(true).asText(100));
                eb.field("rowVersion", rowVersion("f_version"));
            });
        }

        @Bean
        DatasetDefinition itProductCardDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(CARD_DATASET, d -> d.targetEntityType(CARD).asDefault()
                .permissions("it.card.read", "it.card.write").storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        DatasetDefinition itPublicCardDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(PUBLIC_CARDS, d -> d.targetEntityType(CARD)
                .publicRead(p -> p.allRows().fields("productId", "title"))
                .permissions("it.card.read", "it.card.write").storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        PublicCounter itCardViews() {
            return PublicCounter.define(CARD_VIEWS, c -> c.keys(PUBLIC_CARDS, "productId"));
        }
    }

    @Autowired
    PublicCounters counters;

    private void view(String key) {
        client.post().uri("/api/public/counters/" + CARD_VIEWS + "/" + key)
            .header(HttpHeaders.USER_AGENT, "Browser " + UUID.randomUUID()).exchange()
            .expectStatus().isNoContent();
    }

    private static long counted(String key) {
        return query("SELECT coalesce(sum(hits), 0) AS n FROM sys_public_counter_day WHERE counter_name = '"
            + CARD_VIEWS + "' AND counter_key = '" + key + "'").stream()
            .mapToLong(row -> ((Number) row.get("n")).longValue()).sum();
    }

    @Test
    void aKeyThatIsAReferenceToAnAppendOnlyEntityIsCountedForAShownRow() {
        String product = UUID.randomUUID().toString();
        // Test set-up only: the card of a product (the column is the product's id, a uuid).
        execute("INSERT INTO it_product_card (product_id, title) VALUES (?::uuid, ?)", product, "A card");

        view(product);
        view(product.toUpperCase(java.util.Locale.ROOT));
        // A key no shown row has is ignored, as for any counter.
        view(UUID.randomUUID().toString());
        counters.flush().block();

        assertThat(counted(product)).isEqualTo(2);
    }
}
