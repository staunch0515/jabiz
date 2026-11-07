package com.jabiz.quizbuks.region;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * The entities of the rule on foreign videos and mainland China (F-S11, docs/quizbuks/plans/impl/F-S11.md):
 * <ul>
 *   <li>{@value #EXCEPTION}: headquarters allows mainland China to answer one publication, or all of one sponsor's,
 *       although their videos are foreign (A3-15), with a reason and perhaps a last day; revoked, never deleted;</li>
 *   <li>{@value #REFUSAL_DAY}: how many starts the rule refused on a UTC day (only the count).</li>
 * </ul>
 * Both are ordinary tables written by processes only ({@code processOnlyWrites}): every change goes through the
 * platform's write path and its audit.
 */
public final class QbRegion {

    public static final String EXCEPTION = "QbRegionException";
    public static final String REFUSAL_DAY = "QbRegionRefusalDay";
    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:region-exception-kind";

    /** An exception for one publication. */
    public static final String PUBLICATION = "PUBLICATION";
    /** An exception for every publication of one sponsor, present and future. */
    public static final String SPONSOR = "SPONSOR";
    public static final List<String> KINDS = List.of(PUBLICATION, SPONSOR);

    public static final int REASON_LENGTH = 500;

    /**
     * The exceptions as the public templates read them (a sponsor's mini homepage, Q60a): which publications and
     * sponsors have one in force and until when, never why or who.
     */
    public static final String PUBLIC_EXCEPTIONS = "urn:jabiz:dataset:public:" + EXCEPTION;

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    public static final EntityDefinition EXCEPTION_ENTITY = EntityDefinition.define(EXCEPTION, eb -> {
        eb.physicalTable("qb_region_exception");
        eb.primaryKey("exceptionId");
        eb.field("exceptionId", f -> f.physicalColumn("exception_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:region-exception"));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(KIND_DICTIONARY, KINDS.toArray(String[]::new)));
        // As text, not a reference: an exception may name a publication before it goes on (its card is then made).
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).asText(36));
        // The sponsor's user id (the publisher id of a sponsor's publications).
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).asText(128));
        eb.field("reason", f -> f.physicalColumn("reason").required(true).asText(REASON_LENGTH));
        // The last day it counts (UTC, inclusive); none: until revoked.
        eb.field("expiresOn", f -> f.physicalColumn("expires_on").asDate());
        eb.field("revoked", f -> f.physicalColumn("revoked").required(true).asBool());
        eb.field("revokeReason", f -> f.physicalColumn("revoke_reason").asText(REASON_LENGTH));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("createdBy", f -> f.physicalColumn("created_by").immutable(true).required(true).asText(128));
        eb.field("changedAt", f -> f.physicalColumn("changed_at").required(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("changedBy", f -> f.physicalColumn("changed_by").required(true).asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("kind", "publicationId", "sponsorId", "reason", "expiresOn", "revoked", "createdAt", "createdBy")
            .filters("kind", "publicationId", "sponsorId", "revoked", "expiresOn")
            .sorts("createdAt", "expiresOn")
            .defaultSort("createdAt", false));
    });

    public static final EntityDefinition REFUSAL_DAY_ENTITY = EntityDefinition.define(REFUSAL_DAY, eb -> {
        eb.physicalTable("qb_region_refusal_day");
        eb.primaryKey("refusalId");
        eb.field("refusalId", f -> f.physicalColumn("refusal_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:region-refusal-day"));
        eb.field("day", f -> f.physicalColumn("day").immutable(true).required(true).asDate());
        eb.field("refused", f -> f.physicalColumn("refused").required(true).asNumeric(12, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_region_refusal_day", "day");
        eb.listView("default", lv -> lv
            .columns("day", "refused")
            .filters("day")
            .sorts("day")
            .defaultSort("day", false));
    });

    private QbRegion() {}
}
