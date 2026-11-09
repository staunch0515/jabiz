package com.jabiz.quizbuks.help;

import com.jabiz.external.ExternalJobCancellation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.param.ParamValues;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ai.AiCost;
import com.jabiz.quizbuks.content.ai.AiLimits;
import com.jabiz.quizbuks.content.ai.AiQuestionsJob;
import com.jabiz.quizbuks.content.ai.ClaudeApi;
import com.jabiz.quizbuks.content.ai.ClaudeCalls;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The outcomes of {@value HelpJob#TYPE} (F-C10), {@code internal()}: {@value #COMPLETED}, {@value #FAILED} and
 * {@value #CANCELLED}. Each one counts the job in {@link QbHelp#USAGE} (the user's month and everybody's): what its
 * attempts recorded, and for each attempt that recorded nothing (platform decision D52 item 5: its instance died or
 * timed out before it could) the question's reserved input and all the output it asked for, since the provider may have
 * billed it. A cancellation before any attempt ran cost nothing and counts no tokens. Never the Sponsors' AI usage.
 *
 * <p>The question still waiting is answered: the answer checked again ({@link HelpAnswers}: the site's links only; a
 * request to answer a quiz kept as a refusal, best effort), or the failure recorded for the pages to explain. A
 * conversation deleted meanwhile (retention, an erased account) only counts everybody's usage.
 *
 * <p>Under the conversation's lock, then the user's usage row's and everybody's: the outcomes run with the job type's
 * bounded concurrency, never on a question's path.
 */
public final class HelpOutcomes {

    public static final String COMPLETED = "QB_HELP_JOB_COMPLETED";
    public static final String FAILED = "QB_HELP_JOB_FAILED";
    public static final String CANCELLED = "QB_HELP_JOB_CANCELLED";

    /** The messages of the answers the help writes itself. */
    static final String QUIZ_REFUSAL = "qb.help.quiz-refusal";
    static final String NO_ANSWER = "qb.help.no-answer";

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String THREAD = "thread";
    static final String MESSAGE = "message";
    static final String USER_USAGE = "userUsage";
    static final String EVERYBODY_USAGE = "everybodyUsage";
    /** The share of everybody's monthly tokens used after the outcome, in percent (the gauge's value). */
    static final String PERCENT = HelpUsageGauge.PERCENT;

    /** What the three outcomes share. */
    record Outcome(String kind, String requestedBy, String reference, Map<String, Long> usage, int unrecordedAttempts,
        Instant finishedTime, boolean attemptRan) {

        static Outcome of(Object input) {
            return switch (input) {
                case ExternalJobCompletion c -> new Outcome(QbHelp.ANSWERED, c.requestedBy(), c.reference(), c.usage(),
                    c.unrecordedAttempts(), c.finishedTime(), true);
                case ExternalJobFailure f -> new Outcome(QbHelp.FAILED, f.requestedBy(), f.reference(), f.usage(),
                    f.unrecordedAttempts(), f.finishedTime(), true);
                case ExternalJobCancellation c -> new Outcome(QbHelp.CANCELLED, c.requestedBy(), c.reference(),
                    c.usage(), c.unrecordedAttempts(), c.finishedTime(), c.attemptRan());
                default -> throw new IllegalArgumentException("Not a job outcome: " + input);
            };
        }

        String threadId() {
            String[] parts = QbHelp.ofReference(reference);
            return parts == null ? null : parts[0];
        }

        String messageId() {
            String[] parts = QbHelp.ofReference(reference);
            return parts == null ? null : parts[1];
        }
    }

    public record OutcomeOutput(String messageId, String jobStatus) {}

    private HelpOutcomes() {}

    public static ProcessDefinition<ExternalJobCompletion, OutcomeOutput, ProcessContext> completed(ZoneId zone,
        MessageCatalog messages, Set<String> siteHosts) {
        return outcome(COMPLETED, ExternalJobCompletion.class, "Keeps the AI help's answer and counts its usage.",
            zone, messages, siteHosts);
    }

    public static ProcessDefinition<ExternalJobFailure, OutcomeOutput, ProcessContext> failed(ZoneId zone) {
        return outcome(FAILED, ExternalJobFailure.class, "Records that an AI help question failed and counts its usage.",
            zone, null, Set.of());
    }

    public static ProcessDefinition<ExternalJobCancellation, OutcomeOutput, ProcessContext> cancelled(ZoneId zone) {
        return outcome(CANCELLED, ExternalJobCancellation.class,
            "Records that an AI help question was stopped and counts what its attempts used.", zone, null, Set.of());
    }

    private static <I> ProcessDefinition<I, OutcomeOutput, ProcessContext> outcome(String name, Class<I> type,
        String description, ZoneId zone, MessageCatalog messages, Set<String> siteHosts) {
        return ProcessDefinition.define(name, 1, type, OutcomeOutput.class, ProcessContext.class, pb -> pb
            .description(description)
            .permissions(QbPermissions.HELP_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, Outcome.of(input));
                if (input instanceof ExternalJobCompletion completion && completion.result() != null) {
                    ctx.put(RESULT, completion.result(HelpJob.Result.class));
                }
                if (input instanceof ExternalJobFailure failure && failure.errorCode() != null) {
                    ctx.put(FAILURE_CODE, failure.errorCode());
                }
                return ctx;
            })
            .outputMapper(ctx -> ctx.contains(HelpProcesses.OUTPUT) ? ctx.get(HelpProcesses.OUTPUT, OutcomeOutput.class)
                : new OutcomeOutput(null, null))
            .step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(
                ctx -> QbHelp.threadLock(outcome(ctx).threadId())))
            .step("Take the user's usage", HoldLock.<ProcessContext>exclusive(
                ctx -> QbHelp.usageLock(outcome(ctx).requestedBy())))
            .step("Take everybody's usage", HoldLock.<ProcessContext>exclusive(
                ctx -> QbHelp.usageLock(QbHelp.EVERYBODY)))
            .step("Load the monthly tokens", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                QbParams.HELP_GLOBAL_MONTHLY_TOKENS))
            .step("Load the question and the usage", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbHelp.defaultDataset(QbHelp.THREAD),
                    ctx -> HelpProcesses.byId("threadId", outcome(ctx).threadId()), THREAD),
                QueryEntities.query(QbHelp.defaultDataset(QbHelp.MESSAGE),
                    ctx -> HelpProcesses.byId("messageId", outcome(ctx).messageId()), MESSAGE),
                QueryEntities.query(QbHelp.defaultDataset(QbHelp.USAGE), ctx -> HelpProcesses.usageOf(
                    outcome(ctx).requestedBy(), AiLimits.month(outcome(ctx).finishedTime(), zone)), USER_USAGE),
                QueryEntities.query(QbHelp.defaultDataset(QbHelp.USAGE), ctx -> HelpProcesses.usageOf(
                    QbHelp.EVERYBODY, AiLimits.month(outcome(ctx).finishedTime(), zone)), EVERYBODY_USAGE)))
            .compute("Record the outcome", (metadata, ctx) -> record(ctx, zone, messages, siteHosts))
            .afterCommit("Show the usage on the gauge", HelpUsageGauge.class, NoMetadata.INSTANCE));
    }

    static Outcome outcome(ProcessContext ctx) {
        return ctx.get(INPUT, Outcome.class);
    }

    static void record(ProcessContext ctx, ZoneId zone, MessageCatalog messages, Set<String> siteHosts) {
        Outcome outcome = outcome(ctx);
        EntityInstance thread = HelpProcesses.first(ctx, THREAD);
        EntityInstance question = HelpProcesses.first(ctx, MESSAGE);
        boolean ours = thread != null && question != null && QbHelp.QUESTION.equals(question.get("kind"))
            && outcome.requestedBy().equals(String.valueOf((Object) question.get("userId")))
            && String.valueOf(thread.id()).equals(String.valueOf((Object) question.get("threadId")));
        String model = ours ? (String) question.get("model") : null;
        long reserved = ours ? HelpProcesses.number(question.get("reservedInputTokens"))
            : HelpPrompt.reservedInput(HelpPrompt.SYSTEM, "");
        ClaudeApi.Usage usage = outcome.attemptRan()
            ? charged(outcome.usage(), outcome.unrecordedAttempts(), model, reserved) : new ClaudeApi.Usage(0, 0);
        String servedModel = served(ctx);
        BigDecimal cost = model == null ? null : AiCost.of(model, servedModel, usage, null);
        if (ours && QbHelp.QUEUED.equals(question.get("jobStatus"))) {
            Instant now = HelpProcesses.stamp(ctx.opTime(), thread);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("jobStatus", outcome.kind());
            if (QbHelp.ANSWERED.equals(outcome.kind())) {
                answer(ctx, thread, question, messages, siteHosts, now);
            } else if (QbHelp.FAILED.equals(outcome.kind())) {
                values.put("failureCode", clip(failureCode(ctx), QbHelp.CODE_LENGTH));
            } else {
                values.put("failureCode", HelpCodes.STOPPED);
            }
            ctx.changes().update(QbHelp.MESSAGE, question.id(), question.version(), values);
            ctx.changes().update(QbHelp.THREAD, thread.id(), thread.version(), Map.of("lastActivityAt", now));
            ctx.put(HelpProcesses.OUTPUT, new OutcomeOutput(String.valueOf(question.id()), outcome.kind()));
        }
        String month = AiLimits.month(outcome.finishedTime(), zone);
        if (thread != null) {
            add(ctx, HelpProcesses.first(ctx, USER_USAGE), outcome.requestedBy(), month, outcome.kind(), usage, cost);
        }
        long total = add(ctx, HelpProcesses.first(ctx, EVERYBODY_USAGE), QbHelp.EVERYBODY, month, outcome.kind(),
            usage, cost);
        ctx.put(PERCENT, percent(total, HelpSettings.of(ctx.contains(PARAMS) ? ctx.get(PARAMS, ParamValues.class)
            : null).globalMonthlyTokens()));
    }

    private static String served(ProcessContext ctx) {
        HelpJob.Result result = completionResult(ctx);
        return result == null ? null : result.servedModel();
    }

    /** The completion's result; null for the other outcomes. */
    private static HelpJob.Result completionResult(ProcessContext ctx) {
        return ctx.contains(RESULT) ? ctx.get(RESULT, HelpJob.Result.class) : null;
    }

    static final String RESULT = "result";
    static final String FAILURE_CODE = "failureCode";

    private static String failureCode(ProcessContext ctx) {
        return ctx.contains(FAILURE_CODE) ? (String) ctx.get(FAILURE_CODE) : null;
    }

    /** Writes the AI's answer, checked: the site's links only; a quiz request becomes the refusal. */
    private static void answer(ProcessContext ctx, EntityInstance thread, EntityInstance question,
        MessageCatalog messages, Set<String> siteHosts, Instant now) {
        HelpJob.Result result = completionResult(ctx);
        Locale locale = Locale.of(String.valueOf((Object) thread.get("language")));
        String body;
        List<String> links = List.of();
        String failure = null;
        if (result == null || result.quizRequest()) {
            body = text(messages, QUIZ_REFUSAL, locale);
            failure = HelpCodes.QUIZ_REFUSED;
        } else {
            body = HelpAnswers.clip(HelpAnswers.text(result.answer(), siteHosts), QbHelp.ANSWER_LENGTH);
            links = HelpAnswers.links(result.links(), siteHosts);
            if (body.isBlank()) {
                body = text(messages, NO_ANSWER, locale);
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("threadId", String.valueOf(thread.id()));
        values.put("userId", String.valueOf((Object) thread.get("userId")));
        values.put("kind", QbHelp.ANSWER);
        values.put("body", body);
        values.put("links", HelpAnswers.joined(links));
        values.put("page", question.get("page"));
        values.put("failureCode", failure);
        values.put("createdAt", now);
        ctx.changes().insert(QbHelp.MESSAGE, values);
    }

    private static String text(MessageCatalog messages, String code, Locale locale) {
        return messages == null ? code : messages.find(code, locale).or(() -> messages.find(code, Locale.ENGLISH))
            .orElse(code);
    }

    /**
     * What a finished job is charged: the usage its attempts recorded, and for each attempt that recorded none the
     * question's reserved input and all the output one attempt asks for ({@link ClaudeApi#unrecorded}).
     */
    static ClaudeApi.Usage charged(Map<String, Long> metrics, int unrecordedAttempts, String model, long reserved) {
        ClaudeApi.Usage recorded = new ClaudeApi.Usage(metrics.getOrDefault(AiQuestionsJob.INPUT_TOKENS, 0L),
            metrics.getOrDefault(AiQuestionsJob.OUTPUT_TOKENS, 0L),
            metrics.getOrDefault(AiQuestionsJob.FALLBACK_INPUT_TOKENS, 0L),
            metrics.getOrDefault(AiQuestionsJob.FALLBACK_OUTPUT_TOKENS, 0L),
            metrics.getOrDefault(AiQuestionsJob.LONG_INPUT_TOKENS, 0L),
            metrics.getOrDefault(AiQuestionsJob.LONG_OUTPUT_TOKENS, 0L));
        if (unrecordedAttempts <= 0) {
            return recorded;
        }
        ClaudeApi.Usage each = ClaudeCalls.priced(model, ClaudeApi.unrecorded(reserved, HelpJob.MAX_TOKENS));
        long n = unrecordedAttempts;
        return new ClaudeApi.Usage(recorded.inputTokens() + n * each.inputTokens(),
            recorded.outputTokens() + n * each.outputTokens(), recorded.fallbackInputTokens(),
            recorded.fallbackOutputTokens(), recorded.longPromptInputTokens() + n * each.longPromptInputTokens(),
            recorded.longPromptOutputTokens() + n * each.longPromptOutputTokens());
    }

    /** Adds the job to a usage row (made the first time); returns the row's tokens after it. */
    private static long add(ProcessContext ctx, EntityInstance row, String userId, String month, String kind,
        ClaudeApi.Usage usage, BigDecimal cost) {
        long in = usage.inputTokens();
        long out = usage.outputTokens();
        boolean failed = QbHelp.FAILED.equals(kind);
        boolean cancelled = QbHelp.CANCELLED.equals(kind);
        if (row == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("userId", userId);
            values.put("month", month);
            values.put("questions", BigDecimal.ONE);
            values.put("failedJobs", failed ? BigDecimal.ONE : BigDecimal.ZERO);
            values.put("cancelledJobs", cancelled ? BigDecimal.ONE : BigDecimal.ZERO);
            values.put("inputTokens", BigDecimal.valueOf(in));
            values.put("outputTokens", BigDecimal.valueOf(out));
            values.put("costUsd", cost);
            ctx.changes().insert(QbHelp.USAGE, values);
            return in + out;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("questions", BigDecimal.valueOf(HelpProcesses.number(row.get("questions")) + 1));
        if (failed) {
            values.put("failedJobs", BigDecimal.valueOf(HelpProcesses.number(row.get("failedJobs")) + 1));
        }
        if (cancelled) {
            values.put("cancelledJobs", BigDecimal.valueOf(HelpProcesses.number(row.get("cancelledJobs")) + 1));
        }
        long tokensIn = HelpProcesses.number(row.get("inputTokens")) + in;
        long tokensOut = HelpProcesses.number(row.get("outputTokens")) + out;
        values.put("inputTokens", BigDecimal.valueOf(tokensIn));
        values.put("outputTokens", BigDecimal.valueOf(tokensOut));
        if (cost != null) {
            Object before = row.get("costUsd");
            values.put("costUsd", (before == null ? BigDecimal.ZERO : new BigDecimal(before.toString())).add(cost));
        }
        ctx.changes().update(QbHelp.USAGE, row.id(), row.version(), values);
        return tokensIn + tokensOut;
    }

    /** The share of the monthly tokens used, in whole percent (100 and above: reached). */
    static long percent(long used, long monthly) {
        if (monthly <= 0) {
            return used > 0 ? 100 : 0;
        }
        return Math.min(1_000, used * 100 / monthly);
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
