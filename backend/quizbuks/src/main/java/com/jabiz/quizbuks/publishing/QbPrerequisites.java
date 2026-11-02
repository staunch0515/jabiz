package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.EntityDefinition;

import java.util.List;

/**
 * A publication's prerequisites (Q76, docs/quizbuks/plans/Q76-prerequisites.md): the other publications of the same
 * sponsor a taker must first have passed, or answered, before starting it. The edges of the sponsor's "tech tree"
 * (D-Q76-1): a rule the sponsor chooses, like the matching conditions (Q47), frozen with the publication once it is
 * submitted. An ordinary table written by {@link PublicationPrerequisites} and the copy of a publication only.
 */
public final class QbPrerequisites {

    public static final String PREREQUISITE = "QbPublicationPrerequisite";
    public static final String DEFAULT_DATASET = QbPublications.defaultDataset(PREREQUISITE);
    /** The sponsor's own publications' prerequisites, read only. */
    public static final String SPONSOR_DATASET = QbPublications.sponsorDataset(PREREQUISITE);
    /** Live public publications' prerequisites, for the sponsor's page (D-Q76-8); the template projects the whitelist. */
    public static final String PUBLIC_DATASET = "urn:jabiz:dataset:public:" + PREREQUISITE;

    /** What the taker must have done on the required publication (D-Q76-1). */
    public static final String PASSED = "PASSED";
    public static final String ANSWERED = "ANSWERED";
    public static final List<String> REQUIREMENTS = List.of(PASSED, ANSWERED);
    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:prerequisite-requirement";

    /** A publication requires at most this many others (D-Q76-1). */
    public static final int MAX_PREREQUISITES = 5;
    /** How far the cycle check walks a sponsor's chain (D-Q76-4). */
    public static final int MAX_DEPTH = 20;
    /** The most rows a process reads of a sponsor's edges or a publication's prerequisites. */
    public static final int MAX_ROWS = 2_000;

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
        eb.field("requirement", f -> f.physicalColumn("requirement").immutable(true).required(true)
            .asCode(DICTIONARY, PASSED, ANSWERED));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_publication_prerequisite", "publicationId", "requiredPublicationId");
        eb.listView("default", lv -> lv
            .columns("publicationId", "requiredPublicationId", "requirement")
            .filters("publicationId", "publisherKind", "publisherId", "requiredPublicationId")
            .sorts("requiredPublicationId")
            .defaultSort("requiredPublicationId", true));
    });

    private QbPrerequisites() {}
}
