package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.EntityDefinition;

/**
 * A publication's prerequisites (Q76, docs/quizbuks/plans/Q76-prerequisites.md): the sponsor's own rule that a taker
 * first passes ({@link #PASSED}) or answers ({@link #ANSWERED}) another of the sponsor's publications. An ordinary
 * table written by {@link PublicationPrerequisites#SAVE} while the publication is a draft, frozen once it is submitted,
 * copied with it. What meets one is {@link com.jabiz.quizbuks.play.PrerequisiteCheck}'s.
 */
public final class QbPrerequisites {

    public static final String PREREQUISITE = "QbPublicationPrerequisite";

    public static final String DATASET = "urn:jabiz:dataset:default:" + PREREQUISITE;
    public static final String SPONSOR_DATASET = "urn:jabiz:dataset:sponsor:" + PREREQUISITE;
    /** Which publication requires which, and how: ids only, for the sponsor's page (D-Q76-8). */
    public static final String PUBLIC_DATASET = "urn:jabiz:dataset:public:" + PREREQUISITE;

    public static final String PASSED = "PASSED";
    public static final String ANSWERED = "ANSWERED";

    /** How many prerequisites a publication has at most. */
    public static final int MAX_PREREQUISITES = 5;
    /** How long a chain of prerequisites may be, from a publication to the first one without any. */
    public static final int MAX_DEPTH = 20;
    /** How many of a sponsor's prerequisite rows a save reads for the cycle check. */
    public static final int MAX_SPONSOR_ROWS = 10_000;

    public static final EntityDefinition ENTITY = EntityDefinition.define(PREREQUISITE, eb -> {
        eb.physicalTable("qb_publication_prerequisite");
        eb.primaryKey("prerequisiteId");
        eb.field("prerequisiteId", f -> f.physicalColumn("prerequisite_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:publication-prerequisite"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        PublisherKind.declare(eb, false);
        eb.field("requiredPublicationId", f -> f.physicalColumn("required_publication_id").immutable(true)
            .required(true).asReference(QbPublications.PUBLICATION));
        eb.field("requirement", f -> f.physicalColumn("requirement").immutable(true).required(true).asText(10));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_publication_prerequisite", "publicationId", "requiredPublicationId");
        eb.listView("default", lv -> lv
            .columns("publicationId", "requiredPublicationId", "requirement")
            .filters("publicationId", "requiredPublicationId", "publisherKind", "publisherId", "requirement")
            .sorts("publicationId")
            .defaultSort("publicationId", true));
    });

    private QbPrerequisites() {}
}
