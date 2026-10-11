package com.jabiz.quizbuks.publishing;

import com.jabiz.approval.ApprovalSubject;
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
import com.jabiz.runtime.approval.ApprovalProcesses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;

/**
 * Registers publishing (docs/quizbuks/plans/Q4-publishing.md): the four entities, their datasets, the two
 * dictionaries, the approval subject, the ten processes, the minute's job and the two subscriptions to the approval
 * events.
 *
 * <p>Each entity has a default dataset, read only by administrators ({@code qb.admin.publication.read}) and written
 * by the processes only ({@code processOnlyWrites}; its write permission is one no role holds). A sponsor reads their own publications and tiers through the
 * sponsor datasets ({@code sponsorId} = the caller), read only and without history; counters through the templates.
 */
@Configuration
public class PublishingConfig {

    private final String poolRef;

    PublishingConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    private DatasetDefinition defaultDataset(String entity, int maxWriteBatch) {
        return DatasetDefinition.define(QbPublications.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(maxWriteBatch))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    private DatasetDefinition sponsorDataset(String entity) {
        // Read only: the write permission is one no role holds, so making it writable later grants nothing.
        return DatasetDefinition.define(QbPublications.sponsorDataset(entity), d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.PUBLICATION_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    @Bean
    EntityDefinition qbPublicationEntity() {
        return QbPublications.PUBLICATION_ENTITY;
    }

    @Bean
    EntityDefinition qbRewardTierEntity() {
        return QbPublications.TIER_ENTITY;
    }

    @Bean
    EntityDefinition qbPublicationCounterEntity() {
        return QbPublications.COUNTER_ENTITY;
    }

    @Bean
    DatasetDefinition qbPublicationDataset() {
        return defaultDataset(QbPublications.PUBLICATION, 100);
    }

    @Bean
    DatasetDefinition qbRewardTierDataset() {
        // Saving replaces at most every tier: as many deletes and inserts.
        return defaultDataset(QbPublications.TIER, 2 * PublishingLimits.MAX_TIERS);
    }

    @Bean
    DatasetDefinition qbPublicationCounterDataset() {
        return defaultDataset(QbPublications.COUNTER, 100);
    }

    @Bean
    EntityDefinition qbPublicationScheduleEntity() {
        return QbPublications.SCHEDULE_ENTITY;
    }

    @Bean
    DatasetDefinition qbPublicationScheduleDataset() {
        return defaultDataset(QbPublications.SCHEDULE, 100);
    }

    @Bean
    DatasetDefinition qbSponsorPublicationDataset() {
        return sponsorDataset(QbPublications.PUBLICATION);
    }

    @Bean
    DatasetDefinition qbSponsorRewardTierDataset() {
        return sponsorDataset(QbPublications.TIER);
    }

    @Bean
    StaticDictionary qbPublicationStatusDictionary() {
        return StaticDictionary.define(QbPublications.STATUS_DICTIONARY, d -> d
            .item(PublicationStatus.DRAFT, "en", "Draft", "zh", "草稿", "ja", "下書き")
            .item(PublicationStatus.PENDING_REVIEW, "en", "In review", "zh", "待审核", "ja", "審査待ち")
            .item(PublicationStatus.SCHEDULED, "en", "Scheduled", "zh", "待开始", "ja", "開始待ち")
            .item(PublicationStatus.LIVE, "en", "Live", "zh", "进行中", "ja", "公開中")
            .item(PublicationStatus.PAUSED, "en", "Paused", "zh", "已暂停", "ja", "一時停止中")
            .item(PublicationStatus.FUNDS_SHORT, "en", "Funds short", "zh", "预算不足", "ja", "残高不足")
            .item(PublicationStatus.ENDED, "en", "Ended", "zh", "已结束", "ja", "終了"));
    }

    @Bean
    StaticDictionary qbPublicationEndReasonDictionary() {
        return StaticDictionary.define(QbPublications.END_REASON_DICTIONARY, d -> d
            .item(PublicationStatus.EXPIRED, "en", "End time reached", "zh", "到达结束时间", "ja", "終了時刻に到達")
            .item(PublicationStatus.QUOTA, "en", "All winners rewarded", "zh", "名额已满", "ja", "当選枠に到達")
            .item(PublicationStatus.BUDGET, "en", "Budget spent", "zh", "预算已用完", "ja", "予算を使い切り"));
    }

    @Bean
    ApprovalSubject qbPublicationApprovalSubject() {
        return QbPublications.approvalSubject();
    }

    @Bean
    ProcessDefinition<PublicationProcesses.SaveInput, SaveOutput, ProcessContext> qbPublicationSave(
        MessageCatalog messages) {
        return PublicationProcesses.save(messages);
    }

    @Bean
    ProcessDefinition<PublicationProcesses.PublicationRef, PublicationOutput, ProcessContext> qbPublicationDelete() {
        return PublicationProcesses.delete();
    }

    @Bean
    ProcessDefinition<PublicationProcesses.CopyInput, SaveOutput, ProcessContext> qbPublicationCopy(
        MessageCatalog messages) {
        return PublicationProcesses.copy(messages);
    }

    @Bean
    ProcessDefinition<PublicationProcesses.PublicationRef, PublicationOutput, ProcessContext> qbPublicationSubmit() {
        return PublicationLifecycle.submit();
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.PublicationRef, PublicationOutput, ProcessContext> qbPublicationWithdraw() {
        return PublicationLifecycle.withdraw();
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.PublicationRef, PublicationOutput, ProcessContext> qbPublicationPause() {
        return PublicationLifecycle.pause();
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.PublicationRef, PublicationOutput, ProcessContext> qbPublicationResume() {
        return PublicationLifecycle.resume();
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.ApprovalResultInput, PublicationOutput, ProcessContext>
        qbPublicationApprovalResult() {
        return APPROVAL_RESULT;
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.TickInput, PublicationLifecycle.TickOutput, ProcessContext>
        qbPublicationTick() {
        return TICK;
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.PublicationRef, PublicationOutput, ProcessContext> qbPublicationAdvance() {
        return PublicationLifecycle.advance();
    }

    private static final ProcessDefinition<PublicationLifecycle.ApprovalResultInput, PublicationOutput,
        ProcessContext> APPROVAL_RESULT = PublicationLifecycle.approvalResult();

    private static final ProcessDefinition<PublicationLifecycle.TickInput, PublicationLifecycle.TickOutput,
        ProcessContext> TICK = PublicationLifecycle.tick();

    /** Every minute (D-Q4-7); a run finds everything due, so the minutes missed while down are made up. */
    @Bean
    JobDefinition<PublicationLifecycle.TickInput> qbPublicationTickJob() {
        return JobDefinition.cron(PublicationLifecycle.TICK_JOB, "0 * * * * *", ZoneOffset.UTC, TICK,
            PublicationLifecycle.TickInput::new);
    }

    @Bean
    EventSubscription<PublicationLifecycle.ApprovalResultInput> qbPublicationApprovedSubscription() {
        return EventSubscription.of("qb.publication-approved", ApprovalProcesses.APPROVED_EVENT, APPROVAL_RESULT,
            PublicationLifecycle::resultInput);
    }

    @Bean
    EventSubscription<PublicationLifecycle.ApprovalResultInput> qbPublicationRejectedSubscription() {
        return EventSubscription.of("qb.publication-rejected", ApprovalProcesses.REJECTED_EVENT, APPROVAL_RESULT,
            PublicationLifecycle::resultInput);
    }
}
