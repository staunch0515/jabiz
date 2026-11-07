package com.jabiz.quizbuks.conversation;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import com.jabiz.quizbuks.publishing.PublisherKind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;
import java.util.Map;

/**
 * Registers the conversations (Q82): the three entities, their default datasets (written by the processes only; read
 * by headquarters' content administrators and the operators, an operator's session its own sponsors'), the taker's
 * and the sponsor's own conversations for their templates (read only, never directly: the read permission is one no
 * role holds), the dictionaries, the processes and the daily expiry.
 */
@Configuration
public class ConversationConfig {

    static final String TAKER_CONVERSATIONS = "urn:jabiz:dataset:taker:" + QbConversations.CONVERSATION;
    static final String SPONSOR_CONVERSATIONS = "urn:jabiz:dataset:sponsor:" + QbConversations.CONVERSATION;
    static final String TAKER_BLOCKS = "urn:jabiz:dataset:taker:" + QbConversations.BLOCK;

    private final String poolRef;

    ConversationConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbConversationEntity() {
        return QbConversations.CONVERSATION_ENTITY;
    }

    @Bean
    EntityDefinition qbConversationMessageEntity() {
        return QbConversations.MESSAGE_ENTITY;
    }

    @Bean
    EntityDefinition qbConversationBlockEntity() {
        return QbConversations.BLOCK_ENTITY;
    }

    private DatasetDefinition defaultDataset(String entity) {
        return DatasetDefinition.define(QbConversations.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.CONVERSATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(entity))
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** One side's own rows, for its templates only (no role reads the dataset itself). */
    private DatasetDefinition own(String id, String entity, String field) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.CONVERSATION_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> {
                s.fromContext(field, "actorId", RequestContext::actorId);
                if (PublisherKind.ID_FIELD.equals(field)) {
                    s.fixed(PublisherKind.KIND_FIELD, PublisherKind.SPONSOR);
                }
            }));
    }

    @Bean
    DatasetDefinition qbConversationDataset() {
        return defaultDataset(QbConversations.CONVERSATION);
    }

    @Bean
    DatasetDefinition qbConversationMessageDataset() {
        return defaultDataset(QbConversations.MESSAGE);
    }

    @Bean
    DatasetDefinition qbConversationBlockDataset() {
        return defaultDataset(QbConversations.BLOCK);
    }

    @Bean
    DatasetDefinition qbTakerConversationDataset() {
        return own(TAKER_CONVERSATIONS, QbConversations.CONVERSATION, "takerId");
    }

    @Bean
    DatasetDefinition qbSponsorConversationDataset() {
        return own(SPONSOR_CONVERSATIONS, QbConversations.CONVERSATION, PublisherKind.ID_FIELD);
    }

    @Bean
    DatasetDefinition qbTakerConversationBlockDataset() {
        return own(TAKER_BLOCKS, QbConversations.BLOCK, "takerId");
    }

    @Bean
    StaticDictionary qbConversationStatusDictionary() {
        return StaticDictionary.define(QbConversations.STATUS_DICTIONARY, d -> d
            .item(QbConversations.OPEN, "en", "Open", "zh", "进行中", "ja", "受付中")
            .item(QbConversations.CLOSED, "en", "Closed", "zh", "已关闭", "ja", "終了"));
    }

    @Bean
    StaticDictionary qbConversationSideDictionary() {
        return StaticDictionary.define(QbConversations.SIDE_DICTIONARY, d -> d
            .item(QbConversations.TAKER, "en", "Taker", "zh", "学者", "ja", "テイカー")
            .item(QbConversations.SPONSOR, "en", "Sponsor", "zh", "发起方", "ja", "主催者"));
    }

    @Bean
    StaticDictionary qbConversationCloserDictionary() {
        return StaticDictionary.define(QbConversations.CLOSER_DICTIONARY, d -> d
            .item(QbConversations.TAKER, "en", "Taker", "zh", "学者", "ja", "テイカー")
            .item(QbConversations.SPONSOR, "en", "Sponsor", "zh", "发起方", "ja", "主催者")
            .item(QbConversations.SYSTEM, "en", "No messages for a long time", "zh", "长时间无消息",
                "ja", "長期間メッセージなし"));
    }

    @Bean
    ProcessDefinition<ConversationProcesses.OpenInput, ConversationProcesses.MessageOutput, ProcessContext>
        qbConversationOpen() {
        return ConversationProcesses.open();
    }

    @Bean
    ProcessDefinition<ConversationProcesses.SendInput, ConversationProcesses.MessageOutput, ProcessContext>
        qbConversationSend() {
        return ConversationProcesses.send();
    }

    @Bean
    ProcessDefinition<ConversationProcesses.ConversationRef, ConversationProcesses.StatusOutput, ProcessContext>
        qbConversationClose() {
        return ConversationProcesses.close();
    }

    @Bean
    ProcessDefinition<ConversationProcesses.ConversationRef, ConversationProcesses.StatusOutput, ProcessContext>
        qbConversationRead() {
        return ConversationProcesses.read();
    }

    @Bean
    ProcessDefinition<ConversationProcesses.ConversationRef, ConversationProcesses.BlockOutput, ProcessContext>
        qbConversationBlock() {
        return ConversationProcesses.block();
    }

    @Bean
    ProcessDefinition<ConversationProcesses.UnblockInput, ConversationProcesses.BlockOutput, ProcessContext>
        qbConversationUnblock() {
        return ConversationProcesses.unblock();
    }

    @Bean
    ProcessDefinition<ConversationProcesses.ConversationRef, ConversationProcesses.StatusOutput, ProcessContext>
        qbConversationExpire() {
        return ConversationProcesses.expire();
    }

    /** Daily, at an odd minute: each conversation due is closed in its own transaction (decision D42). */
    @Bean
    JobDefinition<ConversationProcesses.ConversationRef> qbConversationExpireJob(
        @Value("${quizbuks.conversations.expire-cron:0 41 3 * * *}") String cron,
        ProcessDefinition<ConversationProcesses.ConversationRef, ConversationProcesses.StatusOutput, ProcessContext>
            qbConversationExpire) {
        return JobDefinition.forEach(QbConversations.EXPIRE_JOB, cron, ZoneOffset.UTC,
                JobItems.template(QbConversations.DUE_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "conversationId"),
                qbConversationExpire, (scheduledTime, row) -> new ConversationProcesses.ConversationRef(
                    String.valueOf(row.get("conversationId"))))
            .maxItems(5_000);
    }
}
