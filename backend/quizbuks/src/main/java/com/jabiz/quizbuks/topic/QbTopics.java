package com.jabiz.quizbuks.topic;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.quizbuks.content.QbContent;

/**
 * The platform's word list of topics (F-S9, docs/quizbuks/plans/impl/F-S9.md): {@value #TOPIC}, one row a word, named
 * by its code and shown by its name in each of the platform's languages, kept by headquarters with the
 * {@code QB_TOPIC_*} processes (a new word needs no change of code). A word is never deleted: deactivated, it stays
 * where it was chosen and is no longer offered; merged into another, it counts as that one wherever words are compared
 * (strict mode, statistics) and is shown by that one's name. An ordinary table written by processes only
 * ({@code processOnlyWrites}): every change goes through the platform's write path and its audit.
 */
public final class QbTopics {

    public static final String TOPIC = "QbTopic";
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + TOPIC;

    /** A word's lock: its changes one after another (a merge takes both words', in the codes' order). */
    public static String lockName(Object code) {
        return "qb.topic:" + code;
    }

    public static final EntityDefinition ENTITY = EntityDefinition.define(TOPIC, eb -> {
        eb.physicalTable("qb_topic");
        eb.primaryKey("topicId");
        // The code headquarters gives it (TopicRules.CODE): what quizzes, publications and takers hold.
        eb.field("topicId", f -> f.physicalColumn("topic_id").immutable(true).required(true)
            .asText(TopicRules.CODE_LENGTH));
        // Every language of the platform is required (the startup check TopicCheck reports a row without one).
        eb.field("name", f -> f.physicalColumn("name").required(true)
            .apply(I18nText.of(TopicRules.NAME_LENGTH).required(QbContent.LANGUAGES.toArray(String[]::new))));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        // The word it was merged into (always one not merged itself: a merge moves those merged into it along).
        eb.field("mergedInto", f -> f.physicalColumn("merged_into").asText(TopicRules.CODE_LENGTH));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("changedAt", f -> f.physicalColumn("changed_at").required(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.display("name");
        eb.listView("default", lv -> lv
            .columns("topicId", "name", "active", "mergedInto", "createdAt", "changedAt")
            .filters("topicId", "active", "mergedInto")
            .sorts("topicId", "createdAt", "changedAt")
            .defaultSort("topicId", true));
    });

    private QbTopics() {
    }
}
