package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.MaterialKind;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizEditing;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.publishing.SponsorChecks;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.StartExternalJob;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The processes of AI generation of questions (docs/quizbuks/plans/Q8-ai.md, Q8a, D-Q8-4):
 * <ul>
 *   <li>{@value #REQUEST}: a sponsor asks for candidates of one of their quizzes. Before any lock: their onboarding
 *       and ban, the AI notice (accepted now when the request names the version in effect, A-11), the settings, the
 *       quiz through their own dataset (another's is 404) and the chosen materials, the input built and measured
 *       (at most {@value ClaudeApi#MAX_INPUT_BYTES} bytes, refused rather than cut, A-5). Under
 *       {@code qb.ai:<sponsor>} then {@code qb.ai:*}: the limits ({@link AiLimits}), everybody's unfinished jobs
 *       counted; then the job and its record.</li>
 *   <li>{@value #COMPLETED} / {@value #FAILED} ({@code internal()}): under {@code qb.ai:<sponsor>} then
 *       {@code qb.ai:*}, the usage of every attempt is added to the sponsor's month and everybody's; the candidates are
 *       checked against the content rules again and wait for review ({@code READY}, for
 *       {@code qb.ai.candidates.expire-days}), or the failure is recorded. A cancelled job keeps its state; its usage
 *       still counts.</li>
 *   <li>{@value #ACCEPT}: under {@code qb.quiz:<quiz>} then {@code qb.ai:<sponsor>}, the questions the sponsor reviewed
 *       (each marked {@code reviewed}, A-6; their text as the sponsor left it) are added at the end of the quiz with
 *       the job's id; the quiz becomes {@code aiGenerated}. No candidate is ever saved without this.</li>
 *   <li>{@value #DISCARD} and {@value #CANCEL}: the sponsor drops the candidates, or stops waiting for a job.</li>
 *   <li>{@value #EXPIRE} ({@code internal()}): the daily job clears candidates not reviewed in time.</li>
 *   <li>{@value #STOPPED_JOB} ({@code internal()}): a job an operator stopped at the platform
 *       ({@code EXTERNAL_JOB_CANCEL}, which calls neither outcome process) is closed and charged its whole estimate,
 *       since nobody knows what its attempts used.</li>
 * </ul>
 * Lock order: {@code qb.quiz:<quiz>} → {@code qb.ai:<sponsor>} → {@code qb.ai:*}; none of them is held with the
 * publication, start or wallet locks, so answering is not affected.
 */
public final class AiProcesses {

    public static final String REQUEST = "QB_AI_QUESTIONS_REQUEST";
    public static final String COMPLETED = "QB_AI_JOB_COMPLETED";
    public static final String FAILED = "QB_AI_JOB_FAILED";
    public static final String ACCEPT = "QB_AI_ACCEPT";
    public static final String DISCARD = "QB_AI_DISCARD";
    public static final String CANCEL = "QB_AI_CANCEL";
    public static final String EXPIRE = "QB_AI_EXPIRE";
    public static final String STOPPED_JOB = "QB_AI_JOB_STOPPED";
    /** Everybody's unfinished jobs, for the global limit. */
    public static final String OPEN_TEMPLATE = "qb.system.ai-open";

    /** The daily job clearing expired candidates, and its selection. */
    public static final String EXPIRE_JOB = "qb.ai-expire";
    public static final String EXPIRED_TEMPLATE = "qb.system.ai-expired";

    /**
     * @param materialIds        the materials to write from (none: the introduction only)
     * @param avoidExisting      whether to send the quiz's questions so that the candidates do not repeat them
     *                           (yes unless false)
     * @param aiNoticeVersionNo  the version of the AI notice the page showed, when the sponsor accepts it now
     */
    public record RequestInput(@NotNull UUID quizId,
        @NotNull @Size(max = ContentLimits.MAX_MATERIALS) List<@NotNull UUID> materialIds,
        @NotNull @Min(1) @Max(AiQuestionsJob.MAX_COUNT) Integer count,
        @NotNull @Pattern(regexp = "EASY|MEDIUM|HARD") String difficulty,
        @NotNull @Pattern(regexp = "en|zh|ja") String language, Boolean avoidExisting,
        @Positive Integer aiNoticeVersionNo) {}

    /** @param estimatedTokens what the job may use at most, counted against the month until it finishes */
    public record RequestOutput(String aiJobId, String externalJobId, String status, long estimatedTokens) {}

    public record JobRef(@NotNull UUID aiJobId) {}

    public record JobOutput(String aiJobId, String status) {}

    public record AcceptedOption(@Size(max = ContentLimits.OPTION_LENGTH) String text, Boolean correct) {}

    /**
     * One reviewed question: the candidate it comes from and the text as the sponsor left it (any field may be
     * changed).
     *
     * @param reviewed must be true: the sponsor marked it as checked (A-6)
     */
    public record AcceptedQuestion(@NotNull @Min(1) Integer candidateNo,
        @Size(max = ContentLimits.STEM_LENGTH) String stem, Integer points,
        @NotNull @Size(max = ContentLimits.MAX_OPTIONS) List<@Valid @NotNull AcceptedOption> options,
        @NotNull @AssertTrue Boolean reviewed) {}

    public record AcceptInput(@NotNull UUID aiJobId, @NotNull UUID quizId,
        @NotNull @Size(min = 1, max = AiQuestionsJob.MAX_COUNT) List<@Valid @NotNull AcceptedQuestion> questions,
        Long baseRevision) {}

    /** @param questionIds the questions added, in their order */
    public record AcceptOutput(String quizId, long revision, String aiJobId, List<String> questionIds) {}

    /**
     * A job the platform finished without an outcome process (the platform's event {@code jabiz.external-job.finished}
     * of every job: only a cancelled job of {@value AiQuestionsJob#TYPE} is acted on).
     */
    public record StoppedInput(String jobId, String jobType, String status, Instant at) {}

    /** An item of the daily expiry (its selection's row). */
    public record ExpireInput(@NotNull UUID aiJobId, @NotBlank @Size(max = 128) String sponsorId) {}

    static final String INPUT = "input";
    static final String SETTINGS_ROWS = "settingsRows";
    static final String SETTINGS = "settings";
    static final String JOB_INPUT = "jobInput";
    static final String JOB_PARAMS = "jobParams";
    static final String INPUT_BYTES = "inputBytes";
    static final String EXTERNAL_JOB_ID = "externalJobId";
    static final String OPEN = "openJobs";
    static final String TODAY = "jobsToday";
    static final String USAGE = "usage";
    static final String JOB = "job";
    static final String OUTPUT = "output";
    static final String MATERIAL_ROWS = "materialRows";
    static final String QUESTION_ROWS = "questionRows";
    static final String GLOBAL_OPEN = "globalOpen";
    static final String FIRST_READ = "firstRead";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // ---- request ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<RequestInput, RequestOutput, ProcessContext> request(ZoneId zone,
        AiUserKeys userKeys) {
        return ProcessDefinition.define(REQUEST, 1, RequestInput.class, RequestOutput.class, ProcessContext.class,
            pb -> pb
                .description("Asks the AI for candidate questions of one of the sponsor's quizzes, to review.")
                .permissions(QbPermissions.AI_USE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(QuizEditing.QUIZ_ID, input.quizId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, RequestOutput.class))
                .steps(SponsorChecks::load)
                .steps(b -> ConsentChecks.load(b, "Load the AI notice", List.of(QbLegal.AI_NOTICE),
                    ctx -> ctx.request().actorId()))
                .step("Load the settings", loadSettings())
                .step("Load the quiz", LoadEntity.by(QbContent.sponsorDataset(QbContent.QUIZ),
                    QuizEditing.QUIZ_ID, QuizEditing.QUIZ))
                .step("Load its materials and questions", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbContent.sponsorDataset(QbContent.MATERIAL),
                        ctx -> QuizEditing.ofQuiz(ctx.get(QuizEditing.QUIZ_ID)), MATERIAL_ROWS),
                    QueryEntities.query(QbContent.sponsorDataset(QbContent.QUESTION),
                        ctx -> QuizEditing.ofQuiz(ctx.get(QuizEditing.QUIZ_ID)), QUESTION_ROWS)))
                .compute("Prepare the request", (metadata, ctx) -> prepare(ctx, userKeys))
                .step("Lock the sponsor's AI requests", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAi.lockName(ctx.request().actorId())))
                .step("Lock everybody's usage", HoldLock.<ProcessContext>exclusive(ctx -> QbAi.GLOBAL_LOCK))
                .step("Load everybody's unfinished jobs", RunTemplate.<ProcessContext>of(OPEN_TEMPLATE,
                    ctx -> Map.of(), GLOBAL_OPEN))
                .step("Load where the sponsor stands", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbAi.defaultDataset(QbAi.JOB), ctx -> EntityQuery.builder()
                        .where(and(new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                            new QueryPredicate.Or(List.of(new QueryPredicate.Eq("status", QbAi.QUEUED),
                                and(new QueryPredicate.Eq("status", QbAi.CANCELLED),
                                    new QueryPredicate.IsNull("finishedAt")))))).limit(100).build(), OPEN),
                    QueryEntities.query(QbAi.defaultDataset(QbAi.JOB), ctx -> EntityQuery.builder()
                        .where(and(new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                            new QueryPredicate.Gte("requestedAt", AiLimits.dayStart(ctx.opTime(), zone))))
                        .limit(ctx.get(SETTINGS, AiSettings.class).dailyJobs() + 1).build(), TODAY),
                    QueryEntities.query(QbAi.defaultDataset(QbAi.USAGE), ctx -> usageOf(ctx.request().actorId(),
                        AiLimits.month(ctx.opTime(), zone)), USAGE)))
                .compute("Check the limits", (metadata, ctx) -> checkLimits(ctx, zone))
                .step("Start the job", StartExternalJob.<ProcessContext>of(AiQuestionsJob.TYPE,
                        ctx -> ctx.get(JOB_INPUT))
                    .reference(ctx -> "quiz:" + ctx.get(QuizEditing.QUIZ_ID))
                    .outputKey(EXTERNAL_JOB_ID))
                .compute("Record the job", (metadata, ctx) -> record(ctx, zone)));
    }

    /** The AI parameters as stored (a controlled one not yet published is simply absent). */
    static QueryEntities.Metadata<ProcessContext> settingsQuery() {
        return QueryEntities.query(ParamEntities.DATASET, ctx -> EntityQuery.builder()
            .where(new QueryPredicate.In(ParamEntities.KEY, new ArrayList<>(AiSettings.KEYS)))
            .limit(AiSettings.KEYS.size() * 2).build(), SETTINGS_ROWS);
    }

    static com.jabiz.process.StepSpec<QueryEntities.Metadata<ProcessContext>, ProcessContext> loadSettings() {
        return QueryEntities.of(ParamEntities.DATASET, ctx -> EntityQuery.builder()
            .where(new QueryPredicate.In(ParamEntities.KEY, new ArrayList<>(AiSettings.KEYS)))
            .limit(AiSettings.KEYS.size() * 2).build(), SETTINGS_ROWS);
    }

    static AiSettings settings(ProcessContext ctx) {
        Map<String, String> values = new LinkedHashMap<>();
        for (EntityInstance row : rows(ctx, SETTINGS_ROWS)) {
            Object value = row.get(ParamEntities.VALUE);
            values.put(String.valueOf((Object) row.get(ParamEntities.KEY)), value == null ? null : value.toString());
        }
        return AiSettings.of(values);
    }

    static void prepare(ProcessContext ctx, AiUserKeys userKeys) {
        RequestInput input = ctx.get(INPUT, RequestInput.class);
        String sponsorId = ctx.request().actorId();
        List<Violation> problems = new ArrayList<>(SponsorChecks.problems(ctx));
        AiSettings settings = settings(ctx);
        problems.addAll(settings.problems());
        int maxCount = AiQuestionsJob.maxCount(settings.effort());
        if (input.count() > maxCount) {
            problems.add(new Violation("count", AiCodes.COUNT_TOO_HIGH, "At most " + maxCount + " questions at a time "
                + "at effort " + settings.effort(), Map.of("max", maxCount)));
        }

        ConsentChecks.State notice = ConsentChecks.states(ctx).get(QbLegal.AI_NOTICE);
        List<ConsentChecks.State> accept = List.of();
        if (notice == null) {
            problems.add(new Violation(null, AiCodes.NOTICE_MISSING, "No AI notice is in effect", Map.of()));
        } else if (notice.mustAccept()) {
            if (Objects.equals(input.aiNoticeVersionNo(), notice.currentVersionNo())) {
                accept = List.of(notice);
            } else {
                problems.add(new Violation("aiNoticeVersionNo", AiCodes.NOTICE_REQUIRED, "Accept version "
                    + notice.currentVersionNo() + " of the AI notice first", Map.of("kind", QbLegal.AI_NOTICE,
                    "versionNo", notice.currentVersionNo())));
            }
        }

        EntityInstance quiz = QuizEditing.quiz(ctx);
        List<EntityInstance> materials = QuizEditing.ordered(rows(ctx, MATERIAL_ROWS));
        Map<String, Integer> numbers = new LinkedHashMap<>();
        for (int i = 0; i < materials.size(); i++) {
            numbers.put(String.valueOf((Object) materials.get(i).id()), i + 1);
        }
        Set<String> chosen = new HashSet<>();
        for (int i = 0; i < input.materialIds().size(); i++) {
            String id = input.materialIds().get(i).toString();
            if (!numbers.containsKey(id) || !chosen.add(id)) {
                problems.add(new Violation("materialIds[" + i + "]", AiCodes.MATERIAL_NOT_IN_QUIZ, "There is no "
                    + "material " + id + " in this quiz (or it was chosen twice)", Map.of("id", id)));
            }
        }
        List<AiQuestionsJob.Material> sent = new ArrayList<>();
        List<Map<String, Object>> sizes = new ArrayList<>();
        for (EntityInstance material : materials) {
            String id = String.valueOf(material.id());
            if (!chosen.contains(id)) {
                continue;
            }
            String kind = material.get("kind");
            String body = MaterialKind.ARTICLE.name().equals(kind) ? material.get("body") : null;
            AiQuestionsJob.Material item = new AiQuestionsJob.Material(numbers.get(id), kind, material.get("title"),
                material.get("description"), body);
            sent.add(item);
            sizes.add(Map.of("materialId", id, "no", item.no(), "bytes", bytes(JSON.writeValueAsString(item))));
        }
        String intro = quiz.get("intro");
        if (sent.isEmpty() && (intro == null || intro.isBlank())) {
            problems.add(new Violation("materialIds", AiCodes.NO_SOURCE, "Choose a material, or write the quiz's "
                + "introduction first", Map.of()));
        }
        List<String> stems = new ArrayList<>();
        if (!Boolean.FALSE.equals(input.avoidExisting())) {
            for (EntityInstance question : QuizEditing.ordered(rows(ctx, QUESTION_ROWS))) {
                String stem = question.get("stem");
                if (stem != null && !stem.isBlank()) {
                    stems.add(stem);
                }
            }
        }
        AiQuestionsJob.Input job = new AiQuestionsJob.Input(settings.model(), settings.effort(), input.language(),
            input.count(), input.difficulty(), quiz.get("title"), intro, sent, stems, userKeys.of(sponsorId));
        // What is sent and what the platform stores: both must fit, and neither is ever cut (A-5).
        int size = Math.max(bytes(AiPrompt.user(job, JSON)), bytes(JSON.writeValueAsString(job)));
        if (size > ClaudeApi.MAX_INPUT_BYTES) {
            problems.add(new Violation("materialIds", AiCodes.INPUT_TOO_LARGE, "The request is " + size
                + " bytes, more than " + ClaudeApi.MAX_INPUT_BYTES + ": choose fewer materials",
                Map.of("size", size, "max", ClaudeApi.MAX_INPUT_BYTES, "materials", sizes)));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        ConsentChecks.record(ctx, sponsorId, accept, QbLegal.SOURCE_AI);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("count", input.count());
        params.put("difficulty", input.difficulty());
        params.put("language", input.language());
        params.put("avoidExisting", !Boolean.FALSE.equals(input.avoidExisting()));
        params.put("materialIds", sent.stream().map(m -> materials.stream()
            .filter(row -> numbers.get(String.valueOf(row.id())) == m.no()).findFirst().orElseThrow().id()
            .toString()).toList());
        params.put("materialNos", sent.stream().map(AiQuestionsJob.Material::no).toList());
        ctx.put(JOB_INPUT, job);
        ctx.put(JOB_PARAMS, JSON.writeValueAsString(params));
        ctx.put(INPUT_BYTES, size);
        ctx.put(SETTINGS, settings);
    }

    static void checkLimits(ProcessContext ctx, ZoneId zone) {
        String quizId = String.valueOf((Object) ctx.get(QuizEditing.QUIZ_ID));
        List<EntityInstance> open = rows(ctx, OPEN);
        boolean quizBusy = open.stream().anyMatch(job -> quizId.equals(String.valueOf((Object) job.get("quizId"))));
        long openEstimate = 0;
        for (EntityInstance job : open) {
            openEstimate += QuizEditing.number(job.get("estimatedTokens"));
        }
        String sponsorId = ctx.request().actorId();
        EntityInstance mine = usageRow(ctx, sponsorId);
        EntityInstance everybody = usageRow(ctx, QbAi.EVERYBODY);
        AiLimits.Standing standing = new AiLimits.Standing(open.size(), quizBusy, rows(ctx, TODAY).size(),
            tokens(mine), openEstimate, tokens(everybody), globalOpenEstimate(ctx));
        long estimate = AiLimits.estimate((int) ctx.get(INPUT_BYTES),
            ctx.get(JOB_INPUT, AiQuestionsJob.Input.class).count());
        List<Violation> problems = AiLimits.problems(ctx.get(SETTINGS, AiSettings.class).limits(), standing,
            estimate, ctx.opTime(), zone);
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    /** What everybody's unfinished jobs may still use ({@value #OPEN_TEMPLATE}'s one row). */
    @SuppressWarnings("unchecked")
    static long globalOpenEstimate(ProcessContext ctx) {
        List<Map<String, Object>> found = (List<Map<String, Object>>) ctx.get(GLOBAL_OPEN);
        return found == null || found.isEmpty() ? 0 : QuizEditing.number(found.getFirst().get("openEstimate"));
    }

    static void record(ProcessContext ctx, ZoneId zone) {
        AiQuestionsJob.Input job = ctx.get(JOB_INPUT, AiQuestionsJob.Input.class);
        int size = (int) ctx.get(INPUT_BYTES);
        long estimate = AiLimits.estimate(size, job.count());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("externalJobId", String.valueOf((Object) ctx.get(EXTERNAL_JOB_ID)));
        values.put("sponsorId", ctx.request().actorId());
        values.put("quizId", ctx.get(QuizEditing.QUIZ_ID));
        values.put("kind", QbAi.KIND_QUESTIONS);
        values.put("params", ctx.get(JOB_PARAMS));
        values.put("model", job.model());
        values.put("status", QbAi.QUEUED);
        values.put("inputBytes", BigDecimal.valueOf(size));
        values.put("estimatedTokens", BigDecimal.valueOf(estimate));
        values.put("inputTokens", BigDecimal.ZERO);
        values.put("outputTokens", BigDecimal.ZERO);
        values.put("requestedAt", ctx.opTime());
        values.put("zone", zone.getId());
        Object id = ctx.changes().insert(QbAi.JOB, values);
        ctx.put(OUTPUT, new RequestOutput(String.valueOf(id), String.valueOf((Object) ctx.get(EXTERNAL_JOB_ID)), QbAi.QUEUED,
            estimate));
    }

    // ---- the job's outcome -----------------------------------------------------------------------------------------

    public static ProcessDefinition<ExternalJobCompletion, JobOutput, ProcessContext> completed(ZoneId zone) {
        return ProcessDefinition.define(COMPLETED, 1, ExternalJobCompletion.class, JobOutput.class,
            ProcessContext.class, pb -> pb
                .description("Keeps the AI's candidate questions for the sponsor's review and counts the usage.")
                .permissions(QbPermissions.AI_SYSTEM)
                .internal()
                .contextFactory(AiProcesses::context)
                .outputMapper(AiProcesses::jobOutput)
                .step("Lock the sponsor's AI requests", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAi.lockName(ctx.get(INPUT, ExternalJobCompletion.class).requestedBy())))
                .step("Lock everybody's usage", HoldLock.<ProcessContext>exclusive(ctx -> QbAi.GLOBAL_LOCK))
                .step("Load the job and the usage", outcomeReads(zone,
                    ctx -> ctx.get(INPUT, ExternalJobCompletion.class).jobId(),
                    ctx -> ctx.get(INPUT, ExternalJobCompletion.class).requestedBy(),
                    ctx -> ctx.get(INPUT, ExternalJobCompletion.class).finishedTime()))
                .compute("Keep the candidates", (metadata, ctx) -> complete(ctx, zone)));
    }

    public static ProcessDefinition<ExternalJobFailure, JobOutput, ProcessContext> failed(ZoneId zone) {
        return ProcessDefinition.define(FAILED, 1, ExternalJobFailure.class, JobOutput.class,
            ProcessContext.class, pb -> pb
                .description("Records that an AI job failed and counts its usage.")
                .permissions(QbPermissions.AI_SYSTEM)
                .internal()
                .contextFactory(AiProcesses::context)
                .outputMapper(AiProcesses::jobOutput)
                .step("Lock the sponsor's AI requests", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAi.lockName(ctx.get(INPUT, ExternalJobFailure.class).requestedBy())))
                .step("Lock everybody's usage", HoldLock.<ProcessContext>exclusive(ctx -> QbAi.GLOBAL_LOCK))
                .step("Load the job and the usage", outcomeReads(zone,
                    ctx -> ctx.get(INPUT, ExternalJobFailure.class).jobId(),
                    ctx -> ctx.get(INPUT, ExternalJobFailure.class).requestedBy(),
                    ctx -> ctx.get(INPUT, ExternalJobFailure.class).finishedTime()))
                .compute("Record the failure", (metadata, ctx) -> fail(ctx, zone)));
    }

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static JobOutput jobOutput(ProcessContext ctx) {
        JobOutput output = (JobOutput) ctx.get(OUTPUT);
        return output == null ? new JobOutput(null, null) : output;
    }

    private static com.jabiz.process.StepSpec<QueryEntities.AllMetadata<ProcessContext>, ProcessContext>
        outcomeReads(ZoneId zone, java.util.function.Function<ProcessContext, String> jobId,
            java.util.function.Function<ProcessContext, String> sponsorId,
            java.util.function.Function<ProcessContext, Instant> finished) {
        return QueryEntities.<ProcessContext>all(
            QueryEntities.query(QbAi.defaultDataset(QbAi.JOB), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq("externalJobId", jobId.apply(ctx))).limit(1).build(), JOB),
            QueryEntities.query(QbAi.defaultDataset(QbAi.USAGE), ctx -> usageOf(sponsorId.apply(ctx),
                AiLimits.month(finished.apply(ctx), zone)), USAGE),
            settingsQuery());
    }

    /** The job of the outcome, when it is the requester's and not finished yet; null otherwise (nothing to do). */
    private static EntityInstance unfinished(ProcessContext ctx, String requestedBy) {
        EntityInstance job = first(ctx, JOB);
        if (job == null || !requestedBy.equals(job.get("sponsorId")) || job.get("finishedAt") != null) {
            return null;
        }
        return job;
    }

    static void complete(ProcessContext ctx, ZoneId zone) {
        ExternalJobCompletion completion = ctx.get(INPUT, ExternalJobCompletion.class);
        EntityInstance job = unfinished(ctx, completion.requestedBy());
        if (job == null) {
            return;
        }
        ClaudeApi.Usage usage = usage(completion.usage());
        String month = AiLimits.month(completion.finishedTime(), zone);
        AiQuestionsJob.Result result = completion.result(AiQuestionsJob.Result.class);
        java.math.BigDecimal cost = AiCost.of(job.get("model"), result.servedModel(), usage, settings(ctx).price());
        Map<String, Object> values = finished(usage, cost, completion.attempts(), completion.finishedTime());
        boolean failed = false;
        if (QbAi.QUEUED.equals(job.get("status"))) {
            values.put("servedModel", result.servedModel());
            Map<String, Object> params = params(job);
            int count = ((Number) params.getOrDefault("count", AiQuestionsJob.MAX_COUNT)).intValue();
            List<Integer> materialNos = numbers(params.get("materialNos"));
            // Checked again as stored: the handler's check is not the last word.
            AiCandidates.Checked checked = AiCandidates.check(result.candidates(), count, materialNos);
            int invalid = result.invalid() + checked.invalid();
            values.put("invalidCount", BigDecimal.valueOf(invalid));
            if (checked.valid().isEmpty()) {
                failed = true;
                values.put("status", QbAi.FAILED);
                values.put("candidateCount", BigDecimal.ZERO);
                values.put("failureCode", AiCodes.NO_CANDIDATES);
                values.put("failureMessage", "None of the " + invalid + " candidates met the content rules");
            } else {
                values.put("status", QbAi.READY);
                values.put("candidates", candidatesJson(checked.valid()));
                values.put("candidateCount", BigDecimal.valueOf(checked.valid().size()));
                values.put("expiresAt", completion.finishedTime().plus(Duration.ofDays(settings(ctx).expireDays())));
            }
        }
        ctx.changes().update(QbAi.JOB, job.id(), job.version(), values);
        addUsage(ctx, completion.requestedBy(), month, usage, cost, failed);
        ctx.put(OUTPUT, new JobOutput(String.valueOf(job.id()), (String) values.getOrDefault("status",
            job.get("status"))));
    }

    static void fail(ProcessContext ctx, ZoneId zone) {
        ExternalJobFailure failure = ctx.get(INPUT, ExternalJobFailure.class);
        EntityInstance job = unfinished(ctx, failure.requestedBy());
        if (job == null) {
            return;
        }
        ClaudeApi.Usage usage = usage(failure.usage());
        java.math.BigDecimal cost = AiCost.of(job.get("model"), null, usage, settings(ctx).price());
        Map<String, Object> values = finished(usage, cost, failure.attempts(), failure.finishedTime());
        if (QbAi.QUEUED.equals(job.get("status"))) {
            values.put("status", QbAi.FAILED);
            values.put("failureCode", clip(failure.errorCode(), 100));
            values.put("failureMessage", clip(failure.errorMessage(), QbAi.FAILURE_MESSAGE_LENGTH));
        }
        ctx.changes().update(QbAi.JOB, job.id(), job.version(), values);
        addUsage(ctx, failure.requestedBy(), AiLimits.month(failure.finishedTime(), zone), usage, cost, true);
        ctx.put(OUTPUT, new JobOutput(String.valueOf(job.id()), (String) values.getOrDefault("status",
            job.get("status"))));
    }

    /** The usage of every attempt, by metric, as the platform added it up. */
    static ClaudeApi.Usage usage(Map<String, Long> metrics) {
        return new ClaudeApi.Usage(metrics.getOrDefault(AiQuestionsJob.INPUT_TOKENS, 0L),
            metrics.getOrDefault(AiQuestionsJob.OUTPUT_TOKENS, 0L),
            metrics.getOrDefault(AiQuestionsJob.FALLBACK_INPUT_TOKENS, 0L),
            metrics.getOrDefault(AiQuestionsJob.FALLBACK_OUTPUT_TOKENS, 0L));
    }

    private static Map<String, Object> finished(ClaudeApi.Usage usage, BigDecimal cost, Integer attempts,
        Instant at) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("inputTokens", BigDecimal.valueOf(usage.inputTokens()));
        values.put("outputTokens", BigDecimal.valueOf(usage.outputTokens()));
        values.put("fallbackInputTokens", BigDecimal.valueOf(usage.fallbackInputTokens()));
        values.put("fallbackOutputTokens", BigDecimal.valueOf(usage.fallbackOutputTokens()));
        values.put("costUsd", cost);
        values.put("attempts", attempts == null ? null : BigDecimal.valueOf(attempts));
        values.put("finishedAt", at);
        return values;
    }

    /** Adds a finished job to the sponsor's month and to everybody's (under both locks). */
    private static void addUsage(ProcessContext ctx, String sponsorId, String month, ClaudeApi.Usage usage,
        BigDecimal cost, boolean failed) {
        long in = usage.inputTokens();
        long out = usage.outputTokens();
        for (String who : List.of(sponsorId, QbAi.EVERYBODY)) {
            EntityInstance row = usageRow(ctx, who);
            if (row == null) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("sponsorId", who);
                values.put("month", month);
                values.put("jobs", BigDecimal.ONE);
                values.put("failedJobs", failed ? BigDecimal.ONE : BigDecimal.ZERO);
                values.put("inputTokens", BigDecimal.valueOf(in));
                values.put("outputTokens", BigDecimal.valueOf(out));
                values.put("costUsd", cost);
                ctx.changes().insert(QbAi.USAGE, values);
            } else {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("jobs", BigDecimal.valueOf(QuizEditing.number(row.get("jobs")) + 1));
                if (failed) {
                    values.put("failedJobs", BigDecimal.valueOf(QuizEditing.number(row.get("failedJobs")) + 1));
                }
                values.put("inputTokens", BigDecimal.valueOf(QuizEditing.number(row.get("inputTokens")) + in));
                values.put("outputTokens", BigDecimal.valueOf(QuizEditing.number(row.get("outputTokens")) + out));
                if (cost != null) {
                    Object before = row.get("costUsd");
                    values.put("costUsd", (before == null ? BigDecimal.ZERO : new BigDecimal(before.toString()))
                        .add(cost));
                }
                ctx.changes().update(QbAi.USAGE, row.id(), row.version(), values);
            }
        }
    }

    // ---- a job stopped at the platform ------------------------------------------------------------------------------

    public static ProcessDefinition<StoppedInput, JobOutput, ProcessContext> stopped(ZoneId zone) {
        return ProcessDefinition.define(STOPPED_JOB, 1, StoppedInput.class, JobOutput.class, ProcessContext.class,
            pb -> pb
                .description("Closes an AI job an operator stopped at the platform and charges its estimate.")
                .permissions(QbPermissions.AI_SYSTEM)
                .internal()
                .contextFactory(AiProcesses::context)
                .outputMapper(AiProcesses::jobOutput)
                // Found first, to know whose locks to take; read again under them.
                .step("Find the job", QueryEntities.<ProcessContext>of(QbAi.defaultDataset(QbAi.JOB),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("externalJobId",
                        stoppedJobId(ctx))).limit(1).build(), FIRST_READ))
                .step("Lock the sponsor's AI requests", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAi.lockName(stoppedSponsor(ctx))))
                .step("Lock everybody's usage", HoldLock.<ProcessContext>exclusive(ctx -> QbAi.GLOBAL_LOCK))
                .step("Load the job and the usage", outcomeReads(zone, AiProcesses::stoppedJobId,
                    AiProcesses::stoppedSponsor, ctx -> ctx.get(INPUT, StoppedInput.class).at()))
                .compute("Close the job", (metadata, ctx) -> closeStopped(ctx, zone)));
    }

    /** The platform's job, when the event is about a cancelled job of this type; a key matching nothing otherwise. */
    private static String stoppedJobId(ProcessContext ctx) {
        StoppedInput input = ctx.get(INPUT, StoppedInput.class);
        return AiQuestionsJob.TYPE.equals(input.jobType()) && "CANCELLED".equals(input.status())
            && input.jobId() != null ? input.jobId() : "-";
    }

    /** The sponsor of the job found; a lock nobody else takes when there is none. */
    private static String stoppedSponsor(ProcessContext ctx) {
        EntityInstance job = first(ctx, FIRST_READ);
        return job == null ? "-" : String.valueOf((Object) job.get("sponsorId"));
    }

    static void closeStopped(ProcessContext ctx, ZoneId zone) {
        StoppedInput input = ctx.get(INPUT, StoppedInput.class);
        EntityInstance found = first(ctx, FIRST_READ);
        if (found == null) {
            return;
        }
        EntityInstance job = unfinished(ctx, String.valueOf((Object) found.get("sponsorId")));
        if (job == null) {
            return;
        }
        // Nobody knows what its attempts used: the whole estimate, as the limits reserved it.
        long estimate = QuizEditing.number(job.get("estimatedTokens"));
        long in = Math.min(estimate, AiQuestionsJob.ATTEMPTS
            * AiLimits.inputEstimate(QuizEditing.number(job.get("inputBytes"))));
        ClaudeApi.Usage usage = new ClaudeApi.Usage(in, estimate - in);
        BigDecimal cost = AiCost.of(job.get("model"), null, usage, settings(ctx).price());
        Map<String, Object> values = finished(usage, cost, null, input.at());
        if (QbAi.QUEUED.equals(job.get("status"))) {
            values.put("status", QbAi.CANCELLED);
            values.put("failureCode", AiCodes.STOPPED);
            values.put("failureMessage", "Stopped at the platform; charged its estimate");
            values.put("closedAt", input.at());
        }
        ctx.changes().update(QbAi.JOB, job.id(), job.version(), values);
        addUsage(ctx, String.valueOf((Object) job.get("sponsorId")), AiLimits.month(input.at(), zone), usage, cost,
            true);
        ctx.put(OUTPUT, new JobOutput(String.valueOf(job.id()), (String) values.getOrDefault("status",
            job.get("status"))));
    }

    // ---- accept ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AcceptInput, AcceptOutput, ProcessContext> accept() {
        return ProcessDefinition.define(ACCEPT, 1, AcceptInput.class, AcceptOutput.class, ProcessContext.class,
            pb -> pb
                .description("Adds the AI candidates the sponsor reviewed to the quiz, as the sponsor left them.")
                .permissions(QbPermissions.AI_USE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(QuizEditing.QUIZ_ID, input.quizId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, AcceptOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .step("Lock the sponsor's AI requests", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAi.lockName(ctx.request().actorId())))
                .step("Load the job and the questions", QueryEntities.<ProcessContext>all(
                    jobById(ctx -> ctx.get(INPUT, AcceptInput.class).aiJobId()),
                    QueryEntities.query(QbContent.defaultDataset(QbContent.QUESTION),
                        ctx -> QuizEditing.ofQuiz(ctx.get(QuizEditing.QUIZ_ID)), QuizEditing.QUESTIONS)))
                .compute("Add the questions", (metadata, ctx) -> acceptQuestions(ctx)));
    }

    static void acceptQuestions(ProcessContext ctx) {
        AcceptInput input = ctx.get(INPUT, AcceptInput.class);
        EntityInstance quiz = QuizEditing.quiz(ctx);
        EntityInstance job = own(ctx);
        if (!input.quizId().toString().equals(String.valueOf((Object) job.get("quizId")))) {
            throw new EntityNotFoundException("AI job not found");
        }
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        requireReady(ctx, job);
        List<AiQuestionsJob.Candidate> candidates = candidates(job);
        List<EntityInstance> existing = QuizEditing.ordered(QuizEditing.list(ctx, QuizEditing.QUESTIONS));
        List<Violation> problems = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        List<AiQuestionsJob.Candidate> accepted = new ArrayList<>();
        for (int i = 0; i < input.questions().size(); i++) {
            AcceptedQuestion question = input.questions().get(i);
            String path = "questions[" + i + "]";
            int no = question.candidateNo();
            if (no > candidates.size() || !seen.add(no)) {
                problems.add(new Violation(path + ".candidateNo", AiCodes.CANDIDATE_UNKNOWN, "Candidate " + no
                    + " is not one of the job's, or is given twice", Map.of("candidateNo", no)));
                continue;
            }
            List<AiQuestionsJob.Option> options = question.options().stream()
                .map(option -> new AiQuestionsJob.Option(option.text(), Boolean.TRUE.equals(option.correct())))
                .toList();
            AiQuestionsJob.Candidate reviewed = AiCandidates.clean(new AiQuestionsJob.Candidate(question.stem(),
                options, question.points(), candidates.get(no - 1).materials()), candidates.get(no - 1).materials());
            problems.addAll(AiCandidates.problems(reviewed, path, existing.size() + i + 1));
            accepted.add(reviewed);
        }
        if (existing.size() + input.questions().size() > ContentLimits.MAX_QUESTIONS) {
            problems.add(new Violation("questions", ContentCodes.TOO_MANY_QUESTIONS, "A quiz has at most "
                + ContentLimits.MAX_QUESTIONS + " questions", Map.of("max", ContentLimits.MAX_QUESTIONS)));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }

        Object owner = quiz.get("ownerId");
        int seq = placesFor(ctx, existing, accepted.size());
        List<String> ids = new ArrayList<>();
        for (AiQuestionsJob.Candidate question : accepted) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("quizId", quiz.id());
            values.put("ownerId", owner);
            values.put("seq", BigDecimal.valueOf(seq++));
            values.put("stem", question.stem());
            values.put("points", BigDecimal.valueOf(question.points()));
            values.put("aiJobId", String.valueOf(job.id()));
            Object questionId = ctx.changes().insert(QbContent.QUESTION, values);
            for (int j = 0; j < question.options().size(); j++) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("questionId", questionId);
                option.put("quizId", quiz.id());
                option.put("ownerId", owner);
                option.put("seq", BigDecimal.valueOf(j + 1));
                option.put("text", question.options().get(j).text());
                option.put("correct", question.options().get(j).correct());
                ctx.changes().insert(QbContent.OPTION, option);
            }
            ids.add(String.valueOf(questionId));
        }
        long revision = QuizEditing.number(quiz.get("revision")) + 1;
        Map<String, Object> quizValues = new LinkedHashMap<>();
        quizValues.put("revision", BigDecimal.valueOf(revision));
        quizValues.put("editedAt", ctx.opTime());
        quizValues.put("changedSinceVersion", true);
        quizValues.put("questionCount", BigDecimal.valueOf(existing.size() + accepted.size()));
        quizValues.put("aiGenerated", true);
        ctx.changes().update(QbContent.QUIZ, quiz.id(), quiz.version(), quizValues);

        Map<String, Object> jobValues = new LinkedHashMap<>();
        jobValues.put("status", QbAi.ACCEPTED);
        jobValues.put("candidates", null);
        jobValues.put("acceptedCount", BigDecimal.valueOf(accepted.size()));
        jobValues.put("closedAt", ctx.opTime());
        ctx.changes().update(QbAi.JOB, job.id(), job.version(), jobValues);
        ctx.put(OUTPUT, new AcceptOutput(String.valueOf(quiz.id()), revision, String.valueOf(job.id()),
            List.copyOf(ids)));
    }

    /**
     * The place of the first of {@code count} new questions at the end; when they would not fit the column, the
     * existing ones are numbered 1..n again first (as one new question does, {@code Reorder.next}).
     */
    static int placesFor(ProcessContext ctx, List<EntityInstance> ordered, int count) {
        long last = ordered.stream().mapToLong(row -> QuizEditing.number(row.get("seq"))).max().orElse(0);
        if (last + count <= ContentLimits.MAX_SEQ) {
            return (int) last + 1;
        }
        for (int i = 0; i < ordered.size(); i++) {
            EntityInstance row = ordered.get(i);
            if (QuizEditing.number(row.get("seq")) != i + 1) {
                ctx.changes().update(QbContent.QUESTION, row.id(), row.version(),
                    Map.of("seq", BigDecimal.valueOf(i + 1)));
            }
        }
        return ordered.size() + 1;
    }

    // ---- discard, cancel, expire -----------------------------------------------------------------------------------

    public static ProcessDefinition<JobRef, JobOutput, ProcessContext> discard() {
        return own(DISCARD, "Drops the AI candidates of a job without adding any.", job -> QbAi.READY,
            (ctx, job) -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", QbAi.DISCARDED);
                values.put("candidates", null);
                values.put("closedAt", ctx.opTime());
                return values;
            });
    }

    public static ProcessDefinition<JobRef, JobOutput, ProcessContext> cancel() {
        return own(CANCEL, "Stops waiting for an AI job; its usage still counts when it ends.", job -> QbAi.QUEUED,
            (ctx, job) -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", QbAi.CANCELLED);
                values.put("closedAt", ctx.opTime());
                return values;
            });
    }

    private static ProcessDefinition<JobRef, JobOutput, ProcessContext> own(String name, String description,
        java.util.function.Function<EntityInstance, String> from,
        java.util.function.BiFunction<ProcessContext, EntityInstance, Map<String, Object>> change) {
        return ProcessDefinition.define(name, 1, JobRef.class, JobOutput.class, ProcessContext.class, pb -> pb
            .description(description)
            .permissions(QbPermissions.AI_USE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory(AiProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, JobOutput.class))
            .step("Lock the sponsor's AI requests", HoldLock.<ProcessContext>exclusive(
                ctx -> QbAi.lockName(ctx.request().actorId())))
            .step("Load the job", loadJob(ctx -> ctx.get(INPUT, JobRef.class).aiJobId()))
            .compute("Change the job", (metadata, ctx) -> {
                EntityInstance job = own(ctx);
                if (!from.apply(job).equals(job.get("status"))) {
                    throw new BusinessRuleViolationException(List.of(new Violation("aiJobId",
                        AiCodes.JOB_NOT_READY, "The AI job is " + job.get("status"), Map.of("status",
                        String.valueOf((Object) job.get("status"))))));
                }
                Map<String, Object> values = change.apply(ctx, job);
                ctx.changes().update(QbAi.JOB, job.id(), job.version(), values);
                ctx.put(OUTPUT, new JobOutput(String.valueOf(job.id()), (String) values.get("status")));
            }));
    }

    public static ProcessDefinition<ExpireInput, JobOutput, ProcessContext> expire() {
        return ProcessDefinition.define(EXPIRE, 1, ExpireInput.class, JobOutput.class, ProcessContext.class, pb -> pb
            .description("Clears AI candidates nobody reviewed in time.")
            .permissions(QbPermissions.AI_SYSTEM)
            .internal()
            .contextFactory(AiProcesses::context)
            .outputMapper(AiProcesses::jobOutput)
            .step("Lock the sponsor's AI requests", HoldLock.<ProcessContext>exclusive(
                ctx -> QbAi.lockName(ctx.get(INPUT, ExpireInput.class).sponsorId())))
            .step("Load the job", loadJob(ctx -> ctx.get(INPUT, ExpireInput.class).aiJobId()))
            .compute("Clear the candidates", (metadata, ctx) -> {
                ExpireInput input = ctx.get(INPUT, ExpireInput.class);
                EntityInstance job = first(ctx, JOB);
                // Read under the lock: done meanwhile (accepted, discarded), or not the item's sponsor's: nothing.
                if (job == null || !input.sponsorId().equals(job.get("sponsorId"))
                    || !QbAi.READY.equals(job.get("status")) || QuizEditing.instant(job.get("expiresAt"))
                    .isAfter(ctx.opTime())) {
                    return;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", QbAi.EXPIRED);
                values.put("candidates", null);
                values.put("closedAt", ctx.opTime());
                ctx.changes().update(QbAi.JOB, job.id(), job.version(), values);
                ctx.put(OUTPUT, new JobOutput(String.valueOf(job.id()), QbAi.EXPIRED));
            }));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static com.jabiz.process.StepSpec<QueryEntities.Metadata<ProcessContext>, ProcessContext> loadJob(
        java.util.function.Function<ProcessContext, UUID> id) {
        return QueryEntities.of(QbAi.defaultDataset(QbAi.JOB), ctx -> EntityQuery.builder()
            .where(new QueryPredicate.Eq("aiJobId", id.apply(ctx).toString())).limit(1).build(), JOB);
    }

    private static QueryEntities.Metadata<ProcessContext> jobById(java.util.function.Function<ProcessContext, UUID> id) {
        return QueryEntities.query(QbAi.defaultDataset(QbAi.JOB), ctx -> EntityQuery.builder()
            .where(new QueryPredicate.Eq("aiJobId", id.apply(ctx).toString())).limit(1).build(), JOB);
    }

    /** The caller's own job: anyone else's, or none, is 404 without saying which. */
    private static EntityInstance own(ProcessContext ctx) {
        EntityInstance job = first(ctx, JOB);
        if (job == null || !ctx.request().actorId().equals(job.get("sponsorId"))) {
            throw new EntityNotFoundException("AI job not found");
        }
        return job;
    }

    private static void requireReady(ProcessContext ctx, EntityInstance job) {
        if (!QbAi.READY.equals(job.get("status"))) {
            throw new BusinessRuleViolationException(List.of(new Violation("aiJobId", AiCodes.JOB_NOT_READY,
                "The AI job is " + job.get("status"), Map.of("status", String.valueOf((Object) job.get("status"))))));
        }
        if (!QuizEditing.instant(job.get("expiresAt")).isAfter(ctx.opTime())) {
            throw new BusinessRuleViolationException(List.of(new Violation("aiJobId", AiCodes.JOB_EXPIRED,
                "The AI candidates expired", Map.of())));
        }
    }

    private static QueryPredicate and(QueryPredicate... predicates) {
        return new QueryPredicate.And(List.of(predicates));
    }

    private static EntityQuery usageOf(String sponsorId, String month) {
        return EntityQuery.builder().where(and(new QueryPredicate.Eq("month", month),
            new QueryPredicate.In("sponsorId", new ArrayList<>(List.of(sponsorId, QbAi.EVERYBODY))))).limit(2)
            .build();
    }

    private static EntityInstance usageRow(ProcessContext ctx, String sponsorId) {
        return rows(ctx, USAGE).stream().filter(row -> sponsorId.equals(row.get("sponsorId"))).findFirst()
            .orElse(null);
    }

    private static long tokens(EntityInstance usage) {
        return usage == null ? 0 : QuizEditing.number(usage.get("inputTokens"))
            + QuizEditing.number(usage.get("outputTokens"));
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> rows(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = rows(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> params(EntityInstance job) {
        return JSON.readValue(String.valueOf((Object) job.get("params")), Map.class);
    }

    private static List<Integer> numbers(Object value) {
        List<Integer> numbers = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> {
                if (item instanceof Number n) {
                    numbers.add(n.intValue());
                }
            });
        }
        return numbers;
    }

    /** The candidates as stored: {@code {schema: 1, questions: [...]}}, canonical (fields in a fixed order). */
    static String candidatesJson(List<AiQuestionsJob.Candidate> candidates) {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            AiQuestionsJob.Candidate candidate = candidates.get(i);
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("no", i + 1);
            question.put("stem", candidate.stem());
            question.put("points", candidate.points());
            question.put("materials", candidate.materials());
            question.put("options", candidate.options().stream().map(option -> {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("text", option.text());
                o.put("correct", option.correct());
                return o;
            }).toList());
            questions.add(question);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema", 1);
        document.put("questions", questions);
        return JSON.writeValueAsString(document);
    }

    /** The stored candidates, in their order. */
    @SuppressWarnings("unchecked")
    static List<AiQuestionsJob.Candidate> candidates(EntityInstance job) {
        Map<String, Object> document = JSON.readValue(String.valueOf((Object) job.get("candidates")), Map.class);
        List<AiQuestionsJob.Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> question : (List<Map<String, Object>>) document.get("questions")) {
            List<AiQuestionsJob.Option> options = ((List<Map<String, Object>>) question.get("options")).stream()
                .map(o -> new AiQuestionsJob.Option((String) o.get("text"), Boolean.TRUE.equals(o.get("correct"))))
                .toList();
            candidates.add(new AiQuestionsJob.Candidate((String) question.get("stem"), options,
                ((Number) question.get("points")).intValue(), numbers(question.get("materials"))));
        }
        return candidates;
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.codePointCount(0, text.length()) <= max ? text
            : text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
    }

    private AiProcesses() {}
}
