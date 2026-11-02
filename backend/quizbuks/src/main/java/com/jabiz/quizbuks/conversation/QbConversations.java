package com.jabiz.quizbuks.conversation;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;

import java.util.List;

/**
 * Conversations between a taker and the sponsor of a publication (Q82, docs/quizbuks/plans/Q82-conversations.md):
 * <ul>
 *   <li>{@code QbConversation}: one per taker and publication (D-Q82-1), opened by the taker only, about what came
 *       of that publication for them (the result, the prize, a referral). It records who wrote last and when each
 *       side last read it, and whether it is closed (by either side, or by the daily expiry);</li>
 *   <li>{@code QbConversationMessage}: a message, plain text, only inserted (D-Q82-2). Who sent it is a side; the
 *       user is kept for the platform and never shown to the other side (D-Q82-8);</li>
 *   <li>{@code QbConversationBlock}: a sponsor a taker blocked (D-Q82-6): no conversation with them opens, and none
 *       of theirs takes a message, until the taker removes it.</li>
 * </ul>
 * Ordinary tables written by {@link ConversationProcesses} only; their default datasets are read by headquarters'
 * content administrators and the operators (their own sponsors'), the two sides only through their templates.
 */
public final class QbConversations {

    public static final String CONVERSATION = "QbConversation";
    public static final String MESSAGE = "QbConversationMessage";
    public static final String BLOCK = "QbConversationBlock";

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:conversation-status";
    public static final String SIDE_DICTIONARY = "urn:jabiz:dict:quizbuks:conversation-side";
    public static final String CLOSER_DICTIONARY = "urn:jabiz:dict:quizbuks:conversation-closer";

    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";
    public static final List<String> STATUSES = List.of(OPEN, CLOSED);

    /** The two sides; who closed a conversation is one of them or the expiry. */
    public static final String TAKER = "TAKER";
    public static final String SPONSOR = "SPONSOR";
    public static final String SYSTEM = "SYSTEM";
    public static final List<String> SIDES = List.of(TAKER, SPONSOR);
    public static final List<String> CLOSERS = List.of(TAKER, SPONSOR, SYSTEM);

    /** The job that closes the conversations without a message for {@code qb.conversation.close-days} days. */
    public static final String EXPIRE_JOB = "qb.conversation-expire";
    public static final String DUE_TEMPLATE = "qb.system.conversations-due";

    /** The event of a sponsor's reply: the taker's message in the inbox and the push (D-Q82-9). */
    public static final String MESSAGE_EVENT = "qb.conversation.message";

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /**
     * One conversation's writes take turns (open, send, close, read, expire). Named by its taker and publication,
     * which never change: opening finds no conversation yet, and two first messages still make one.
     */
    public static String lockName(Object takerId, Object publicationId) {
        return "qb.conversation:" + takerId + ":" + publicationId;
    }

    public static final EntityDefinition CONVERSATION_ENTITY = EntityDefinition.define(CONVERSATION, eb -> {
        eb.physicalTable("qb_conversation");
        eb.primaryKey("conversationId");
        eb.field("conversationId", f -> f.physicalColumn("conversation_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:conversation"));
        PublisherKind.declare(eb, true);
        eb.field("takerId", f -> f.physicalColumn("taker_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .processOnly().asReference(QbPublications.PUBLICATION));
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").immutable(true).processOnly().asText(36));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("openedAt", f -> f.physicalColumn("opened_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("lastMessageAt", f -> f.physicalColumn("last_message_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("lastSender", f -> f.physicalColumn("last_sender").required(true).processOnly()
            .asCode(SIDE_DICTIONARY, SIDES.toArray(String[]::new)));
        eb.field("takerReadAt", f -> f.physicalColumn("taker_read_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("sponsorReadAt", f -> f.physicalColumn("sponsor_read_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("closedAt", f -> f.physicalColumn("closed_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("closedBy", f -> f.physicalColumn("closed_by").processOnly()
            .asCode(CLOSER_DICTIONARY, CLOSERS.toArray(String[]::new)));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("publicationId", "status", "openedAt", "lastMessageAt", "lastSender", "closedAt", "closedBy")
            .filters("publicationId", "publisherId", "takerId", "status", "lastMessageAt")
            .sorts("lastMessageAt", "openedAt")
            .defaultSort("lastMessageAt", false));
    });

    public static final EntityDefinition MESSAGE_ENTITY = EntityDefinition.define(MESSAGE, eb -> {
        eb.physicalTable("qb_conversation_message");
        eb.primaryKey("messageId");
        eb.field("messageId", f -> f.physicalColumn("message_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:conversation-message"));
        eb.field("conversationId", f -> f.physicalColumn("conversation_id").immutable(true).required(true)
            .processOnly().asText(36));
        PublisherKind.declare(eb, true);
        eb.field("sender", f -> f.physicalColumn("sender").immutable(true).required(true).processOnly()
            .asCode(SIDE_DICTIONARY, SIDES.toArray(String[]::new)));
        eb.field("senderUserId", f -> f.physicalColumn("sender_user_id").immutable(true).required(true)
            .processOnly().asText(128));
        // Emptied when the sender's account is erased (D-Q82-11); otherwise written once.
        eb.field("body", f -> f.physicalColumn("body").processOnly().asText(ConversationRules.BODY_LENGTH, true));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("conversationId", "sender", "senderUserId", "body", "createdAt")
            .filters("conversationId", "publisherId", "sender", "createdAt")
            .sorts("createdAt")
            .defaultSort("createdAt", true));
    });

    public static final EntityDefinition BLOCK_ENTITY = EntityDefinition.define(BLOCK, eb -> {
        eb.physicalTable("qb_conversation_block");
        eb.primaryKey("blockId");
        eb.field("blockId", f -> f.physicalColumn("block_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:conversation-block"));
        eb.field("takerId", f -> f.physicalColumn("taker_id").immutable(true).required(true).processOnly()
            .asText(128));
        PublisherKind.declare(eb, true);
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("takerId", "publisherId", "createdAt")
            .filters("takerId", "publisherId", "createdAt")
            .sorts("createdAt")
            .defaultSort("createdAt", false));
    });

    private QbConversations() {}
}
