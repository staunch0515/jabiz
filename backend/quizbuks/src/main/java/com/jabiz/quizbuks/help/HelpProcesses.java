package com.jabiz.quizbuks.help;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ai.AiLimits;
import com.jabiz.quizbuks.content.ai.AiUserKeys;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RequireScope;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The AI help's processes for people (F-C10, docs/quizbuks/plans/impl/F-C10.md):
 * <ul>
 *   <li>{@value #ASK}: a Taker or a Sponsor asks, in a new conversation or the same one. Once the conversation was
 *       handed to a person the words go to the person (no AI, nothing counted). Otherwise: the switch, the person's
 *       questions today (under the person's own lock, never everybody's) and everybody's tokens this month (the usage
 *       recorded, read without a lock: it may go a little over), then the question and its job {@value HelpJob#TYPE};
 *       </li>
 *   <li>{@value #RATE}: the user rates an AI answer or a person's reply; "solved" on a conversation handed to a person
 *       closes it and its tasks;</li>
 *   <li>{@value #ESCALATE}: the user hands the conversation (or, with none, the question) to a person: a Sponsor's to
 *       its operator (the task in the operator's entry scope), a direct Sponsor's and a Taker's to headquarters;</li>
 *   <li>{@value #REPLY}: a person (content administrators, the operators' members within their scope) replies in the
 *       same conversation, the user hearing it in the inbox, and may close it;</li>
 *   <li>{@value #CLOSE}: the user closes the conversation, and its tasks.</li>
 * </ul>
 * Every process that changes a conversation takes its lock. Nothing reads or sends the user's other data: the job has
 * the question, the latest turns, the page's name (from {@link HelpPages}) and the user's anonymous key only.
 */
public final class HelpProcesses {

    public static final String ASK = "QB_HELP_ASK";
    public static final String RATE = "QB_HELP_RATE";
    public static final String ESCALATE = "QB_HELP_ESCALATE";
    public static final String REPLY = "QB_HELP_REPLY";
    public static final String CLOSE = "QB_HELP_CLOSE";

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String OUTPUT = "output";
    static final String THREAD = "thread";
    static final String RECENT = "recent";
    static final String TODAY = "today";
    static final String EVERYBODY = "everybody";
    static final String JOB_INPUT = "jobInput";
    static final String MATERIAL = "material";
    static final String MATERIAL_FILE = "materialFile";
    static final String JOB_ID = "jobId";
    static final String REFERENCE = "reference";
    static final String ASSIGNMENT = "assignment";
    static final String DIRECT_OF = "directOf";
    static final String TASK = "task";
    static final String CLOSING = "closing";
    static final String REMINDER = "reminder";
    static final String REPLIED = "replied";

    /** The pattern of the entries and the languages. */
    static final String ENTRY_PATTERN = "app|sponsor";
    static final String LANGUAGE_PATTERN = "zh|ja|en";

    /**
     * @param threadId the conversation to go on with; none for a new one
     * @param entry    {@code app} (a Taker's) or {@code sponsor} (a Sponsor's)
     * @param page     the page's name (dropped unless on {@link HelpPages}' list)
     * @param text     the question
     */
    public record AskInput(@Size(max = QbHelp.ID_LENGTH) String threadId,
        @NotBlank @Pattern(regexp = ENTRY_PATTERN) String entry,
        @NotBlank @Pattern(regexp = LANGUAGE_PATTERN) String language,
        @Size(max = QbHelp.PAGE_LENGTH) String page,
        @NotBlank @Size(max = QbHelp.TEXT_LENGTH) String text) {}

    /**
     * @param jobStatus {@code QUEUED} when the AI was asked (the pages ask for the answer every
     *                  {@code qb.help.poll-seconds}); null when the words went to a person
     */
    public record AskOutput(String threadId, String messageId, String status, String jobStatus) {}

    public record RateInput(@NotBlank @Size(max = QbHelp.ID_LENGTH) String threadId,
        @NotBlank @Size(max = QbHelp.ID_LENGTH) String messageId,
        @NotBlank @Pattern(regexp = "SOLVED|UNSOLVED") String rating) {}

    /**
     * @param threadId the conversation to hand over; none to hand the question over directly (the AI off or its limits
     *                 reached), then {@code text} is required
     */
    public record EscalateInput(@Size(max = QbHelp.ID_LENGTH) String threadId,
        @NotBlank @Pattern(regexp = ENTRY_PATTERN) String entry,
        @NotBlank @Pattern(regexp = LANGUAGE_PATTERN) String language,
        @Size(max = QbHelp.PAGE_LENGTH) String page,
        @Size(max = QbHelp.TEXT_LENGTH) String text) {}

    /** @param close whether the conversation closes (after the reply, if there is one) */
    public record ReplyInput(@NotBlank @Size(max = QbHelp.ID_LENGTH) String threadId,
        @Size(max = QbHelp.TEXT_LENGTH) String text, boolean close) {}

    public record ThreadRef(@NotBlank @Size(max = QbHelp.ID_LENGTH) String threadId) {}

    /** @param messageId the message written, if any */
    public record ThreadOutput(String threadId, String messageId, String status) {}

    private HelpProcesses() {}

    // ---- ask -------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AskInput, AskOutput, ProcessContext> ask(ZoneId zone, AiUserKeys userKeys,
        JsonMapper json) {
        return ProcessDefinition.define(ASK, 1, AskInput.class, AskOutput.class, ProcessContext.class, pb -> {
            user(pb, "Asks the AI help how to use the platform, or writes to the person a conversation was handed to.");
            pb.step("Load the help's parameters", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.HELP.toArray(String[]::new)))
                .step("Take the person's turn", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbHelp.userLock(ctx.request().actorId())))
                .step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbHelp.threadLock(askInput(ctx).threadId())))
                .step("Load the conversation, today's questions and the month's usage", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbHelp.defaultDataset(QbHelp.THREAD),
                        ctx -> byId("threadId", askInput(ctx).threadId()), THREAD),
                    QueryEntities.query(QbHelp.defaultDataset(QbHelp.MESSAGE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("threadId", ids(askInput(ctx).threadId())),
                            new QueryPredicate.In("kind", List.of(QbHelp.QUESTION, QbHelp.ANSWER)))))
                        .orderBy("createdAt", false).limit(settings(ctx).historyTurns() * 2 + 2).build(), RECENT),
                    QueryEntities.query(QbHelp.defaultDataset(QbHelp.MESSAGE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("userId", ctx.request().actorId()),
                            new QueryPredicate.Eq("kind", QbHelp.QUESTION),
                            new QueryPredicate.Gte("createdAt", AiLimits.dayStart(ctx.opTime(), zone)))))
                        .limit(settings(ctx).userDaily() + 1).build(), TODAY),
                    QueryEntities.query(QbHelp.defaultDataset(QbHelp.USAGE), ctx -> usageOf(QbHelp.EVERYBODY,
                        AiLimits.month(ctx.opTime(), zone)), EVERYBODY),
                    // F-C10b: the snapshot of the entry's and language's materials in use, the job's file.
                    QueryEntities.query(QbHelpMaterials.defaultDataset(QbHelpMaterials.MATERIAL),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("entry", askInput(ctx).entry()),
                            new QueryPredicate.Eq("language", askInput(ctx).language())))).limit(1).build(),
                        MATERIAL)))
                .compute("Ask", (metadata, ctx) -> asked(ctx, userKeys, json))
                .step("Start the job", StartExternalJob.<ProcessContext>of(HelpJob.TYPE, ctx -> ctx.get(JOB_INPUT))
                    .when(ctx -> ctx.contains(JOB_INPUT))
                    .reference(ctx -> (String) ctx.get(REFERENCE))
                    .files(ctx -> ctx.contains(MATERIAL_FILE) ? List.of((UUID) ctx.get(MATERIAL_FILE)) : List.of())
                    .outputKey(JOB_ID));
        });
    }

    static void asked(ProcessContext ctx, AiUserKeys userKeys, JsonMapper json) {
        AskInput input = askInput(ctx);
        HelpSettings settings = settings(ctx);
        String actor = ctx.request().actorId();
        requireEntry(ctx, input.entry());
        EntityInstance thread = ownThread(ctx, input.threadId(), input.entry());
        Instant now = stamp(ctx.opTime(), thread);
        String text = input.text().strip();
        requireLength(text, settings);
        if (thread != null && QbHelp.CLOSED.equals(thread.get("status"))) {
            throw refused(HelpCodes.CLOSED, "The conversation is closed", Map.of());
        }
        if (thread != null && QbHelp.ESCALATED.equals(thread.get("status"))) {
            // With a person now: the words go to the person, no AI and nothing counted.
            Object messageId = insertMessage(ctx, thread.id(), QbHelp.USER, text, null, null, now);
            Map<String, Object> values = waitForStaff(thread, now);
            values.put("language", input.language());
            ctx.changes().update(QbHelp.THREAD, thread.id(), thread.version(), values);
            ctx.put(OUTPUT, new AskOutput(String.valueOf(thread.id()), String.valueOf(messageId), QbHelp.ESCALATED,
                null));
            return;
        }
        if (!settings.enabled()) {
            throw refused(HelpCodes.DISABLED, "The AI help is switched off", Map.of());
        }
        if (settings.model() == null) {
            throw refused(HelpCodes.MODEL, "qb.help.model names a model the platform does not allow", Map.of());
        }
        List<EntityInstance> recent = list(ctx, RECENT);
        if (thread != null && !recent.isEmpty() && QbHelp.QUESTION.equals(recent.getFirst().get("kind"))
            && QbHelp.QUEUED.equals(recent.getFirst().get("jobStatus"))) {
            throw refused(HelpCodes.WAITING, "The previous question is still being answered", Map.of());
        }
        if (list(ctx, TODAY).size() >= settings.userDaily()) {
            throw refused(HelpCodes.DAILY_LIMIT, "The caller asked " + settings.userDaily() + " questions today",
                Map.of("limit", settings.userDaily()));
        }
        EntityInstance everybody = first(ctx, EVERYBODY);
        long used = everybody == null ? 0 : settings.counted(number(everybody.get("inputTokens")),
            number(everybody.get("outputTokens")), number(everybody.get("cacheReadTokens")));
        if (used >= settings.globalMonthlyTokens()) {
            throw refused(HelpCodes.MONTHLY_LIMIT, "The AI help's tokens of the month are used up", Map.of());
        }
        String page = HelpPages.allowed(input.entry(), input.page());
        Object threadId;
        if (thread == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("userId", actor);
            values.put("entry", input.entry());
            values.put("language", input.language());
            values.put("startPage", page);
            values.put("status", QbHelp.OPEN);
            values.put("escalated", false);
            values.put("createdAt", now);
            values.put("lastActivityAt", now);
            threadId = ctx.changes().insert(QbHelp.THREAD, values);
        } else {
            threadId = thread.id();
            ctx.changes().update(QbHelp.THREAD, thread.id(), thread.version(), Map.of("language", input.language(),
                "lastActivityAt", now));
        }
        HelpJob.Input draft = new HelpJob.Input(settings.model(), input.entry(), input.language(), page,
            history(recent, settings.historyTurns()), text, userKeys.of(actor), 0);
        // F-C10b: the materials are the job's file (not copied into its input); the reservation counts their size.
        EntityInstance material = first(ctx, MATERIAL);
        UUID materialFile = material == null ? null : fileId(material.get("fileId"));
        long materialBytes = materialFile == null ? 0 : number(material.get("bytes"));
        if (materialFile != null) {
            ctx.put(MATERIAL_FILE, materialFile);
        }
        long reserved = HelpPrompt.reservedInput(HelpPrompt.SYSTEM, materialBytes, HelpPrompt.user(draft, json));
        HelpJob.Input jobInput = new HelpJob.Input(draft.model(), draft.entry(), draft.language(), draft.page(),
            draft.history(), draft.question(), draft.userKey(), reserved);
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("threadId", String.valueOf(threadId));
        question.put("userId", actor);
        question.put("kind", QbHelp.QUESTION);
        question.put("body", text);
        question.put("page", page);
        question.put("jobStatus", QbHelp.QUEUED);
        question.put("model", settings.model());
        question.put("reservedInputTokens", BigDecimal.valueOf(reserved));
        question.put("createdAt", now);
        Object messageId = ctx.changes().insert(QbHelp.MESSAGE, question);
        ctx.put(JOB_INPUT, jobInput);
        ctx.put(REFERENCE, QbHelp.reference(threadId, messageId));
        ctx.put(OUTPUT, new AskOutput(String.valueOf(threadId), String.valueOf(messageId), QbHelp.OPEN,
            QbHelp.QUEUED));
    }

    /**
     * The latest {@code turns} questions with their answers, oldest first, from the conversation's latest questions
     * and answers (newest first); a question without its answer (failed, cancelled) is left out.
     */
    static List<HelpJob.Turn> history(List<EntityInstance> newestFirst, int turns) {
        List<EntityInstance> chronological = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(chronological);
        List<HelpJob.Turn> all = new ArrayList<>();
        for (int i = 0; i + 1 < chronological.size(); i++) {
            EntityInstance question = chronological.get(i);
            EntityInstance answer = chronological.get(i + 1);
            if (QbHelp.QUESTION.equals(question.get("kind")) && QbHelp.ANSWER.equals(answer.get("kind"))) {
                all.add(new HelpJob.Turn(String.valueOf((Object) question.get("body")),
                    String.valueOf((Object) answer.get("body"))));
                i++;
            }
        }
        return all.size() <= turns ? List.copyOf(all) : List.copyOf(all.subList(all.size() - turns, all.size()));
    }

    // ---- rate ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<RateInput, ThreadOutput, ProcessContext> rate() {
        return ProcessDefinition.define(RATE, 1, RateInput.class, ThreadOutput.class, ProcessContext.class, pb -> {
            user(pb, "Rates an AI help answer or a person's reply as solved or not.");
            pb.step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbHelp.threadLock(ctx.get(INPUT, RateInput.class).threadId())))
                .step("Load the conversation and the message", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbHelp.defaultDataset(QbHelp.THREAD),
                        ctx -> byId("threadId", ctx.get(INPUT, RateInput.class).threadId()), THREAD),
                    QueryEntities.query(QbHelp.defaultDataset(QbHelp.MESSAGE),
                        ctx -> byId("messageId", ctx.get(INPUT, RateInput.class).messageId()), RECENT)))
                .compute("Rate", (metadata, ctx) -> rated(ctx));
            closeTasks(pb);
        });
    }

    static void rated(ProcessContext ctx) {
        RateInput input = ctx.get(INPUT, RateInput.class);
        EntityInstance thread = ownThread(ctx, input.threadId(), null);
        EntityInstance message = first(ctx, RECENT);
        if (message == null || !String.valueOf(thread.id()).equals(String.valueOf((Object) message.get("threadId")))) {
            throw new EntityNotFoundException(QbHelp.MESSAGE + " " + input.messageId() + " not found");
        }
        if (!QbHelp.ANSWER.equals(message.get("kind")) && !QbHelp.STAFF.equals(message.get("kind"))) {
            throw refused(HelpCodes.NOT_RATABLE, "Only an answer or a reply is rated", Map.of());
        }
        Instant now = stamp(ctx.opTime(), thread);
        ctx.changes().update(QbHelp.MESSAGE, message.id(), message.version(), Map.of("rating", input.rating(),
            "ratedAt", now));
        boolean solved = QbHelp.SOLVED.equals(input.rating());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("resolved", solved);
        values.put("lastActivityAt", now);
        if (solved && QbHelp.ESCALATED.equals(thread.get("status"))) {
            closing(ctx, thread, values, QbHelp.CLOSED_RESOLVED, now);
        }
        ctx.changes().update(QbHelp.THREAD, thread.id(), thread.version(), values);
        ctx.put(OUTPUT, new ThreadOutput(String.valueOf(thread.id()), String.valueOf(message.id()),
            String.valueOf(values.getOrDefault("status", thread.get("status")))));
    }

    // ---- escalate --------------------------------------------------------------------------------------------------

    public static ProcessDefinition<EscalateInput, ThreadOutput, ProcessContext> escalate() {
        return ProcessDefinition.define(ESCALATE, 1, EscalateInput.class, ThreadOutput.class, ProcessContext.class,
            pb -> {
                user(pb, "Hands an AI help conversation, or a question, to a person: a Sponsor's to its operator, a "
                    + "direct Sponsor's and a Taker's to headquarters.");
                pb.step("Load the questions' length", LoadParams.<ProcessContext>optional(ProcessContext::opTime,
                        PARAMS, QbParams.HELP_QUESTION_MAX_LENGTH))
                    .step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbHelp.threadLock(ctx.get(INPUT, EscalateInput.class).threadId())))
                    .step("Load the conversation and who looks after the caller", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbHelp.defaultDataset(QbHelp.THREAD),
                            ctx -> byId("threadId", ctx.get(INPUT, EscalateInput.class).threadId()), THREAD),
                        QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> byId("userId",
                            sponsorEntry(ctx) ? ctx.request().actorId() : null), ASSIGNMENT),
                        QueryEntities.query(QbOperators.defaultDataset(QbOperators.OPERATOR), ctx -> byId(
                            "directSponsorId", sponsorEntry(ctx) ? ctx.request().actorId() : null), DIRECT_OF)))
                    .compute("Hand it over", (metadata, ctx) -> escalated(ctx))
                    .step("Ask a person", CreateTask.<ProcessContext>when(ctx -> ctx.contains(TASK),
                        ctx -> ctx.get(TASK, TaskSpec.class)));
            });
    }

    private static boolean sponsorEntry(ProcessContext ctx) {
        return QbHelp.SPONSOR.equals(ctx.get(INPUT, EscalateInput.class).entry());
    }

    static void escalated(ProcessContext ctx) {
        EscalateInput input = ctx.get(INPUT, EscalateInput.class);
        requireEntry(ctx, input.entry());
        EntityInstance thread = ownThread(ctx, input.threadId(), input.entry());
        String text = input.text() == null || input.text().isBlank() ? null : input.text().strip();
        Instant now = stamp(ctx.opTime(), thread);
        if (thread != null && QbHelp.CLOSED.equals(thread.get("status"))) {
            throw refused(HelpCodes.CLOSED, "The conversation is closed", Map.of());
        }
        if (thread != null && QbHelp.ESCALATED.equals(thread.get("status"))) {
            throw refused(HelpCodes.ALREADY_ESCALATED, "The conversation was handed to a person already", Map.of());
        }
        if (thread == null && text == null) {
            throw refused(HelpCodes.TEXT_REQUIRED, "Write the question to hand over", Map.of());
        }
        if (text != null) {
            requireLength(text, settings(ctx));
        }
        String operatorId = operatorOf(ctx, input.entry());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbHelp.ESCALATED);
        values.put("escalated", true);
        values.put("escalatedAt", now);
        values.put("operatorId", operatorId);
        values.put("waitingFor", QbHelp.WAIT_STAFF);
        values.put("waitingSince", now);
        values.put("language", input.language());
        values.put("lastActivityAt", now);
        Object threadId;
        if (thread == null) {
            values.put("userId", ctx.request().actorId());
            values.put("entry", input.entry());
            values.put("startPage", HelpPages.allowed(input.entry(), input.page()));
            values.put("createdAt", now);
            threadId = ctx.changes().insert(QbHelp.THREAD, values);
        } else {
            threadId = thread.id();
            ctx.changes().update(QbHelp.THREAD, thread.id(), thread.version(), values);
        }
        Object messageId = text == null ? null
            : insertMessage(ctx, threadId, QbHelp.USER, text, null, HelpPages.allowed(input.entry(), input.page()),
                now);
        ctx.put(TASK, task(QbHelp.TASK, "task.qb.help.escalated." + input.entry(), threadId, operatorId, null));
        ctx.put(OUTPUT, new ThreadOutput(String.valueOf(threadId), messageId == null ? null : String.valueOf(messageId),
            QbHelp.ESCALATED));
    }

    /**
     * The operator a conversation goes to: a Sponsor's operator, unless the Sponsor is an operator's direct Sponsor
     * (its own lead) or has none; then, as for a Taker, headquarters (null).
     */
    static String operatorOf(ProcessContext ctx, String entry) {
        if (!QbHelp.SPONSOR.equals(entry) || first(ctx, DIRECT_OF) != null) {
            return null;
        }
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        Object operator = assignment == null ? null : assignment.get("operatorId");
        return operator == null ? null : String.valueOf(operator);
    }

    /** A task to the people who answer the conversation: its operator's (in its scope), else headquarters'. */
    static TaskSpec task(String type, String title, Object threadId, String operatorId, String source) {
        TaskSpec task = TaskSpec.forPermission(type, title, Map.of(), QbPermissions.ADMIN_HELP)
            .about(QbHelp.THREAD, String.valueOf(threadId))
            .link("/qb/help/" + threadId)
            .source(source == null ? QbHelp.taskSource(threadId) : source);
        return operatorId == null ? task : task.scope(QbOperators.SCOPE, operatorId);
    }

    // ---- reply -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ReplyInput, ThreadOutput, ProcessContext> reply() {
        return ProcessDefinition.define(REPLY, 1, ReplyInput.class, ThreadOutput.class, ProcessContext.class, pb -> {
            pb.description("Replies, as a person, in an AI help conversation handed over, and may close it.")
                .permissions(QbPermissions.ADMIN_HELP)
                .contextFactory(HelpProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, ThreadOutput.class))
                .step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbHelp.threadLock(ctx.get(INPUT, ReplyInput.class).threadId())))
                .step("Load the conversation", QueryEntities.<ProcessContext>of(QbHelp.defaultDataset(QbHelp.THREAD),
                    ctx -> byId("threadId", ctx.get(INPUT, ReplyInput.class).threadId()), THREAD))
                .compute("Refuse a conversation not found", (metadata, ctx) -> {
                    if (first(ctx, THREAD) == null) {
                        throw notFound(ctx.get(INPUT, ReplyInput.class).threadId());
                    }
                })
                // An operator's member answers only what was handed to its operator; headquarters' are not theirs.
                .step("Within the operator's scope", RequireScope.<ProcessContext>of(QbOperators.SCOPE,
                    ctx -> (String) first(ctx, THREAD).get("operatorId"),
                    ctx -> QbHelp.THREAD + " " + ctx.get(INPUT, ReplyInput.class).threadId()))
                .compute("Reply", (metadata, ctx) -> replied(ctx))
                .step("Tell the user", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(REPLIED),
                    QbHelp.REPLIED_EVENT, ctx -> ctx.get(REPLIED)));
            closeTasks(pb);
        });
    }

    static void replied(ProcessContext ctx) {
        ReplyInput input = ctx.get(INPUT, ReplyInput.class);
        EntityInstance thread = first(ctx, THREAD);
        String text = input.text() == null || input.text().isBlank() ? null : input.text().strip();
        if (QbHelp.CLOSED.equals(thread.get("status"))) {
            throw refused(HelpCodes.CLOSED, "The conversation is closed", Map.of());
        }
        if (!QbHelp.ESCALATED.equals(thread.get("status"))) {
            throw refused(HelpCodes.NOT_ESCALATED, "The conversation was not handed to a person", Map.of());
        }
        if (text == null && !input.close()) {
            throw refused(HelpCodes.TEXT_REQUIRED, "Write the reply", Map.of());
        }
        Instant now = stamp(ctx.opTime(), thread);
        Map<String, Object> values = new LinkedHashMap<>();
        Object messageId = null;
        Instant since = instant(thread.get("waitingSince"));
        if (QbHelp.WAIT_STAFF.equals(thread.get("waitingFor")) && since != null) {
            // Its reminder, if one was made, is done: someone answered.
            ctx.put(REMINDER, QbHelp.remindSource(thread.id(), since.getEpochSecond()));
        }
        if (text != null) {
            messageId = insertMessage(ctx, thread.id(), QbHelp.STAFF, text, null, null, now);
            values.put("waitingFor", QbHelp.WAIT_USER);
            values.put("waitingSince", now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("threadId", String.valueOf(thread.id()));
            payload.put("messageId", String.valueOf(messageId));
            payload.put("userId", String.valueOf((Object) thread.get("userId")));
            ctx.put(REPLIED, payload);
        }
        values.put("lastActivityAt", now);
        if (input.close()) {
            closing(ctx, thread, values, QbHelp.CLOSED_BY_STAFF, now);
        }
        ctx.changes().update(QbHelp.THREAD, thread.id(), thread.version(), values);
        ctx.put(OUTPUT, new ThreadOutput(String.valueOf(thread.id()), messageId == null ? null
            : String.valueOf(messageId), String.valueOf(values.getOrDefault("status", thread.get("status")))));
    }

    // ---- close -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ThreadRef, ThreadOutput, ProcessContext> close() {
        return ProcessDefinition.define(CLOSE, 1, ThreadRef.class, ThreadOutput.class, ProcessContext.class, pb -> {
            user(pb, "Closes one of the caller's AI help conversations.");
            pb.step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbHelp.threadLock(ctx.get(INPUT, ThreadRef.class).threadId())))
                .step("Load the conversation", QueryEntities.<ProcessContext>of(QbHelp.defaultDataset(QbHelp.THREAD),
                    ctx -> byId("threadId", ctx.get(INPUT, ThreadRef.class).threadId()), THREAD))
                .compute("Close", (metadata, ctx) -> {
                    EntityInstance thread = ownThread(ctx, ctx.get(INPUT, ThreadRef.class).threadId(), null);
                    if (!QbHelp.CLOSED.equals(thread.get("status"))) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("lastActivityAt", ctx.opTime());
                        closing(ctx, thread, values, QbHelp.CLOSED_BY_USER, ctx.opTime());
                        ctx.changes().update(QbHelp.THREAD, thread.id(), thread.version(), values);
                    }
                    ctx.put(OUTPUT, new ThreadOutput(String.valueOf(thread.id()), null, QbHelp.CLOSED));
                });
            closeTasks(pb);
        });
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /** A user's process: the permission, a verified address, not banned. */
    private static <I, O> void user(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String description) {
        pb.description(description)
            .permissions(QbPermissions.HELP_USE)
            .requiresVerifiedEmail()
            .contextFactory(HelpProcesses::context)
            .outputMapper(ctx -> {
                @SuppressWarnings("unchecked")
                O output = (O) ctx.get(OUTPUT);
                return output;
            });
        CallerChecks.refuseBanned(pb);
    }

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    /** The steps closing a conversation's tasks, when the process closed it or someone answered its reminder. */
    static <I, O> void closeTasks(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Close its task", CloseTasks.<ProcessContext>done(ctx -> ctx.contains(CLOSING)
                ? QbHelp.taskSource(ctx.get(CLOSING)) : "qb.help:none"))
            .step("Close its reminder", CloseTasks.<ProcessContext>done(ctx -> ctx.contains(REMINDER)
                ? (String) ctx.get(REMINDER) : "qb.help-remind:none"));
    }

    /**
     * Closes the conversation in {@code values} and names its tasks to close: the task of its handing over, and the
     * reminder of what it waits for now.
     */
    static void closing(ProcessContext ctx, EntityInstance thread, Map<String, Object> values, String reason,
        Instant now) {
        values.put("status", QbHelp.CLOSED);
        values.put("closedAt", now);
        values.put("closeReason", reason);
        values.put("waitingFor", null);
        ctx.put(CLOSING, String.valueOf(thread.id()));
        Instant since = instant(thread.get("waitingSince"));
        if (QbHelp.WAIT_STAFF.equals(thread.get("waitingFor")) && since != null) {
            ctx.put(REMINDER, QbHelp.remindSource(thread.id(), since.getEpochSecond()));
        }
    }

    /** A conversation now waiting for a person: since now, unless it waited already. */
    static Map<String, Object> waitForStaff(EntityInstance thread, Instant now) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!QbHelp.WAIT_STAFF.equals(thread.get("waitingFor"))) {
            values.put("waitingFor", QbHelp.WAIT_STAFF);
            values.put("waitingSince", now);
        }
        values.put("lastActivityAt", now);
        return values;
    }

    static Object insertMessage(ProcessContext ctx, Object threadId, String kind, String body, String links,
        String page, Instant now) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("threadId", String.valueOf(threadId));
        values.put("userId", ctx.request().actorId());
        values.put("kind", kind);
        values.put("body", body);
        values.put("links", links);
        values.put("page", page);
        values.put("createdAt", now);
        if (QbHelp.STAFF.equals(kind)) {
            // A person's reply belongs to the conversation's user (their scope, their erasure), not to who wrote it.
            values.put("userId", ctx.contains(THREAD) && first(ctx, THREAD) != null
                ? String.valueOf((Object) first(ctx, THREAD).get("userId")) : ctx.request().actorId());
        }
        return ctx.changes().insert(QbHelp.MESSAGE, values);
    }

    /**
     * The caller's own conversation of the entry (any entry when null); null when none was named; 404 when it is not
     * found or not the caller's.
     */
    static EntityInstance ownThread(ProcessContext ctx, String threadId, String entry) {
        if (threadId == null) {
            return null;
        }
        EntityInstance thread = first(ctx, THREAD);
        if (thread == null || !ctx.request().actorId().equals(String.valueOf((Object) thread.get("userId")))
            || entry != null && !entry.equals(thread.get("entry"))) {
            throw notFound(threadId);
        }
        return thread;
    }

    /** A user's text within {@code qb.help.question-max-length} characters (code points). */
    static void requireLength(String text, HelpSettings settings) {
        if (text.codePointCount(0, text.length()) > settings.questionMaxLength()) {
            throw refused(HelpCodes.TEXT_TOO_LONG, "The text is longer than " + settings.questionMaxLength()
                + " characters", Map.of("max", settings.questionMaxLength()));
        }
    }

    /** The entry must be the caller's: a Sponsor asks from {@code sponsor}, a Taker from {@code app}. */
    static void requireEntry(ProcessContext ctx, String entry) {
        String permission = QbHelp.SPONSOR.equals(entry) ? QbPermissions.SPONSOR_ME : QbPermissions.PLAY;
        if (!ctx.request().hasPermission(permission)) {
            throw refused(HelpCodes.ENTRY, "The caller does not use the entry " + entry, Map.of("entry", entry));
        }
    }

    /** A file field's value as an id; null when there is none. */
    static UUID fileId(Object value) {
        return value == null ? null : value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    static EntityQuery byId(String field, Object id) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, ids(id))).limit(1).build();
    }

    static List<Object> ids(Object id) {
        return id == null ? List.of() : List.of(String.valueOf(id));
    }

    static EntityQuery usageOf(String userId, String month) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("userId", userId),
            new QueryPredicate.Eq("month", month)))).limit(1).build();
    }

    static HelpSettings settings(ProcessContext ctx) {
        return HelpSettings.of(ctx.contains(PARAMS) ? ctx.get(PARAMS, ParamValues.class) : null);
    }

    static AskInput askInput(ProcessContext ctx) {
        return ctx.get(INPUT, AskInput.class);
    }

    static BusinessRuleViolationException refused(String code, String message, Map<String, Object> params) {
        return new BusinessRuleViolationException(new Violation(null, code, message, params));
    }

    static EntityNotFoundException notFound(String threadId) {
        return new EntityNotFoundException(QbHelp.THREAD + " " + threadId + " not found");
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.contains(key) ? ctx.get(key) : null;
        return value == null ? List.of() : (List<EntityInstance>) value;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    static long number(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).longValue();
    }

    static String text(Object value) {
        return value == null ? null : Objects.toString(value);
    }

    /**
     * The time a change of the conversation is written at: now, or just after its last activity when that is not
     * earlier (the clock may stand still between two acts), so that its messages keep their order.
     */
    static Instant stamp(Instant now, EntityInstance thread) {
        Instant last = thread == null ? null : instant(thread.get("lastActivityAt"));
        return last == null || last.isBefore(now) ? now : last.plusMillis(1);
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof java.time.OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }
}
