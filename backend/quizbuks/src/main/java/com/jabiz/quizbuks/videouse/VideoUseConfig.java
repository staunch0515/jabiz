package com.jabiz.quizbuks.videouse;

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
import com.jabiz.quizbuks.publishing.PublishingLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Registers the ledger of shared videos' uses and both sides' figures (F-S14, docs/quizbuks/plans/impl/F-S14.md): the
 * two entities, their default datasets (written by the processes only; read directly by headquarters' and the
 * operators' read permission, an operator's session its own sponsors' rows only), each side's own rows for its
 * templates (no role reads those datasets themselves), the dictionaries, the processes and the three daily jobs.
 */
@Configuration
public class VideoUseConfig {

    private final String poolRef;
    /** The deployment's zone ({@code jabiz.fiscal-year-zone}): the day of the check's task. */
    private final ZoneId zone;

    VideoUseConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.poolRef = poolRef;
        // An invalid zone is the platform's startup error (RETENTION): UTC here so that it is reported, not this.
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (java.time.DateTimeException e) {
            zoneId = ZoneOffset.UTC;
        }
        this.zone = zoneId;
    }

    @Bean
    EntityDefinition qbVideoUseEntity() {
        return QbVideoUses.USE_ENTITY;
    }

    @Bean
    EntityDefinition qbVideoUseFiguresEntity() {
        return QbVideoUses.FIGURES_ENTITY;
    }

    @Bean
    DatasetDefinition qbVideoUseDataset() {
        return defaultDataset(QbVideoUses.USE);
    }

    @Bean
    DatasetDefinition qbVideoUseFiguresDataset() {
        return defaultDataset(QbVideoUses.FIGURES);
    }

    @Bean
    DatasetDefinition qbSponsorVideoUseDataset() {
        return own(QbVideoUses.SPONSOR, QbVideoUses.USE, "sponsorId");
    }

    @Bean
    DatasetDefinition qbProviderVideoUseDataset() {
        return own(QbVideoUses.PROVIDER, QbVideoUses.USE, "providerId");
    }

    @Bean
    DatasetDefinition qbProviderVideoUseFiguresDataset() {
        return own(QbVideoUses.PROVIDER, QbVideoUses.FIGURES, "providerId");
    }

    private DatasetDefinition defaultDataset(String entity) {
        return DatasetDefinition.define(QbVideoUses.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_VIDEO_USE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(entity))
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** One side's own rows ({@code field} is the caller), for its templates only. */
    private DatasetDefinition own(String side, String entity, String field) {
        return DatasetDefinition.define(QbVideoUses.sideDataset(side, entity), d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.PUBLICATION_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext(field, "actorId", RequestContext::actorId)));
    }

    @Bean
    StaticDictionary qbVideoUsePricingDictionary() {
        return StaticDictionary.define(QbVideoUses.PRICING_DICTIONARY, d -> d
            .item(VideoUseRules.FIXED, "en", "Fixed price", "zh", "固定价", "ja", "固定価格")
            .item(VideoUseRules.PER_MILLE, "en", "Per 1,000 exposures", "zh", "按千次曝光", "ja", "表示 1,000 回あたり")
            .item(VideoUseRules.PER_TAKER, "en", "Per Taker who went on to answer", "zh", "按去答题的人数",
                "ja", "回答に進んだテイカー 1 人あたり"));
    }

    @Bean
    StaticDictionary qbVideoUseStatusDictionary() {
        return StaticDictionary.define(QbVideoUses.STATUS_DICTIONARY, d -> d
            .item(VideoUseRules.DRAFT, "en", "Draft", "zh", "草稿", "ja", "下書き")
            .item(VideoUseRules.SUBMITTED, "en", "Waiting for the provider", "zh", "等待提供方确认",
                "ja", "提供者の確認待ち")
            .item(VideoUseRules.CONFIRMED, "en", "Confirmed", "zh", "已确认", "ja", "確認済み")
            .item(VideoUseRules.REJECTED, "en", "Sent back", "zh", "被拒绝", "ja", "差し戻し"));
    }

    @Bean
    ProcessDefinition<VideoUseProcesses.SaveInput, VideoUseProcesses.VideoUseOutput, ProcessContext> qbVideoUseSave() {
        return VideoUseProcesses.save();
    }

    @Bean
    ProcessDefinition<VideoUseProcesses.UseRef, VideoUseProcesses.VideoUseOutput, ProcessContext> qbVideoUseSubmit() {
        return VideoUseProcesses.submit();
    }

    @Bean
    ProcessDefinition<VideoUseProcesses.UseRef, VideoUseProcesses.VideoUseOutput, ProcessContext> qbVideoUseConfirm() {
        return VideoUseProcesses.confirm();
    }

    @Bean
    ProcessDefinition<VideoUseProcesses.RejectInput, VideoUseProcesses.VideoUseOutput, ProcessContext>
        qbVideoUseReject() {
        return VideoUseProcesses.reject();
    }

    @Bean
    ProcessDefinition<VideoUseFigures.RefreshInput, VideoUseFigures.RefreshOutput, ProcessContext>
        qbVideoUseFiguresRefresh() {
        return VideoUseFigures.refresh();
    }

    /** Every day, each publication using another's shared video in a transaction of its own (decision D42). */
    @Bean
    JobDefinition<VideoUseFigures.RefreshInput> qbVideoUseFiguresJob(
        ProcessDefinition<VideoUseFigures.RefreshInput, VideoUseFigures.RefreshOutput, ProcessContext>
            qbVideoUseFiguresRefresh) {
        return JobDefinition.forEach(VideoUseFigures.JOB, VideoUseFigures.CRON, ZoneOffset.UTC,
                JobItems.template(QbVideoUses.FIGURES_DUE_TEMPLATE, scheduledTime -> Map.of(), "publicationId"),
                qbVideoUseFiguresRefresh, (scheduledTime, row) -> new VideoUseFigures.RefreshInput(
                    String.valueOf(row.get("publicationId")), scheduledTime))
            .maxItems(PublishingLimits.TICK_BATCH);
    }

    @Bean
    ProcessDefinition<VideoUseReminders.RemindInput, VideoUseReminders.RemindOutput, ProcessContext>
        qbVideoUseRemind() {
        return VideoUseReminders.remind();
    }

    /** Every day, each use waiting too long for its provider in a transaction of its own (decision D42). */
    @Bean
    JobDefinition<VideoUseReminders.RemindInput> qbVideoUseRemindJob(
        ProcessDefinition<VideoUseReminders.RemindInput, VideoUseReminders.RemindOutput, ProcessContext>
            qbVideoUseRemind) {
        return JobDefinition.forEach(VideoUseReminders.JOB, VideoUseReminders.CRON, ZoneOffset.UTC,
                JobItems.template(QbVideoUses.REMINDERS_DUE_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "videoUseId"),
                qbVideoUseRemind, (scheduledTime, row) -> new VideoUseReminders.RemindInput(
                    String.valueOf(row.get("videoUseId"))))
            .maxItems(PublishingLimits.TICK_BATCH);
    }

    @Bean
    ProcessDefinition<VideoUseFigures.CheckInput, VideoUseFigures.CheckOutput, ProcessContext> qbVideoUseCheckRun() {
        return VideoUseFigures.check(zone);
    }

    @Bean
    JobDefinition<VideoUseFigures.CheckInput> qbVideoUseCheckJob(
        ProcessDefinition<VideoUseFigures.CheckInput, VideoUseFigures.CheckOutput, ProcessContext>
            qbVideoUseCheckRun) {
        return JobDefinition.cron(VideoUseFigures.CHECK_JOB, VideoUseFigures.CHECK_CRON, ZoneOffset.UTC,
            qbVideoUseCheckRun, VideoUseFigures.CheckInput::new);
    }
}
