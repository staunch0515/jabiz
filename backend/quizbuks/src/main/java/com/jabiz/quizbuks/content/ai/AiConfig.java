package com.jabiz.quizbuks.content.ai;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.external.ExternalJobCancellation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.ExternalService;
import com.jabiz.integrity.IntegrityKey;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.EditOutput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Registers AI generation of questions (docs/quizbuks/plans/Q8-ai.md, Q8a): Claude's external service, the job type,
 * the two tables with their datasets and dictionaries, the seven processes and the daily expiry of candidates.
 *
 * <p>The administrators' (default) datasets are read with {@code qb.admin.ai.read} and written by the processes only;
 * a sponsor reads their own jobs and usage only through the templates {@code qb.sponsor.ai-*}, which go through
 * read-only datasets scoped to the caller ({@code sponsorId}). Those datasets are not readable themselves (their read
 * permission is {@code qb.ai.system}, held by nobody): a dataset gives every column, and the sponsor is not shown the
 * model that answered, the estimate or the input's size. Nobody writes through a dataset. Days and months are the deployment's zone's ({@code jabiz.fiscal-year-zone}).
 */
@Configuration
public class AiConfig {

    private final String poolRef;
    private final ZoneId zone;

    AiConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.poolRef = poolRef;
        // An invalid zone is the platform's startup error (RETENTION); UTC here so that it is reported, not this.
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (RuntimeException e) {
            zoneId = ZoneOffset.UTC;
        }
        this.zone = zoneId;
    }

    @Bean
    ExternalService anthropicService() {
        return ClaudeService.DEFINITION;
    }

    /** How long a call waits for Claude's answer ({@code quizbuks.ai.request-timeout}), for both writers. */
    @Bean
    ClaudeCalls.Settings qbAiCallSettings(@Value("${quizbuks.ai.request-timeout:PT2M}") java.time.Duration timeout) {
        return new ClaudeCalls.Settings(timeout);
    }

    /**
     * The PDF materials a request may send (Q8b): {@code quizbuks.ai.pdf.max-bytes} each (10 MiB unless configured,
     * at most 20 MiB) and {@code quizbuks.ai.pdf.max-pages} pages together (100, at most 600).
     */
    @Bean
    AiPdfs.Limits qbAiPdfLimits(
        @Value("${quizbuks.ai.pdf.max-bytes:" + AiPdfs.DEFAULT_MAX_BYTES + "}") long maxBytes,
        @Value("${quizbuks.ai.pdf.max-pages:" + AiPdfs.DEFAULT_MAX_PAGES + "}") int maxPages) {
        return new AiPdfs.Limits(maxBytes, maxPages);
    }

    /** @param maxConcurrency jobs running at a time across instances ({@code quizbuks.ai.max-concurrency}) */
    @Bean
    ExternalJobType<AiQuestionsJob.Input, AiQuestionsJob.Result> qbAiQuestionsJob(
        @Value("${quizbuks.ai.max-concurrency:" + AiQuestionsJob.DEFAULT_CONCURRENCY + "}") int maxConcurrency,
        AiPdfs.Limits pdfs) {
        return AiQuestionsJob.type(maxConcurrency, pdfs.maxBytes());
    }

    /** Candidate covers (Q8b): the same concurrency setting, counted for this type of its own. */
    @Bean
    ExternalJobType<AiCoverJob.Input, AiCoverJob.Result> qbAiCoverJob(
        @Value("${quizbuks.ai.max-concurrency:" + AiQuestionsJob.DEFAULT_CONCURRENCY + "}") int maxConcurrency) {
        return AiCoverJob.type(maxConcurrency);
    }

    @Bean
    AiUserKeys aiUserKeys(IntegrityKey integrityKey) {
        return AiUserKeys.of(integrityKey);
    }

    @Bean
    EntityDefinition qbAiJobEntity() {
        return QbAi.JOB_ENTITY;
    }

    @Bean
    EntityDefinition qbAiUsageEntity() {
        return QbAi.USAGE_ENTITY;
    }

    @Bean
    DatasetDefinition qbAiJobDataset() {
        return defaultDataset(QbAi.JOB);
    }

    @Bean
    DatasetDefinition qbAiUsageDataset() {
        return defaultDataset(QbAi.USAGE);
    }

    @Bean
    DatasetDefinition qbSponsorAiJobDataset() {
        return sponsorDataset(QbAi.JOB);
    }

    @Bean
    DatasetDefinition qbSponsorAiUsageDataset() {
        return sponsorDataset(QbAi.USAGE);
    }

    private DatasetDefinition defaultDataset(String entity) {
        return DatasetDefinition.define(QbAi.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_AI_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(10))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /**
     * The scope of the sponsor's templates. Neither read nor write permission is held by any role: the templates pick
     * the columns a sponsor sees.
     */
    private DatasetDefinition sponsorDataset(String entity) {
        return DatasetDefinition.define(QbAi.sponsorDataset(entity), d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.AI_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    @Bean
    StaticDictionary qbAiJobStatusDictionary() {
        return StaticDictionary.define(QbAi.STATUS_DICTIONARY, d -> d
            .item(QbAi.QUEUED, "en", "Generating", "zh", "生成中", "ja", "生成中")
            .item(QbAi.READY, "en", "To review", "zh", "待校对", "ja", "確認待ち")
            .item(QbAi.FAILED, "en", "Failed", "zh", "失败", "ja", "失敗")
            .item(QbAi.CANCELLED, "en", "Cancelled", "zh", "已取消", "ja", "取り消し")
            .item(QbAi.ACCEPTED, "en", "Added", "zh", "已加入题库", "ja", "追加済み")
            .item(QbAi.DISCARDED, "en", "Discarded", "zh", "已丢弃", "ja", "破棄")
            .item(QbAi.EXPIRED, "en", "Expired", "zh", "已过期", "ja", "期限切れ"));
    }

    @Bean
    StaticDictionary qbAiJobKindDictionary() {
        return StaticDictionary.define(QbAi.KIND_DICTIONARY, d -> d
            .item(QbAi.KIND_QUESTIONS, "en", "Questions", "zh", "题目", "ja", "問題")
            .item(QbAi.KIND_COVER, "en", "Cover", "zh", "封面", "ja", "カバー"));
    }

    /** The values the model parameter may take (its kind lists them; the dictionary labels them). */
    @Bean
    StaticDictionary qbAiModelDictionary() {
        return StaticDictionary.define("urn:jabiz:dict:quizbuks:ai-model", d -> d
            .item(ClaudeService.OPUS, "en", "Claude Opus 5.5", "zh", "Claude Opus 5.5", "ja", "Claude Opus 5.5")
            .item(ClaudeService.SONNET, "en", "Claude Sonnet 5.5", "zh", "Claude Sonnet 5.5", "ja",
                "Claude Sonnet 5.5")
            .item(ClaudeService.HAIKU, "en", "Claude Haiku 5.5", "zh", "Claude Haiku 5.5", "ja", "Claude Haiku 5.5"));
    }

    @Bean
    StaticDictionary qbAiEffortDictionary() {
        return StaticDictionary.define("urn:jabiz:dict:quizbuks:ai-effort", d -> d
            .item("low", "en", "Low", "zh", "低", "ja", "低")
            .item("medium", "en", "Medium", "zh", "中", "ja", "中")
            .item("high", "en", "High", "zh", "高", "ja", "高"));
    }

    @Bean
    ProcessDefinition<AiProcesses.RequestInput, AiProcesses.RequestOutput, ProcessContext> qbAiQuestionsRequest(
        AiUserKeys userKeys, AiPdfs.Limits pdfs) {
        return AiProcesses.request(zone, userKeys, pdfs);
    }

    @Bean
    ProcessDefinition<AiProcesses.CoverRequestInput, AiProcesses.RequestOutput, ProcessContext> qbAiCoverRequest(
        AiUserKeys userKeys) {
        return AiProcesses.coverRequest(zone, userKeys);
    }

    @Bean
    ProcessDefinition<AiProcesses.CoverAcceptInput, EditOutput, ProcessContext> qbAiCoverAccept() {
        return AiProcesses.coverAccept();
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, AiProcesses.JobOutput, ProcessContext> qbAiJobCompleted() {
        return AiProcesses.completed(zone);
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, AiProcesses.JobOutput, ProcessContext> qbAiJobFailed() {
        return AiProcesses.failed(zone);
    }

    @Bean
    ProcessDefinition<AiProcesses.AcceptInput, AiProcesses.AcceptOutput, ProcessContext> qbAiAccept() {
        return AiProcesses.accept();
    }

    @Bean
    ProcessDefinition<AiProcesses.JobRef, AiProcesses.JobOutput, ProcessContext> qbAiDiscard() {
        return AiProcesses.discard();
    }

    @Bean
    ProcessDefinition<AiProcesses.JobRef, AiProcesses.JobOutput, ProcessContext> qbAiCancel() {
        return AiProcesses.cancel();
    }

    @Bean
    ProcessDefinition<AiProcesses.ExpireInput, AiProcesses.JobOutput, ProcessContext> qbAiExpire() {
        return AiProcesses.expire();
    }

    /**
     * Both job types' {@code onCancelled} (platform decision D52 item 2): a job cancelled by its sponsor
     * ({@value AiProcesses#CANCEL}) or by an operator at the platform ({@code EXTERNAL_JOB_CANCEL}, operations.md
     * section 7.8) is closed and charged what its attempts used, once it has ended.
     */
    @Bean
    ProcessDefinition<ExternalJobCancellation, AiProcesses.JobOutput, ProcessContext> qbAiJobCancelled() {
        return AiProcesses.cancelled(zone);
    }

    @Bean
    ProcessDefinition<AiProcesses.PlatformJobRef, AiProcesses.JobOutput, ProcessContext> qbAiCancelQuestionsJob() {
        return AiProcesses.cancelPlatformJob(AiProcesses.CANCEL_QUESTIONS_JOB, AiQuestionsJob.TYPE);
    }

    @Bean
    ProcessDefinition<AiProcesses.PlatformJobRef, AiProcesses.JobOutput, ProcessContext> qbAiCancelCoverJob() {
        return AiProcesses.cancelPlatformJob(AiProcesses.CANCEL_COVER_JOB, AiCoverJob.TYPE);
    }

    /**
     * Every day at 18:43 UTC (03:43 in Tokyo): each candidate set past its expiry is cleared, one item a transaction
     * under the sponsor's lock (platform decision D42); a missed day is made up by the next.
     */
    @Bean
    JobDefinition<AiProcesses.ExpireInput> qbAiExpireJob(
        ProcessDefinition<AiProcesses.ExpireInput, AiProcesses.JobOutput, ProcessContext> qbAiExpire) {
        return JobDefinition.forEach(AiProcesses.EXPIRE_JOB, "0 43 18 * * *", ZoneOffset.UTC,
                JobItems.template(AiProcesses.EXPIRED_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "aiJobId"),
                qbAiExpire,
                (scheduledTime, row) -> new AiProcesses.ExpireInput(UUID.fromString(String.valueOf(row.get("aiJobId"))),
                    String.valueOf(row.get("sponsorId"))))
            .maxItems(1_000);
    }
}
