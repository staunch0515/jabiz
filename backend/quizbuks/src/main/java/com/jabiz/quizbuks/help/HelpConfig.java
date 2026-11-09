package com.jabiz.quizbuks.help;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.external.ExternalJobCancellation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobType;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ai.AiUserKeys;
import com.jabiz.quizbuks.operator.OperatorScopes;
import com.jabiz.quizbuks.setup.Brand;
import com.jabiz.security.erasure.ErasedEntity;
import com.jabiz.security.erasure.PersonalDataEraser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers the AI help (F-C10, docs/quizbuks/plans/impl/F-C10.md): the three tables and their datasets, the
 * dictionaries, the job type {@value HelpJob#TYPE}, the people's processes, the job's outcomes, the daily upkeep and
 * what an erased account loses.
 *
 * <p>Datasets: the default ones are written by the processes only and read by nobody through the dataset API
 * ({@code qb.help.system}, held by no role), limited to an operator's own Sponsors for an operator's session (the
 * processes it runs read through them); the user's own ({@code me:}) for the user's templates; the conversations handed
 * to a person ({@code staff:}) for the people answering them, an operator's session its own Sponsors' only; the usage
 * read by headquarters' AI page. Days and months are the deployment's zone's ({@code jabiz.fiscal-year-zone}).
 */
@Configuration
public class HelpConfig {

    private final String poolRef;
    private final ZoneId zone;

    HelpConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
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
    EntityDefinition qbHelpThreadEntity() {
        return QbHelp.THREAD_ENTITY;
    }

    @Bean
    EntityDefinition qbHelpMessageEntity() {
        return QbHelp.MESSAGE_ENTITY;
    }

    @Bean
    EntityDefinition qbHelpUsageEntity() {
        return QbHelp.USAGE_ENTITY;
    }

    @Bean
    DatasetDefinition qbHelpThreadDataset() {
        return processOnly(QbHelp.THREAD);
    }

    @Bean
    DatasetDefinition qbHelpMessageDataset() {
        return processOnly(QbHelp.MESSAGE);
    }

    private DatasetDefinition processOnly(String entity) {
        return DatasetDefinition.define(QbHelp.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.HELP_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(entity))
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The usage: headquarters' AI page reads it (never a Sponsor: it is the platform's cost). */
    @Bean
    DatasetDefinition qbHelpUsageDataset() {
        return DatasetDefinition.define(QbHelp.defaultDataset(QbHelp.USAGE), d -> d
            .targetEntityType(QbHelp.USAGE)
            .asDefault()
            .permissions(QbPermissions.ADMIN_AI_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbMyHelpThreadDataset() {
        return own(QbHelp.THREAD);
    }

    @Bean
    DatasetDefinition qbMyHelpMessageDataset() {
        return own(QbHelp.MESSAGE);
    }

    /** The caller's own rows, for the user's templates only (no role reads the dataset itself). */
    private DatasetDefinition own(String entity) {
        return DatasetDefinition.define(QbHelp.ownDataset(entity), d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.HELP_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    /** The conversations handed to a person: the people answering them, an operator its own Sponsors' only. */
    @Bean
    DatasetDefinition qbStaffHelpThreadDataset() {
        return DatasetDefinition.define(QbHelp.STAFF_THREADS, d -> d
            .targetEntityType(QbHelp.THREAD)
            .permissions(QbPermissions.ADMIN_HELP, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> {
                OperatorScopes.of(QbHelp.THREAD).accept(s);
                s.fixed("escalated", true);
            }));
    }

    @Bean
    StaticDictionary qbHelpStatusDictionary() {
        return StaticDictionary.define(QbHelp.STATUS_DICTIONARY, d -> d
            .item(QbHelp.OPEN, "en", "Open", "zh", "进行中", "ja", "進行中")
            .item(QbHelp.ESCALATED, "en", "With a person", "zh", "已转人工", "ja", "担当者対応中")
            .item(QbHelp.CLOSED, "en", "Closed", "zh", "已关闭", "ja", "終了"));
    }

    @Bean
    StaticDictionary qbHelpKindDictionary() {
        return StaticDictionary.define(QbHelp.KIND_DICTIONARY, d -> d
            .item(QbHelp.QUESTION, "en", "Question", "zh", "提问", "ja", "質問")
            .item(QbHelp.ANSWER, "en", "AI answer", "zh", "AI 回答", "ja", "AI の回答")
            .item(QbHelp.USER, "en", "To a person", "zh", "给客服", "ja", "担当者へ")
            .item(QbHelp.STAFF, "en", "Reply", "zh", "客服回复", "ja", "担当者の返信"));
    }

    @Bean
    StaticDictionary qbHelpEntryDictionary() {
        return StaticDictionary.define(QbHelp.ENTRY_DICTIONARY, d -> d
            .item(QbHelp.APP, "en", "Taker", "zh", "学者", "ja", "テイカー")
            .item(QbHelp.SPONSOR, "en", "Sponsor", "zh", "发起方", "ja", "主催者"));
    }

    @Bean
    StaticDictionary qbHelpJobStatusDictionary() {
        return StaticDictionary.define(QbHelp.JOB_STATUS_DICTIONARY, d -> d
            .item(QbHelp.QUEUED, "en", "Answering", "zh", "回答中", "ja", "回答中")
            .item(QbHelp.ANSWERED, "en", "Answered", "zh", "已回答", "ja", "回答済み")
            .item(QbHelp.FAILED, "en", "Failed", "zh", "失败", "ja", "失敗")
            .item(QbHelp.CANCELLED, "en", "Stopped", "zh", "已停止", "ja", "停止"));
    }

    @Bean
    StaticDictionary qbHelpRatingDictionary() {
        return StaticDictionary.define(QbHelp.RATING_DICTIONARY, d -> d
            .item(QbHelp.SOLVED, "en", "Solved", "zh", "解决了", "ja", "解決した")
            .item(QbHelp.UNSOLVED, "en", "Not solved", "zh", "没解决", "ja", "解決しない"));
    }

    @Bean
    StaticDictionary qbHelpWaitDictionary() {
        return StaticDictionary.define(QbHelp.WAIT_DICTIONARY, d -> d
            .item(QbHelp.WAIT_STAFF, "en", "A person", "zh", "客服", "ja", "担当者")
            .item(QbHelp.WAIT_USER, "en", "The user", "zh", "用户", "ja", "利用者"));
    }

    @Bean
    StaticDictionary qbHelpCloseDictionary() {
        return StaticDictionary.define(QbHelp.CLOSE_DICTIONARY, d -> d
            .item(QbHelp.CLOSED_BY_USER, "en", "By the user", "zh", "用户关闭", "ja", "利用者が終了")
            .item(QbHelp.CLOSED_BY_STAFF, "en", "By a person", "zh", "客服关闭", "ja", "担当者が終了")
            .item(QbHelp.CLOSED_RESOLVED, "en", "Solved", "zh", "已解决", "ja", "解決")
            .item(QbHelp.CLOSED_AUTO, "en", "Closed by itself", "zh", "自动关闭", "ja", "自動終了"));
    }

    /**
     * @param maxConcurrency help jobs running at a time across instances ({@code quizbuks.help.max-concurrency})
     * @param attempts       attempts of one question ({@code quizbuks.help.attempts}, 1 to 5)
     * @param attemptTimeout one attempt at most ({@code quizbuks.help.attempt-timeout}, 30 s to 10 min)
     */
    @Bean
    ExternalJobType<HelpJob.Input, HelpJob.Result> qbHelpJob(
        @Value("${quizbuks.help.max-concurrency:" + HelpJob.DEFAULT_CONCURRENCY + "}") int maxConcurrency,
        @Value("${quizbuks.help.attempts:" + HelpJob.ATTEMPTS + "}") int attempts,
        @Value("${quizbuks.help.attempt-timeout:PT3M}") java.time.Duration attemptTimeout) {
        return HelpJob.type(maxConcurrency, attempts, attemptTimeout);
    }

    /** The site's host: the answers keep links to it only (and paths). */
    static Set<String> siteHosts() {
        String host = HelpAnswers.hostOf(Brand.current().siteUrl());
        return host == null ? Set.of() : Set.of(host, host.startsWith("www.") ? host.substring(4) : "www." + host);
    }

    @Bean
    ProcessDefinition<HelpProcesses.AskInput, HelpProcesses.AskOutput, ProcessContext> qbHelpAsk(AiUserKeys userKeys,
        JsonMapper json) {
        return HelpProcesses.ask(zone, userKeys, json);
    }

    @Bean
    ProcessDefinition<HelpProcesses.RateInput, HelpProcesses.ThreadOutput, ProcessContext> qbHelpRate() {
        return HelpProcesses.rate();
    }

    @Bean
    ProcessDefinition<HelpProcesses.EscalateInput, HelpProcesses.ThreadOutput, ProcessContext> qbHelpEscalate() {
        return HelpProcesses.escalate();
    }

    @Bean
    ProcessDefinition<HelpProcesses.ReplyInput, HelpProcesses.ThreadOutput, ProcessContext> qbHelpReply() {
        return HelpProcesses.reply();
    }

    @Bean
    ProcessDefinition<HelpProcesses.ThreadRef, HelpProcesses.ThreadOutput, ProcessContext> qbHelpClose() {
        return HelpProcesses.close();
    }

    /** A reassigned Sponsor's conversations with a person go with them ({@code QB_SPONSOR_OPERATOR_ASSIGN}). */
    @Bean
    ProcessDefinition<HelpOperatorMove.Input, HelpOperatorMove.Output, ProcessContext> qbHelpOperatorMove() {
        return HelpOperatorMove.move();
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, HelpOutcomes.OutcomeOutput, ProcessContext> qbHelpJobCompleted(
        MessageCatalog messages) {
        return HelpOutcomes.completed(zone, messages, siteHosts());
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, HelpOutcomes.OutcomeOutput, ProcessContext> qbHelpJobFailed() {
        return HelpOutcomes.failed(zone);
    }

    @Bean
    ProcessDefinition<ExternalJobCancellation, HelpOutcomes.OutcomeOutput, ProcessContext> qbHelpJobCancelled() {
        return HelpOutcomes.cancelled(zone);
    }

    @Bean
    ProcessDefinition<HelpUpkeep.ItemInput, HelpUpkeep.ItemOutput, ProcessContext> qbHelpRetention() {
        return HelpUpkeep.retention();
    }

    @Bean
    ProcessDefinition<HelpUpkeep.ItemInput, HelpUpkeep.ItemOutput, ProcessContext> qbHelpAutoClose() {
        return HelpUpkeep.autoClose();
    }

    @Bean
    ProcessDefinition<HelpUpkeep.ItemInput, HelpUpkeep.ItemOutput, ProcessContext> qbHelpRemind() {
        return HelpUpkeep.remind();
    }

    @Bean
    ProcessDefinition<HelpUpkeep.UsageCheckInput, HelpUpkeep.UsageCheckOutput, ProcessContext> qbHelpUsageCheck() {
        return HelpUpkeep.usageCheck(zone);
    }

    @Bean
    JobDefinition<HelpUpkeep.ItemInput> qbHelpRetentionJob(
        ProcessDefinition<HelpUpkeep.ItemInput, HelpUpkeep.ItemOutput, ProcessContext> qbHelpRetention) {
        return items(HelpUpkeep.RETENTION_JOB, HelpUpkeep.RETENTION_CRON, QbHelp.RETENTION_DUE, qbHelpRetention);
    }

    @Bean
    JobDefinition<HelpUpkeep.ItemInput> qbHelpAutoCloseJob(
        ProcessDefinition<HelpUpkeep.ItemInput, HelpUpkeep.ItemOutput, ProcessContext> qbHelpAutoClose) {
        return items(HelpUpkeep.AUTO_CLOSE_JOB, HelpUpkeep.AUTO_CLOSE_CRON, QbHelp.AUTO_CLOSE_DUE, qbHelpAutoClose);
    }

    @Bean
    JobDefinition<HelpUpkeep.ItemInput> qbHelpRemindJob(
        ProcessDefinition<HelpUpkeep.ItemInput, HelpUpkeep.ItemOutput, ProcessContext> qbHelpRemind) {
        return items(HelpUpkeep.REMIND_JOB, HelpUpkeep.REMIND_CRON, QbHelp.REMIND_DUE, qbHelpRemind);
    }

    @Bean
    JobDefinition<HelpUpkeep.UsageCheckInput> qbHelpUsageJob(
        ProcessDefinition<HelpUpkeep.UsageCheckInput, HelpUpkeep.UsageCheckOutput, ProcessContext> qbHelpUsageCheck) {
        return JobDefinition.cron(HelpUpkeep.USAGE_JOB, HelpUpkeep.USAGE_CRON, ZoneOffset.UTC, qbHelpUsageCheck,
            HelpUpkeep.UsageCheckInput::new);
    }

    /** Every day, each due conversation in a transaction of its own (decision D42); a missed day the next makes up. */
    private static JobDefinition<HelpUpkeep.ItemInput> items(String name, String cron, String template,
        ProcessDefinition<HelpUpkeep.ItemInput, HelpUpkeep.ItemOutput, ProcessContext> process) {
        return JobDefinition.forEach(name, cron, ZoneOffset.UTC,
                JobItems.template(template, scheduledTime -> Map.of("at", scheduledTime), "threadId"),
                process, (scheduledTime, row) -> new HelpUpkeep.ItemInput(String.valueOf(row.get("threadId"))))
            .maxItems(1_000);
    }

    /**
     * An erased account's help (platform decision D62): its conversations, their messages and its usage rows go; the
     * texts are masked in the audit records. The jobs' inputs and results are the platform's to purge (by requester).
     * Everybody's usage row stays: it holds no person.
     */
    @Bean
    PersonalDataEraser qbHelpPersonalData() {
        return () -> List.of(
            ErasedEntity.delete(QbHelp.MESSAGE, "userId", "body", "links"),
            ErasedEntity.delete(QbHelp.THREAD, "userId"),
            ErasedEntity.delete(QbHelp.USAGE, "userId"));
    }
}
