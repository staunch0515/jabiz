package com.jabiz.quizbuks.play;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.QbWallets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;

/**
 * Registers answering (docs/quizbuks/plans/Q5-answering.md): the three entities, their datasets, the two
 * dictionaries, the processes, the statistics job and the subscription that continues paying pending rewards.
 *
 * <p>Each entity has a default dataset, read only by administrators and written by the processes only
 * ({@code processOnlyWrites}; its write permission is one no role holds). A taker reads their own attempts through the
 * taker dataset ({@code userId} = the caller), read only; cards, counters, statistics and wallets only through the
 * templates.
 */
@Configuration
public class PlayConfig {

    private final String poolRef;

    PlayConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    private DatasetDefinition defaultDataset(String entity, String readPermission, int maxWriteBatch) {
        return DatasetDefinition.define(QbPlay.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(readPermission, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(maxWriteBatch))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    EntityDefinition qbAttemptEntity() {
        return QbPlay.ATTEMPT_ENTITY;
    }

    @Bean
    EntityDefinition qbPublicationViewEntity() {
        return QbPlay.VIEW_ENTITY;
    }

    @Bean
    EntityDefinition qbPublicationStatsEntity() {
        return QbPlay.STATS_ENTITY;
    }

    /** Support reads every attempt with the users (P-01). */
    @Bean
    DatasetDefinition qbAttemptDataset() {
        return defaultDataset(QbPlay.ATTEMPT, QbPermissions.ADMIN_USERS_READ, 100);
    }

    @Bean
    DatasetDefinition qbPublicationViewDataset() {
        return defaultDataset(QbPlay.VIEW, QbPermissions.ADMIN_PUBLICATION_READ, 100);
    }

    @Bean
    DatasetDefinition qbPublicationStatsDataset() {
        return defaultDataset(QbPlay.STATS, QbPermissions.ADMIN_PUBLICATION_READ, PlayLimits.STATS_BATCH);
    }

    /**
     * A taker's own attempts, read only and without history. A finished attempt's result (with the correct answers)
     * is written only when it finishes, so an attempt in progress shows none.
     */
    @Bean
    DatasetDefinition qbTakerAttemptDataset() {
        return DatasetDefinition.define(QbPlay.TAKER_ATTEMPTS, d -> d
            .targetEntityType(QbPlay.ATTEMPT)
            .permissions(QbPermissions.ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    @Bean
    StaticDictionary qbAttemptStatusDictionary() {
        return StaticDictionary.define(QbPlay.ATTEMPT_STATUS_DICTIONARY, d -> d
            .item(AttemptStatus.IN_PROGRESS, "en", "In progress", "zh", "进行中", "ja", "回答中")
            .item(AttemptStatus.SUBMITTED, "en", "Submitted", "zh", "已提交", "ja", "提出済み")
            .item(AttemptStatus.EXPIRED, "en", "Submitted at the time limit", "zh", "超时自动提交",
                "ja", "時間切れで自動提出"));
    }

    @Bean
    StaticDictionary qbRewardStatusDictionary() {
        return StaticDictionary.define(QbPlay.REWARD_STATUS_DICTIONARY, d -> d
            .item(RewardStatus.NONE, "en", "No reward", "zh", "无奖励", "ja", "報酬なし")
            .item(RewardStatus.PAID, "en", "Paid", "zh", "已到账", "ja", "支払済み")
            .item(RewardStatus.PENDING, "en", "Pending", "zh", "待支付", "ja", "支払待ち"));
    }

    @Bean
    ProcessDefinition<AttemptProcesses.PublicationRef, AttemptProcesses.ViewOutput, ProcessContext> qbView() {
        return AttemptProcesses.view();
    }

    @Bean
    ProcessDefinition<AttemptProcesses.PublicationRef, AttemptProcesses.StartOutput, ProcessContext>
        qbAttemptStart() {
        return AttemptProcesses.start();
    }

    @Bean
    ProcessDefinition<AttemptProcesses.SaveInput, AttemptProcesses.SaveOutput, ProcessContext> qbAttemptSave() {
        return AttemptProcesses.save();
    }

    @Bean
    ProcessDefinition<AttemptFinishing.SubmitInput, AttemptFinishing.SubmitOutput, ProcessContext> qbAttemptSubmit(
        MessageCatalog messages) {
        return AttemptFinishing.submit(messages);
    }

    @Bean
    ProcessDefinition<AttemptFinishing.ExpireInput, AttemptFinishing.ExpireOutput, ProcessContext> qbAttemptExpire(
        MessageCatalog messages) {
        return AttemptFinishing.expire(messages);
    }

    @Bean
    ProcessDefinition<QbWallets.SponsorRef, PendingSettlement.SettleOutput, ProcessContext> qbPendingSettle() {
        return SETTLE;
    }

    @Bean
    ProcessDefinition<PendingSettlement.PayInput, PendingSettlement.PayOutput, ProcessContext> qbPendingPay() {
        return PendingSettlement.pay();
    }

    @Bean
    ProcessDefinition<PublicationStats.RefreshInput, PublicationStats.RefreshOutput, ProcessContext>
        qbPublicationStatsRefresh() {
        return STATS;
    }

    private static final ProcessDefinition<QbWallets.SponsorRef, PendingSettlement.SettleOutput, ProcessContext>
        SETTLE = PendingSettlement.settle();

    private static final ProcessDefinition<PublicationStats.RefreshInput, PublicationStats.RefreshOutput,
        ProcessContext> STATS = PublicationStats.refresh();

    /** The next batch of pending rewards, in a transaction of its own (D-Q5-5). */
    @Bean
    EventSubscription<QbWallets.SponsorRef> qbPendingSettleSubscription() {
        return EventSubscription.of(PendingSettlement.SUBSCRIPTION, PendingSettlement.MORE_EVENT, SETTLE,
            PendingSettlement::input);
    }

    /** Every ten minutes (U-22, requirement 6.1); a missed run is made up by the next, which counts from scratch. */
    @Bean
    JobDefinition<PublicationStats.RefreshInput> qbPublicationStatsJob() {
        return JobDefinition.cron(PublicationStats.JOB, "0 */10 * * * *", ZoneOffset.UTC, STATS,
            PublicationStats.RefreshInput::new);
    }
}
