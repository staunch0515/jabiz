package com.jabiz.quizbuks.block;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * A taker's blocks (F-S8, docs/quizbuks/plans/impl/F-S8.md): {@value #BLOCK}, one row a blocked object of a taker's
 * (a publication, a quiz, a sponsor or a video provider, {@link BlockRules}). An ordinary table written by the two
 * processes only ({@code processOnlyWrites}), every change audited by the platform. Private: no role reads the default
 * dataset; the taker reads their own through {@link #MY_DATASET} in templates, the sponsors and headquarters only
 * rounded or aggregated counts through theirs.
 */
public final class QbBlocks {

    public static final String BLOCK = "QbBlock";
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + BLOCK;
    /** The caller's own blocks, for the takers' templates (the feed, the page, the inbox, the list). */
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + BLOCK;
    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:block-kind";

    /** A taker's lock: their blocks change one after another (the limit is counted under it). */
    public static String lockName(Object userId) {
        return "qb.block:" + userId;
    }

    public static final EntityDefinition ENTITY = EntityDefinition.define(BLOCK, eb -> {
        eb.physicalTable("qb_block");
        eb.primaryKey("blockId");
        eb.field("blockId", f -> f.physicalColumn("block_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:block"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).processOnly()
            .asText(BlockRules.ID_LENGTH));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).processOnly()
            .asCode(KIND_DICTIONARY, BlockRules.KINDS.toArray(String[]::new)));
        // The object, taken from the card the taker blocked from: the publication, the card's quiz, its publisher or
        // its shared video's provider.
        eb.field("objectId", f -> f.physicalColumn("object_id").immutable(true).required(true).processOnly()
            .asText(BlockRules.ID_LENGTH));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("userId", "kind", "objectId", "createdAt")
            .filters("userId", "kind", "objectId", "createdAt")
            .sorts("createdAt")
            .defaultSort("createdAt", false));
    });

    private QbBlocks() {}
}
