package com.jabiz.quizbuks.prospect;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.external.ExternalJobCancellation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobType;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ai.AiQuestionsJob;
import com.jabiz.quizbuks.content.ai.AiUserKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Registers AI prospecting (docs/quizbuks/plans/Q48-operators.md, Q48c): the five tables with their default datasets
 * (read by headquarters, {@code qb.admin.prospect}; written by the processes only), the public dataset of the trial
 * quizzes that went out, the dictionaries, the two job types, the mail, the processes ({@link ProspectProcesses}) and
 * the three item jobs. Operators read their own prospecting through templates ({@code queries/qb/operator}). Days
 * and months are the deployment's zone's ({@code jabiz.fiscal-year-zone}).
 */
@Configuration
public class ProspectConfig {

    private final String poolRef;
    private final ZoneId zone;

    ProspectConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.poolRef = poolRef;
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (RuntimeException e) {
            zoneId = ZoneOffset.UTC;
        }
        this.zone = zoneId;
    }

    @Bean
    EntityDefinition qbProspectSettingEntity() {
        return QbProspects.SETTING_ENTITY;
    }

    @Bean
    EntityDefinition qbProspectDirectionEntity() {
        return QbProspects.DIRECTION_ENTITY;
    }

    @Bean
    EntityDefinition qbProspectEntity() {
        return QbProspects.PROSPECT_ENTITY;
    }

    @Bean
    EntityDefinition qbProspectBlockEntity() {
        return QbProspects.BLOCK_ENTITY;
    }

    @Bean
    EntityDefinition qbProspectUsageEntity() {
        return QbProspects.USAGE_ENTITY;
    }

    private DatasetDefinition defaultDataset(String entity) {
        return DatasetDefinition.define(QbProspects.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PROSPECT, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbProspectSettingDataset() {
        return defaultDataset(QbProspects.SETTING);
    }

    @Bean
    DatasetDefinition qbProspectDirectionDataset() {
        return defaultDataset(QbProspects.DIRECTION);
    }

    @Bean
    DatasetDefinition qbProspectDataset() {
        return defaultDataset(QbProspects.PROSPECT);
    }

    @Bean
    DatasetDefinition qbProspectBlockDataset() {
        return defaultDataset(QbProspects.BLOCK);
    }

    @Bean
    DatasetDefinition qbProspectUsageDataset() {
        return defaultDataset(QbProspects.USAGE);
    }

    /**
     * The trial quizzes that went out, for the public trial page ({@code /sponsor/try/<key>}): found only by the
     * random key in the mail, and nothing of the business but its name and the quiz made from its own site.
     */
    @Bean
    DatasetDefinition qbPublicProspectTrialDataset() {
        return DatasetDefinition.define(QbProspects.PUBLIC_TRIALS, d -> d
            .targetEntityType(QbProspects.PROSPECT)
            .scope(s -> s.fixed("status", QbProspects.SENT))
            .publicRead(p -> p.fields("trialKey", "company", "language", "trial"))
            .permissions(QbPermissions.ADMIN_PROSPECT, QbPermissions.SPONSOR_VIEW_WRITE)
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbProspectStatusDictionary() {
        return StaticDictionary.define(QbProspects.STATUS_DICTIONARY, d -> d
            .item(QbProspects.FOUND, "en", "Found", "zh", "已发现", "ja", "発見")
            .item(QbProspects.RESEARCHING, "en", "Researching", "zh", "调研中", "ja", "調査中")
            .item(QbProspects.DRAFTED, "en", "To review", "zh", "待审核", "ja", "確認待ち")
            .item(QbProspects.LOW_SCORE, "en", "Low score", "zh", "评分不足", "ja", "スコア不足")
            .item(QbProspects.SKIPPED, "en", "Skipped", "zh", "已跳过", "ja", "対象外")
            .item(QbProspects.FAILED, "en", "Failed", "zh", "失败", "ja", "失敗")
            .item(QbProspects.APPROVED, "en", "Approved", "zh", "已批准", "ja", "承認済み")
            .item(QbProspects.SENT, "en", "Sent", "zh", "已发送", "ja", "送信済み")
            .item(QbProspects.DISCARDED, "en", "Discarded", "zh", "已丢弃", "ja", "破棄"));
    }

    @Bean
    StaticDictionary qbProspectBlockReasonDictionary() {
        return StaticDictionary.define(QbProspects.BLOCK_REASON_DICTIONARY, d -> d
            .item(QbProspects.UNSUBSCRIBED, "en", "Unsubscribed", "zh", "已退订", "ja", "配信停止")
            .item(QbProspects.DECLINED, "en", "Declined", "zh", "已拒绝", "ja", "辞退")
            .item(QbProspects.BOUNCED, "en", "Bounced", "zh", "退信", "ja", "不達")
            .item(QbProspects.COMPLAINED, "en", "Complained", "zh", "投诉", "ja", "苦情")
            .item(QbProspects.REFUSES_MARKETING, "en", "Refuses marketing mail", "zh", "拒收营销邮件", "ja",
                "広告メール拒否")
            .item(QbProspects.MANUAL, "en", "Blocked by hand", "zh", "手工屏蔽", "ja", "手動で除外"));
    }

    @Bean
    MailTemplate qbProspectMail() {
        return ProspectMails.PROSPECT;
    }

    /** @param maxConcurrency jobs running at a time across instances, per type ({@code quizbuks.prospect.max-concurrency}) */
    @Bean
    ExternalJobType<ProspectJobs.FindInput, ProspectJobs.FindResult> qbProspectFindJob(
        @Value("${quizbuks.prospect.max-concurrency:" + AiQuestionsJob.DEFAULT_CONCURRENCY + "}") int maxConcurrency) {
        return ProspectJobs.findType(maxConcurrency);
    }

    @Bean
    ExternalJobType<ProspectJobs.ResearchInput, ProspectJobs.ResearchResult> qbProspectResearchJob(
        @Value("${quizbuks.prospect.max-concurrency:" + AiQuestionsJob.DEFAULT_CONCURRENCY + "}") int maxConcurrency) {
        return ProspectJobs.researchType(maxConcurrency);
    }

    // ---- people's ----------------------------------------------------------------------------------------------------

    @Bean
    ProcessDefinition<ProspectProcesses.SettingsInput, ProspectProcesses.SettingsOutput, ProcessContext>
        qbProspectSettingsSave() {
        return ProspectProcesses.settingsSave();
    }

    @Bean
    ProcessDefinition<ProspectProcesses.DirectionInput, ProspectProcesses.DirectionOutput, ProcessContext>
        qbProspectDirectionSave() {
        return ProspectProcesses.directionSave();
    }

    @Bean
    ProcessDefinition<ProspectProcesses.ApproveInput, ProspectProcesses.ProspectOutput, ProcessContext>
        qbProspectApprove(JsonMapper json) {
        return ProspectProcesses.approve(json);
    }

    @Bean
    ProcessDefinition<ProspectProcesses.DiscardInput, ProspectProcesses.ProspectOutput, ProcessContext>
        qbProspectDiscard() {
        return ProspectProcesses.discard();
    }

    @Bean
    ProcessDefinition<ProspectProcesses.BlockInput, ProspectProcesses.BlockOutput, ProcessContext>
        qbProspectBlockAdd() {
        return ProspectProcesses.blockAdd();
    }

    @Bean
    ProcessDefinition<com.jabiz.quizbuks.mailserver.MailServerProcesses.SetInput,
        com.jabiz.quizbuks.mailserver.MailServerProcesses.SetOutput, ProcessContext> qbProspectMailServerSet() {
        return ProspectProcesses.mailServerSet();
    }

    @Bean
    ProcessDefinition<com.jabiz.quizbuks.mailserver.MailServerProcesses.RemoveInput,
        com.jabiz.quizbuks.mailserver.MailServerProcesses.RemoveOutput, ProcessContext> qbProspectMailServerRemove() {
        return ProspectProcesses.mailServerRemove();
    }

    // ---- the system's ------------------------------------------------------------------------------------------------

    @Bean
    ProcessDefinition<ProspectProcesses.DirectionRef, ProspectProcesses.ItemOutput, ProcessContext>
        qbProspectFindStart(AiUserKeys keys) {
        return ProspectProcesses.findStart(zone, keys);
    }

    @Bean
    ProcessDefinition<ProspectProcesses.ProspectRef, ProspectProcesses.ItemOutput, ProcessContext>
        qbProspectResearchStart(AiUserKeys keys) {
        return ProspectProcesses.researchStart(zone, keys);
    }

    @Bean
    ProcessDefinition<ProspectProcesses.ProspectRef, ProspectProcesses.ItemOutput, ProcessContext> qbProspectSend() {
        return ProspectProcesses.send(zone);
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, ProspectProcesses.ItemOutput, ProcessContext> qbProspectFound() {
        return ProspectProcesses.found(zone);
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, ProspectProcesses.ItemOutput, ProcessContext> qbProspectFindFailed() {
        return ProspectProcesses.findFailed(zone);
    }

    @Bean
    ProcessDefinition<ExternalJobCancellation, ProspectProcesses.ItemOutput, ProcessContext>
        qbProspectFindCancelled() {
        return ProspectProcesses.findCancelled(zone);
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, ProspectProcesses.ItemOutput, ProcessContext> qbProspectResearched(
        JsonMapper json) {
        return ProspectProcesses.researched(zone, json);
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, ProspectProcesses.ItemOutput, ProcessContext> qbProspectResearchFailed() {
        return ProspectProcesses.researchFailed(zone);
    }

    @Bean
    ProcessDefinition<ExternalJobCancellation, ProspectProcesses.ItemOutput, ProcessContext>
        qbProspectResearchCancelled() {
        return ProspectProcesses.researchCancelled(zone);
    }

    /**
     * Every day at 23:17 UTC (08:17 in Tokyo): each active direction of an owner that prospects is searched once
     * (the item reads it again under the owner's lock and does nothing when it was searched that day).
     */
    @Bean
    JobDefinition<ProspectProcesses.DirectionRef> qbProspectFindJobDefinition(
        ProcessDefinition<ProspectProcesses.DirectionRef, ProspectProcesses.ItemOutput, ProcessContext> start) {
        return JobDefinition.forEach(ProspectProcesses.FIND_JOB, "0 17 23 * * *", ZoneOffset.UTC,
                JobItems.template(ProspectProcesses.FIND_DUE_TEMPLATE, scheduledTime -> Map.of(
                    "dayStart", ProspectProcesses.dayStart(scheduledTime, zone)), "directionId"),
                start,
                (scheduledTime, row) -> new ProspectProcesses.DirectionRef(
                    UUID.fromString(String.valueOf(row.get("directionId")))))
            .maxItems(1_000);
    }

    /**
     * Every hour at :23: the found businesses, oldest first, each researched while its owner has research left for
     * the day ({@value ProspectProcesses#RESEARCH_PER_MAIL} per mail of its daily cap).
     */
    @Bean
    JobDefinition<ProspectProcesses.ProspectRef> qbProspectResearchJobDefinition(
        ProcessDefinition<ProspectProcesses.ProspectRef, ProspectProcesses.ItemOutput, ProcessContext> start) {
        return JobDefinition.forEach(ProspectProcesses.RESEARCH_JOB, "0 23 * * * *", ZoneOffset.UTC,
                JobItems.template(ProspectProcesses.RESEARCH_DUE_TEMPLATE, scheduledTime -> Map.of(
                    "dayStart", ProspectProcesses.dayStart(scheduledTime, zone)), "prospectId"),
                start,
                (scheduledTime, row) -> new ProspectProcesses.ProspectRef(
                    UUID.fromString(String.valueOf(row.get("prospectId")))))
            .maxItems(500);
    }

    /**
     * Every ten minutes: the approved mails, oldest approval first, each sent within its owner's working hours and
     * daily cap (the item decides; one waiting for those simply comes again).
     */
    @Bean
    JobDefinition<ProspectProcesses.ProspectRef> qbProspectSendJobDefinition(
        ProcessDefinition<ProspectProcesses.ProspectRef, ProspectProcesses.ItemOutput, ProcessContext> send) {
        return JobDefinition.forEach(ProspectProcesses.SEND_JOB, "0 7/10 * * * *", ZoneOffset.UTC,
                JobItems.template(ProspectProcesses.SEND_DUE_TEMPLATE, scheduledTime -> Map.of(
                    "dayStart", ProspectProcesses.dayStart(scheduledTime, zone)), "prospectId"),
                send,
                (scheduledTime, row) -> new ProspectProcesses.ProspectRef(
                    UUID.fromString(String.valueOf(row.get("prospectId")))))
            .maxItems(500);
    }
}
