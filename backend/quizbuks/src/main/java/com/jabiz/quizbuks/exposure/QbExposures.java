package com.jabiz.quizbuks.exposure;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * A taker's exposures (F-S13, docs/quizbuks/plans/impl/F-S13.md): {@value #EXPOSURE}, one row per publication and taker
 * (unique), made by the first report about it: when a push of it was opened in the vertical mode, when its exposure
 * counted (where, how long, timed how), when the taker went on to answer, and how often the daily cap refused it. An
 * ordinary table written by {@code QB_EXPOSURE_REPORT} only ({@code processOnlyWrites}), never under a publication's,
 * start or wallet lock. It holds ids only: it stays when a taker's account is deleted, as the attempts and the views do
 * (platform decision D62 item 4).
 */
public final class QbExposures {

    public static final String EXPOSURE = "QbExposure";
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + EXPOSURE;
    /** The caller's own rows, for the feed's pushes (the ones they opened). */
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + EXPOSURE;

    public static final int ID_LENGTH = 128;

    /** A taker's lock: their reports are handled one after another (the daily cap is counted under it). */
    public static String lockName(Object userId) {
        return "qb.exposure:" + userId;
    }

    public static final EntityDefinition ENTITY = EntityDefinition.define(EXPOSURE, eb -> {
        eb.physicalTable("qb_exposure");
        eb.primaryKey("exposureId");
        eb.field("exposureId", f -> f.physicalColumn("exposure_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:exposure"));
        // Text, as a block's object: no reference check per insert (the card was read in the same process).
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .processOnly().asText(36));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).processOnly()
            .asText(ID_LENGTH));
        // The card's publisher and its shared video's provider, copied: the sponsors' figures and the operators'
        // scope go by them without a join.
        eb.field("publisherId", f -> f.physicalColumn("publisher_id").immutable(true).required(true).processOnly()
            .asText(ID_LENGTH));
        eb.field("providerId", f -> f.physicalColumn("provider_id").immutable(true).processOnly().asText(ID_LENGTH));
        eb.field("openedAt", f -> f.physicalColumn("opened_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("countedAt", f -> f.physicalColumn("counted_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("surface", f -> f.physicalColumn("surface").processOnly().asText(10));
        eb.field("countedSec", f -> f.physicalColumn("counted_sec").processOnly().asNumeric(6, 0));
        eb.field("timer", f -> f.physicalColumn("timer").processOnly().asText(10));
        eb.field("wentToAnswerAt", f -> f.physicalColumn("went_to_answer_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // How many UTC days the daily cap refused its exposure (once a day), and the last of them.
        eb.field("cappedDays", f -> f.physicalColumn("capped_days").required(true).processOnly().asNumeric(6, 0));
        eb.field("lastCappedOn", f -> f.physicalColumn("last_capped_on").processOnly().asDate());
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("publicationId", "userId", "openedAt", "countedAt", "surface", "cappedDays", "createdAt")
            .filters("publicationId", "userId", "publisherId", "countedAt", "surface")
            .sorts("createdAt", "countedAt")
            .defaultSort("createdAt", false));
    });

    private QbExposures() {}
}
