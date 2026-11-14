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

    /** Candidate translations (Q73d-1): the same concurrency setting, counted for this type of its own. */
    @Bean
    ExternalJobType<AiTranslateJob.Input, AiTranslateJob.Result> qbAiTranslateJob(
        @Value("${quizbuks.ai.max-concurrency:" + AiQuestionsJob.DEFAULT_CONCURRENCY + "}") int maxConcurrency) {
        return AiTranslateJob.type(maxConcurrency);
    }

    /** AI covers (Q8b), withdrawn (Q98a): registered so that jobs queued before end. */
    @Bean
    ExternalJobType<AiCoverJob.Input, AiCoverJob.Result> qbAiCoverJob(
        @Value("${quizbuks.ai.max-concurrency:" + AiQuestionsJob.DEFAULT_CONCURRENCY + "}") int maxConcurrency) {
        return AiCoverJob.type(maxConcurrency);
    }

    @Bean
    AiUserKeys aiUserKeys(IntegrityKey integrityKey) {
        return AiUserKeys.of(integrityKey);
    }

    /** F-M9a-2: the month's AI fees of a sponsor, charged from their AI credit (an item of {@value AiFeeSettlements#JOB}). */
    @Bean
    ProcessDefinition<AiFeeSettlements.SettleRef, com.jabiz.quizbuks.fee.FeeProcesses.ItemOutput, ProcessContext>
        qbAiFeeSettle() {
        return AiFeeSettlements.settle();
    }

    /**
     * F-M9a-2: the AI fees of the months before this one, on the 1st at 00:27 in the fiscal year's zone
     * ({@code quizbuks.ai-fee.monthly-cron}); missed runs are run again with {@code JOB_RUN_REQUEST}.
     */
    @Bean
    JobDefinition<AiFeeSettlements.SettleRef> qbAiFeeMonthlyJob(
        ProcessDefinition<AiFeeSettlements.SettleRef, com.jabiz.quizbuks.fee.FeeProcesses.ItemOutput, ProcessContext>
            qbAiFeeSettle,
        @Value("${quizbuks.ai-fee.monthly-cron:" + AiFeeSettlements.CRON + "}") String cron) {
        return JobDefinition.forEach(AiFeeSettlements.JOB, cron, zone,
                JobItems.template(AiFeeSettlements.DUE_TEMPLATE, scheduledTime -> Map.of("month",
                    com.jabiz.quizbuks.fee.FeeRules.period(scheduledTime, zone)), "itemKey"),
                qbAiFeeSettle,
                (scheduledTime, row) -> new AiFeeSettlements.SettleRef(String.valueOf(row.get("sponsorId")),
                    com.jabiz.quizbuks.fee.FeeRules.period(scheduledTime, zone), String.valueOf(row.get("itemKey"))))
            .maxItems(10_000);
    }

    /** F-M9a-2: the sponsor gives back AI credit. */
    @Bean
    ProcessDefinition<AiCreditReturns.ReturnInput, com.jabiz.quizbuks.fee.AiCreditGiveBack.GiveBackOutput,
        ProcessContext> qbAiCreditReturn() {
        return AiCreditReturns.giveBack();
    }

    /** F-M9a-2: a sponsor's expired fee credit in their AI credit, voided (an item of {@value AiCreditReturns#EXPIRE_JOB}). */
    @Bean
    ProcessDefinition<AiCreditReturns.ExpireRef, com.jabiz.quizbuks.fee.FeeProcesses.ItemOutput, ProcessContext>
        qbAiCreditExpireItem() {
        return AiCreditReturns.expireItem();
    }

    /**
     * F-M9a-2: the fee credit expired in AI credits, every day at 03:47 in the fiscal year's zone
     * ({@code quizbuks.ai-credit-expire.cron}); missed runs are run again with {@code JOB_RUN_REQUEST}.
     */
    @Bean
    JobDefinition<AiCreditReturns.ExpireRef> qbAiCreditExpireJob(
        ProcessDefinition<AiCreditReturns.ExpireRef, com.jabiz.quizbuks.fee.FeeProcesses.ItemOutput, ProcessContext>
            qbAiCreditExpireItem,
        @Value("${quizbuks.ai-credit-expire.cron:" + AiCreditReturns.EXPIRE_CRON + "}") String cron) {
        return JobDefinition.forEach(AiCreditReturns.EXPIRE_JOB, cron, zone,
                JobItems.template(AiCreditReturns.EXPIRE_DUE_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "itemKey"),
                qbAiCreditExpireItem,
                (scheduledTime, row) -> new AiCreditReturns.ExpireRef(String.valueOf(row.get("sponsorId"))))
            .maxItems(10_000);
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
            // F-M9a-2: the month's charge writes back each job it charges, AiFeeSettlements.BATCH_SIZE at most.
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(QbAi.JOB.equals(entity)
                ? AiFeeSettlements.BATCH_SIZE : 10))
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
            .item(QbAi.KIND_COVER, "en", "Cover", "zh", "封面", "ja", "カバー")
            .item(QbAi.KIND_TRANSLATION, "en", "Translation", "zh", "翻译", "ja", "翻訳")
            .item(QbAi.KIND_PUBLICATION_TEXTS, "en", "Publication texts", "zh", "发布文字", "ja", "公開の文言")
            .item(QbAi.KIND_TRANSLATION_CHECK, "en", "Translation check", "zh", "译文对照检查", "ja", "訳文の照合")
            .item(QbAi.KIND_SITE_PROFILE, "en", "Draft from a website", "zh", "官网草稿", "ja", "公式サイトの下書き"));
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

    /** Q73d-1: candidate translations of a quiz's text, asked for as questions are. */
    @Bean
    ProcessDefinition<AiProcesses.TranslateRequestInput, AiProcesses.RequestOutput, ProcessContext>
        qbAiTranslateRequest(AiUserKeys userKeys) {
        return AiProcesses.translateRequest(zone, userKeys);
    }

    @Bean
    ProcessDefinition<AiProcesses.TranslationAcceptInput, EditOutput, ProcessContext> qbAiTranslationAccept() {
        return AiProcesses.translationAccept();
    }

    /** Q73d-2: a publication's own texts translated, and their use recorded. */
    @Bean
    ProcessDefinition<AiProcesses.PublicationTranslateRequestInput, AiProcesses.RequestOutput, ProcessContext>
        qbAiPublicationTranslateRequest(AiUserKeys userKeys) {
        return AiProcesses.publicationTranslateRequest(zone, userKeys);
    }

    @Bean
    ProcessDefinition<AiProcesses.PublicationTextsAcceptInput, AiProcesses.JobOutput, ProcessContext>
        qbAiPublicationTextsAccept() {
        return AiProcesses.publicationTextsAccept();
    }

    /** Q73d-2: a reviewer's comparison of a publication's translations, charged to the platform. */
    @Bean
    ProcessDefinition<AiProcesses.CheckRequestInput, AiProcesses.RequestOutput, ProcessContext> qbAiCheckRequest(
        AiUserKeys userKeys) {
        return AiProcesses.checkRequest(zone, userKeys);
    }

    /** F-C7: a profile package and a quiz drafted from the sponsor's website. */
    @Bean
    ExternalJobType<SiteProfileJob.Input, SiteProfileJob.Result> qbAiSiteProfileJob(
        @Value("${quizbuks.ai.max-concurrency:" + AiQuestionsJob.DEFAULT_CONCURRENCY + "}") int maxConcurrency) {
        return SiteProfileJob.type(maxConcurrency);
    }

    @Bean
    ProcessDefinition<SiteProfileProcesses.RequestInput, AiProcesses.RequestOutput, ProcessContext>
        qbSiteProfileRequest(AiUserKeys userKeys) {
        return SiteProfileProcesses.request(zone, userKeys);
    }

    @Bean
    ProcessDefinition<SiteQuizProcesses.QuizInput, SiteQuizProcesses.QuizOutput, ProcessContext> qbSiteProfileQuiz() {
        return SiteQuizProcesses.quiz();
    }

    @Bean
    ProcessDefinition<SiteProfileProcesses.ReviewRef, AiProcesses.JobOutput, ProcessContext> qbSiteProfileReviewDone() {
        return SiteProfileProcesses.reviewDone();
    }

    @Bean
    ProcessDefinition<SiteProfileProcesses.ReviewRef, AiProcesses.JobOutput, ProcessContext> qbSiteProfileReviewClose() {
        return SiteProfileProcesses.closeReview();
    }

    @Bean
    ExternalJobType<AiCheckJob.Input, AiCheckJob.Result> qbAiCheckJob(
        @Value("${quizbuks.ai.max-concurrency:" + AiQuestionsJob.DEFAULT_CONCURRENCY + "}") int maxConcurrency) {
        return AiCheckJob.type(maxConcurrency);
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
