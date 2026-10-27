package com.jabiz.quizbuks.audience;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;

/**
 * The entities of matching (docs/quizbuks/plans/Q39-audience.md): what takers tell of themselves
 * ({@code QbProfileAttribute}) and the conditions of publications ({@code QbPublicationCriterion}). Both are ordinary
 * tables written by processes only: a taker clears what they told, and a draft's conditions are replaced while it is a
 * draft (frozen with the publication on submission).
 *
 * <p>A taker reads their own rows ({@code me:}); nobody else reads them, not even the administration: the count
 * template reads them in a process and gives a sponsor a rounded number only ({@link AudienceCount}). A sponsor reads
 * the conditions of their own publications ({@code sponsor:}); the feed, the publication page and the start read every
 * publication's.
 */
public final class QbAudience {

    public static final String ATTRIBUTE = "QbProfileAttribute";
    public static final String CRITERION = "QbPublicationCriterion";

    public static final String ATTRIBUTE_DATASET = "urn:jabiz:dataset:default:" + ATTRIBUTE;
    public static final String MY_ATTRIBUTE_DATASET = "urn:jabiz:dataset:me:" + ATTRIBUTE;
    public static final String CRITERION_DATASET = "urn:jabiz:dataset:default:" + CRITERION;
    public static final String SPONSOR_CRITERION_DATASET = "urn:jabiz:dataset:sponsor:" + CRITERION;

    /** How many rows one save writes at most: every option of every criterion. */
    public static final int MAX_ROWS = 200;

    public static final EntityDefinition ATTRIBUTE_ENTITY = EntityDefinition.define(ATTRIBUTE, eb -> {
        eb.physicalTable("qb_profile_attribute");
        eb.primaryKey("attributeId");
        eb.field("attributeId", f -> f.physicalColumn("attribute_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:profile-attribute"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        eb.field("criterionKey", f -> f.physicalColumn("criterion_key").immutable(true).required(true).asText(32));
        // What a taker told stays out of the audit trail (it is cleared at will, D-Q39-9); the key is enough there.
        eb.field("valueCode", f -> f.physicalColumn("value_code").immutable(true).auditMasked().asText(32));
        eb.field("valueNumber", f -> f.physicalColumn("value_number").immutable(true).auditMasked()
            .asNumeric(6, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_profile_attribute", "userId", "criterionKey", "valueCode");
        eb.listView("default", lv -> lv
            .columns("userId", "criterionKey", "valueCode", "valueNumber")
            .filters("userId", "criterionKey", "valueCode")
            .sorts("criterionKey", "valueCode")
            .defaultSort("criterionKey", true));
    });

    public static final EntityDefinition CRITERION_ENTITY = EntityDefinition.define(CRITERION, eb -> {
        eb.physicalTable("qb_publication_criterion");
        eb.primaryKey("criterionId");
        eb.field("criterionId", f -> f.physicalColumn("criterion_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:publication-criterion"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        PublisherKind.declare(eb, false);
        eb.field("criterionKey", f -> f.physicalColumn("criterion_key").immutable(true).required(true).asText(32));
        eb.field("valueCode", f -> f.physicalColumn("value_code").immutable(true).asText(32));
        eb.field("minValue", f -> f.physicalColumn("min_value").immutable(true).asNumeric(6, 0));
        eb.field("maxValue", f -> f.physicalColumn("max_value").immutable(true).asNumeric(6, 0));
        eb.field("measure", f -> f.physicalColumn("measure").immutable(true).asText(12));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_publication_criterion", "publicationId", "criterionKey", "valueCode");
        eb.listView("default", lv -> lv
            .columns("publicationId", "criterionKey", "valueCode", "minValue", "maxValue")
            .filters("publicationId", "publisherKind", "publisherId", "criterionKey")
            .sorts("criterionKey", "valueCode")
            .defaultSort("criterionKey", true));
    });

    private QbAudience() {}
}
