package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.quizbuks.reward.QbDigitalPrizes;
import com.jabiz.quizbuks.reward.QbRewards;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The publisher pair (S0, docs/quizbuks/shopify/S0-publisher.md D-S0-1) as the entities declare it. */
class PublisherKindTest {

    @Test
    void theKindsAreTheDictionarysAndASponsorIsTheDefaultReading() {
        assertThat(PublisherKind.ALL).containsExactly(PublisherKind.SPONSOR, PublisherKind.SHOPIFY_SHOP);
        assertThat(PublisherKind.sponsor(null)).isTrue();
        assertThat(PublisherKind.sponsor(PublisherKind.SPONSOR)).isTrue();
        assertThat(PublisherKind.sponsor(PublisherKind.SHOPIFY_SHOP)).isFalse();
    }

    @Test
    void theDeclaredPairIsImmutableRequiredAndCodedByTheDictionary() {
        EntityDefinition entity = EntityDefinition.define("T", eb -> {
            eb.physicalTable("t");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("id").immutable(true).required(true).generated(true)
                .asSemanticIdentity("urn:jabiz:entity:test:t"));
            PublisherKind.declare(eb, true);
        });
        FieldDefinition kind = entity.field(PublisherKind.KIND_FIELD);
        FieldDefinition id = entity.field(PublisherKind.ID_FIELD);
        assertThat(kind.immutable()).isTrue();
        assertThat(kind.required()).isTrue();
        assertThat(kind.processOnly()).isTrue();
        assertThat(kind.kind()).isInstanceOf(SemanticKind.Code.class);
        assertThat(((SemanticKind.Code) kind.kind()).dictUrn()).isEqualTo(PublisherKind.DICTIONARY);
        assertThat(((SemanticKind.Code) kind.kind()).allowedValues()).isEqualTo(PublisherKind.ALL);
        assertThat(id.immutable()).isTrue();
        assertThat(id.required()).isTrue();
        assertThat(id.processOnly()).isTrue();
        assertThat(id.physicalColumn()).isEqualTo("publisher_id");
        assertThat(kind.physicalColumn()).isEqualTo("publisher_kind");
    }

    @Test
    void everyCopyOfAPublicationsOwnerCarriesThePair() {
        // The rule of D-S0-2: the engine's records; the merchant account's own keep their sponsorId.
        for (EntityDefinition entity : List.of(QbPublications.PUBLICATION_ENTITY, QbPublications.TIER_ENTITY,
            QbPublications.CARD_ENTITY, QbPublications.OWNER_ENTITY)) {
            assertThat(entity.findField(PublisherKind.KIND_FIELD)).as(entity.name).isPresent();
            assertThat(entity.findField(PublisherKind.ID_FIELD)).as(entity.name).isPresent();
            assertThat(entity.findField("sponsorId")).as(entity.name).isEmpty();
        }
        assertThat(QbPublications.SPONSOR_METHODS_ENTITY.findField(PublisherKind.KIND_FIELD)).isEmpty();
    }

    @Test
    void theSealedTablesKeepTheirSponsorIdColumn() {
        // S0b, V30: a sealed column cannot be renamed (platform 21 section 2.1); the field is publisherId all the same.
        for (EntityDefinition entity : List.of(QbPublications.PUBLICATION_ENTITY, QbPublications.TIER_ENTITY,
            QbRewards.CASH_REWARD_ENTITY, QbRewards.REDEMPTION_ENTITY, QbDigitalPrizes.PRIZE_ENTITY)) {
            assertThat(entity.field(PublisherKind.ID_FIELD).physicalColumn()).as(entity.name)
                .isEqualTo(PublisherKind.SEALED_ID_COLUMN).isEqualTo("sponsor_id");
            assertThat(entity.field(PublisherKind.KIND_FIELD).physicalColumn()).as(entity.name)
                .isEqualTo("publisher_kind");
        }
        for (EntityDefinition entity : List.of(QbPublications.CARD_ENTITY, QbPublications.OWNER_ENTITY,
            QbDigitalPrizes.CODE_ENTITY)) {
            assertThat(entity.field(PublisherKind.ID_FIELD).physicalColumn()).as(entity.name)
                .isEqualTo(PublisherKind.ID_COLUMN).isEqualTo("publisher_id");
        }
    }

    @Test
    void theApprovalFactsNameThePublisher() {
        assertThat(QbPublications.approvalSubject().normalizeFacts(PublicationApprovals.facts(true, null, null,
            PublisherKind.SHOPIFY_SHOP, "shop-1", "GLOBAL", false, RewardMethod.DIGITAL_PRIZE)))
            .containsEntry(PublisherKind.KIND_FIELD, PublisherKind.SHOPIFY_SHOP)
            .containsEntry(PublisherKind.ID_FIELD, "shop-1");
    }
}
