package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobCancellation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.quizbuks.content.EditOutput;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.MaterialKind;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizEditing;
import com.jabiz.quizbuks.content.QuizLanguages;
import com.jabiz.quizbuks.content.TranslationProcesses;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.publishing.SponsorChecks;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.file.FileEntities;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.process.steps.CancelExternalJob;
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
 *       {@code qb.ai:*}, the usage of every attempt (an estimate for one that recorded nothing, {@link #charged}) is
 *       added to the sponsor's month and everybody's; the candidates are
 *       checked against the content rules again and wait for review ({@code READY}, for
 *       {@code qb.ai.candidates.expire-days}), or the failure is recorded. A cancelled job keeps its state; its usage
 *       still counts.</li>
 *   <li>{@value #ACCEPT}: under {@code qb.quiz:<quiz>} then {@code qb.ai:<sponsor>}, the questions the sponsor reviewed
 *       (each marked {@code reviewed}, A-6; their text as the sponsor left it) are added at the end of the quiz with
 *       the job's id; the quiz becomes {@code aiGenerated}. No candidate is ever saved without this.</li>
 *   <li>{@value #DISCARD} and {@value #CANCEL}: the sponsor drops the candidates, or stops a job: {@value #CANCEL}
 *       also cancels the platform's job (one step for both job types, platform decision D52 item 2), so a queued
 *       job never calls the provider and a running one ends after its attempt.</li>
 *   <li>{@value #EXPIRE} ({@code internal()}): the daily job clears candidates not reviewed in time.</li>
 *   <li>{@value #CANCELLED_JOB} ({@code internal()}, both job types' {@code onCancelled}): a cancelled job, whoever
 *       cancelled it (the sponsor, or an operator through {@code EXTERNAL_JOB_CANCEL}), is closed once it has ended
 *       and charged what its attempts used; a job cancelled before any attempt ran is charged nothing.</li>
 * </ul>
 * Lock order: {@code qb.quiz:<quiz>} → {@code qb.ai:<sponsor>} → {@code qb.ai:*}; none of them is held with the
 * publication, start or wallet locks, so answering is not affected.
 */
public final class AiProcesses {

    public static final String REQUEST = "QB_AI_QUESTIONS_REQUEST";
    /** Candidate covers of a quiz (Q8b, M-25): as {@value #REQUEST}, from the quiz's title and introduction. */
    public static final String COVER_REQUEST = "QB_AI_COVER_REQUEST";
    /** The sponsor's choice of one candidate cover, written into the quiz's {@code cover} (Q8b). */
    public static final String COVER_ACCEPT = "QB_AI_COVER_ACCEPT";
    /** Q73d-1: candidate translations of a quiz's text, and the sponsor's reviewed ones written. */
    public static final String TRANSLATE_REQUEST = "QB_AI_TRANSLATE_REQUEST";
    public static final String TRANSLATION_ACCEPT = "QB_AI_TRANSLATION_ACCEPT";
    public static final String COMPLETED = "QB_AI_JOB_COMPLETED";
    public static final String FAILED = "QB_AI_JOB_FAILED";
    public static final String ACCEPT = "QB_AI_ACCEPT";
    public static final String DISCARD = "QB_AI_DISCARD";
    public static final String CANCEL = "QB_AI_CANCEL";
    public static final String EXPIRE = "QB_AI_EXPIRE";
    /** Both job types' {@code onCancelled} (platform decision D52 item 2). */
    public static final String CANCELLED_JOB = "QB_AI_JOB_CANCELLED";
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
        @Positive Integer aiNoticeVersionNo) implements Requested {}

    /**
     * Candidate covers of a quiz (Q8b): drawn from its title and introduction.
     *
     * @param language          the covers' text's: {@code en}, {@code zh} or {@code ja}
     * @param aiNoticeVersionNo the version of the AI notice the page showed, when the sponsor accepts it now
     */
    public record CoverRequestInput(@NotNull UUID quizId, @NotNull @Pattern(regexp = "en|zh|ja") String language,
        @Positive Integer aiNoticeVersionNo) implements Requested {}

    /**
     * Candidate translations of a quiz's text (Q73d-1) into one of its other languages.
     *
     * @param all               every text, also the ones translated and up to date (no: only the missing and the
     *                          out-of-date ones)
     * @param aiNoticeVersionNo the version of the AI notice the page showed, when the sponsor accepts it now
     */
    public record TranslateRequestInput(@NotNull UUID quizId, @NotNull @Pattern(regexp = "en|zh|ja") String language,
        Boolean all, @Positive Integer aiNoticeVersionNo) implements Requested {}

    /**
     * One reviewed translation (Q73d-1): the candidate it comes from and the text as the sponsor left it.
     *
     * @param reviewed must be true: the sponsor marked it as checked (D-Q73-9, as A-6 for questions)
     */
    public record AcceptedTranslation(@NotNull @Min(1) Integer candidateNo,
        @NotBlank @Size(max = ContentLimits.INTRO_LENGTH) String text, @NotNull @AssertTrue Boolean reviewed) {}

    public record TranslationAcceptInput(@NotNull UUID aiJobId, @NotNull UUID quizId,
        @NotNull @Size(min = 1, max = 1_000) List<@Valid @NotNull AcceptedTranslation> translations,
        Long baseRevision) {}

    /** What every request of the AI names: the quiz, and the notice accepted with it. */
    public interface Requested {
        UUID quizId();

        Integer aiNoticeVersionNo();
    }

    /** The chosen candidate cover ({@code 1..3}) of a ready cover job of the quiz. */
    public record CoverAcceptInput(@NotNull UUID aiJobId, @NotNull UUID quizId,
        @NotNull @Min(1) @Max(CoverDesigns.COVERS) Integer candidateNo, Long baseRevision) {}

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
    static final String ESTIMATE = "estimate";
    static final String KIND = "kind";
    static final String MODEL = "model";
    static final String PDF_FILES = "pdfFiles";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // ---- request ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<RequestInput, RequestOutput, ProcessContext> request(ZoneId zone,
        AiUserKeys userKeys, AiPdfs.Limits pdfs) {
        return requestProcess(REQUEST, RequestInput.class,
            "Asks the AI for candidate questions of one of the sponsor's quizzes, to review.", AiQuestionsJob.TYPE,
            Loads.QUESTIONS, zone, ctx -> prepare(ctx, userKeys, pdfs));
    }

    /** Candidate covers (Q8b): the same checks, locks and limits as questions, of the job {@value AiCoverJob#TYPE}. */
    public static ProcessDefinition<CoverRequestInput, RequestOutput, ProcessContext> coverRequest(ZoneId zone,
        AiUserKeys userKeys) {
        return requestProcess(COVER_REQUEST, CoverRequestInput.class,
            "Asks the AI for candidate covers of one of the sponsor's quizzes, to choose from.", AiCoverJob.TYPE,
            Loads.NONE, zone, ctx -> prepareCover(ctx, userKeys));
    }

    /**
     * Candidate translations (Q73d-1): the same checks, locks and limits as questions, of the job
     * {@value AiTranslateJob#TYPE}, reading the quiz's texts and the language's translations.
     */
    public static ProcessDefinition<TranslateRequestInput, RequestOutput, ProcessContext> translateRequest(
        ZoneId zone, AiUserKeys userKeys) {
        return requestProcess(TRANSLATE_REQUEST, TranslateRequestInput.class,
            "Asks the AI to translate one of the sponsor's quizzes into one of its other languages, to review.",
            AiTranslateJob.TYPE, Loads.TRANSLATION, zone, ctx -> prepareTranslation(ctx, userKeys));
    }

    /** What a request reads of the quiz besides the quiz itself. */
    private enum Loads { NONE, QUESTIONS, TRANSLATION }

    /**
     * A request of the AI: before any lock the sponsor's standing, the notice, the settings and the quiz (with its
     * materials, questions and chosen PDFs when {@code questions}), then {@code prepare}; under the sponsor's lock and
     * everybody's, the limits; then the job (with the PDFs as its files) and its record.
     */
    private static <I extends Requested> ProcessDefinition<I, RequestOutput, ProcessContext> requestProcess(
        String name, Class<I> type, String description, String jobType, Loads loads, ZoneId zone,
        java.util.function.Consumer<ProcessContext> prepare) {
        return ProcessDefinition.define(name, 1, type, RequestOutput.class, ProcessContext.class, pb -> {
            pb.description(description)
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
                    QuizEditing.QUIZ_ID, QuizEditing.QUIZ));
            if (loads == Loads.TRANSLATION) {
                TranslationProcesses.loadTexts(pb, ctx -> ctx.get(INPUT, TranslateRequestInput.class).language());
            }
            if (loads == Loads.QUESTIONS) {
                pb.step("Load its materials and questions", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbContent.sponsorDataset(QbContent.MATERIAL),
                            ctx -> QuizEditing.ofQuiz(ctx.get(QuizEditing.QUIZ_ID)), MATERIAL_ROWS),
                        QueryEntities.query(QbContent.sponsorDataset(QbContent.QUESTION),
                            ctx -> QuizEditing.ofQuiz(ctx.get(QuizEditing.QUIZ_ID)), QUESTION_ROWS)))
                    .steps(b -> QuizEditing.loadFiles(b, AiProcesses::chosenPdfs));
            }
            pb.compute("Prepare the request", (metadata, ctx) -> prepare.accept(ctx))
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
                .compute("Check the limits", (metadata, ctx) -> checkLimits(ctx, zone));
            StartExternalJob.Spec<ProcessContext> start = StartExternalJob.<ProcessContext>of(jobType,
                    ctx -> ctx.get(JOB_INPUT))
                .reference(ctx -> "quiz:" + ctx.get(QuizEditing.QUIZ_ID))
                .outputKey(EXTERNAL_JOB_ID);
            if (loads == Loads.QUESTIONS) {
                start = start.files(AiProcesses::pdfFiles);
            }
            pb.step("Start the job", start)
                .compute("Record the job", (metadata, ctx) -> record(ctx, zone));
        });
    }

    /** The PDF files of the chosen materials, as the request found them (for the file reads before it). */
    static List<UUID> chosenPdfs(ProcessContext ctx) {
        RequestInput input = ctx.get(INPUT, RequestInput.class);
        Set<String> chosen = new HashSet<>();
        input.materialIds().forEach(id -> chosen.add(id.toString()));
        List<UUID> files = new ArrayList<>();
        // Q73a: only the base materials are sent; the other languages' are not the quiz's source.
        for (EntityInstance material : QuizLanguages.bases(rows(ctx, MATERIAL_ROWS))) {
            Object pdf = material.get("pdf");
            if (chosen.contains(String.valueOf(material.id())) && MaterialKind.PDF.name().equals(material.get("kind"))
                && pdf != null) {
                files.add(QuizEditing.uuid(pdf));
            }
        }
        return files;
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> pdfFiles(ProcessContext ctx) {
        List<UUID> files = (List<UUID>) ctx.get(PDF_FILES);
        return files == null ? List.of() : files;
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

    /** What every request checks before the kind's own: standing, settings, the notice. */
    private record Common(List<Violation> problems, AiSettings settings, List<ConsentChecks.State> accept) {}

    private static Common common(ProcessContext ctx) {
        Requested input = ctx.get(INPUT, Requested.class);
        List<Violation> problems = new ArrayList<>(SponsorChecks.problems(ctx));
        AiSettings settings = settings(ctx);
        problems.addAll(settings.problems());
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
        return new Common(problems, settings, accept);
    }

    /** Refuses the request with every problem found; otherwise records the notice and what the next steps need. */
    private static void prepared(ProcessContext ctx, Common common, String kind, Object job,
        Map<String, Object> params, int size, long estimate, List<UUID> pdfs) {
        if (!common.problems().isEmpty()) {
            throw new BusinessRuleViolationException(common.problems());
        }
        ConsentChecks.record(ctx, ctx.request().actorId(), common.accept(), QbLegal.SOURCE_AI);
        ctx.put(JOB_INPUT, job);
        ctx.put(JOB_PARAMS, JSON.writeValueAsString(params));
        ctx.put(INPUT_BYTES, size);
        ctx.put(SETTINGS, common.settings());
        ctx.put(KIND, kind);
        ctx.put(MODEL, common.settings().model());
        ctx.put(ESTIMATE, estimate);
        ctx.put(PDF_FILES, List.copyOf(pdfs));
    }

    static void prepare(ProcessContext ctx, AiUserKeys userKeys, AiPdfs.Limits pdfLimits) {
        RequestInput input = ctx.get(INPUT, RequestInput.class);
        String sponsorId = ctx.request().actorId();
        Common common = common(ctx);
        List<Violation> problems = common.problems();
        AiSettings settings = common.settings();
        int maxCount = AiQuestionsJob.maxCount(settings.effort());
        if (input.count() > maxCount) {
            problems.add(new Violation("count", AiCodes.COUNT_TOO_HIGH, "At most " + maxCount + " questions at a time "
                + "at effort " + settings.effort(), Map.of("max", maxCount)));
        }

        EntityInstance quiz = QuizEditing.quiz(ctx);
        if (QbContent.KIND_SURVEY.equals(quiz.get("kind"))) {
            // The prompt and the candidates' rules call for correct options; a survey has none (Q12, D-Q12-9).
            problems.add(new Violation("quizId", AiCodes.SURVEY_UNSUPPORTED, "Questions are not written for surveys",
                Map.of()));
        }
        List<EntityInstance> materials = QuizLanguages.bases(rows(ctx, MATERIAL_ROWS));
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
        List<UUID> pdfs = new ArrayList<>();
        List<Map<String, Object>> pdfSizes = new ArrayList<>();
        long pdfBytes = 0;
        for (EntityInstance material : materials) {
            String id = String.valueOf(material.id());
            if (!chosen.contains(id)) {
                continue;
            }
            String kind = material.get("kind");
            String body = MaterialKind.ARTICLE.name().equals(kind) ? material.get("body") : null;
            // A PDF goes as the file itself (Q8b), read by the job; its size is the stored file's.
            EntityInstance file = MaterialKind.PDF.name().equals(kind) && material.get("pdf") != null
                ? QuizEditing.list(ctx, QuizEditing.FILES).stream().filter(row -> String.valueOf(row.id())
                    .equals(String.valueOf((Object) material.get("pdf")))).findFirst().orElse(null) : null;
            UUID fileId = file == null ? null : QuizEditing.uuid(file.id());
            AiQuestionsJob.Material item = new AiQuestionsJob.Material(numbers.get(id), kind, material.get("title"),
                material.get("description"), body, fileId);
            sent.add(item);
            sizes.add(Map.of("materialId", id, "no", item.no(), "bytes", bytes(JSON.writeValueAsString(item))));
            if (file != null) {
                long size = QuizEditing.number(file.get(FileEntities.SIZE_BYTES));
                pdfs.add(fileId);
                pdfBytes += size;
                pdfSizes.add(Map.of("materialId", id, "no", item.no(), "bytes", size));
                if (size > pdfLimits.maxBytes()) {
                    problems.add(new Violation("materialIds", AiCodes.PDF_TOO_LARGE, "The PDF of material "
                        + item.no() + " is " + size + " bytes, more than " + pdfLimits.maxBytes(), Map.of("no",
                        item.no(), "size", size, "max", pdfLimits.maxBytes())));
                }
            }
        }
        if (pdfBytes > AiQuestionsJob.MAX_PDF_TOTAL_BYTES) {
            problems.add(new Violation("materialIds", AiCodes.PDF_TOO_LARGE, "The PDFs are " + pdfBytes
                + " bytes together, more than " + AiQuestionsJob.MAX_PDF_TOTAL_BYTES + ": choose fewer",
                Map.of("size", pdfBytes, "max", AiQuestionsJob.MAX_PDF_TOTAL_BYTES, "materials", pdfSizes)));
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("count", input.count());
        params.put("difficulty", input.difficulty());
        params.put("language", input.language());
        params.put("avoidExisting", !Boolean.FALSE.equals(input.avoidExisting()));
        params.put("materialIds", sent.stream().map(m -> materials.stream()
            .filter(row -> numbers.get(String.valueOf(row.id())) == m.no()).findFirst().orElseThrow().id()
            .toString()).toList());
        params.put("materialNos", sent.stream().map(AiQuestionsJob.Material::no).toList());
        params.put("pdfMaterialNos", sent.stream().filter(m -> m.fileId() != null)
            .map(AiQuestionsJob.Material::no).toList());
        // The pages are not known here (nothing reads the PDFs, review H1/H2): the most a request may send is reserved,
        // and the job's provider count of what it sends may not be above it.
        int pages = pdfs.isEmpty() ? 0 : pdfLimits.maxPages();
        long reservedInput = AiLimits.reservedInput(size, pages);
        params.put("reservedInputTokens", reservedInput);
        long estimate = AiLimits.estimate(size, ClaudeApi.maxTokens(input.count()), pages);
        AiQuestionsJob.Input reserved = new AiQuestionsJob.Input(job.model(), job.effort(), job.language(),
            job.count(), job.difficulty(), job.title(), job.intro(), job.materials(), job.existingStems(),
            job.userKey(), reservedInput);
        prepared(ctx, common, QbAi.KIND_QUESTIONS, reserved, params, size, estimate, pdfs);
    }

    static void prepareCover(ProcessContext ctx, AiUserKeys userKeys) {
        CoverRequestInput input = ctx.get(INPUT, CoverRequestInput.class);
        Common common = common(ctx);
        EntityInstance quiz = QuizEditing.quiz(ctx);
        AiSettings settings = common.settings();
        AiCoverJob.Input job = new AiCoverJob.Input(settings.model(), settings.effort(), input.language(),
            quiz.get("title"), quiz.get("intro"), userKeys.of(ctx.request().actorId()));
        int size = Math.max(bytes(AiCoverPrompt.user(job, JSON)), bytes(JSON.writeValueAsString(job)));
        if (size > ClaudeApi.MAX_INPUT_BYTES) {
            common.problems().add(new Violation(null, AiCodes.INPUT_TOO_LARGE, "The request is " + size
                + " bytes, more than " + ClaudeApi.MAX_INPUT_BYTES, Map.of("size", size, "max",
                ClaudeApi.MAX_INPUT_BYTES)));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("count", CoverDesigns.COVERS);
        params.put("language", input.language());
        long reservedInput = AiLimits.reservedInput(size, 0);
        params.put("reservedInputTokens", reservedInput);
        prepared(ctx, common, QbAi.KIND_COVER, new AiCoverJob.Input(job.model(), job.effort(), job.language(),
            job.title(), job.intro(), job.userKey(), reservedInput), params, size,
            AiLimits.estimate(size, AiCoverJob.MAX_TOKENS, 0), List.of());
    }

    /**
     * A translation's job (Q73d-1): the segments to translate (§5.1: texts only), in batches; the language must be
     * one of the quiz's other languages and there must be something to translate. The reservation is every batch's
     * call: its input at most the largest batch's, and all the output it asks for.
     */
    static void prepareTranslation(ProcessContext ctx, AiUserKeys userKeys) {
        TranslateRequestInput input = ctx.get(INPUT, TranslateRequestInput.class);
        Common common = common(ctx);
        EntityInstance quiz = QuizEditing.quiz(ctx);
        String base = String.valueOf((Object) quiz.get("language"));
        boolean all = Boolean.TRUE.equals(input.all());
        if (!ContentLanguages.parseList(quiz.get("translationLanguages")).contains(input.language())) {
            common.problems().add(new Violation("language", ContentCodes.TRANSLATION_LANGUAGE, "The quiz has no "
                + "language " + input.language() + " besides its base", Map.of("language", input.language())));
        }
        List<AiTranslateJob.Segment> segments = AiTranslations.segments(quiz, TranslationProcesses.questions(ctx),
            TranslationProcesses.options(ctx), TranslationProcesses.translations(ctx), all);
        if (segments.isEmpty()) {
            common.problems().add(new Violation("all", AiCodes.NOTHING_TO_TRANSLATE, "Every text of "
                + input.language() + " is translated and up to date", Map.of("language", input.language())));
        }
        AiTranslateJob.Input job = new AiTranslateJob.Input(common.settings().model(), base, input.language(),
            quiz.get("title"), segments, userKeys.of(ctx.request().actorId()), null);
        int size = bytes(JSON.writeValueAsString(job));
        if (size > AiTranslateJob.MAX_JOB_INPUT_BYTES) {
            common.problems().add(new Violation(null, AiCodes.INPUT_TOO_LARGE, "The request is " + size
                + " bytes, more than " + AiTranslateJob.MAX_JOB_INPUT_BYTES, Map.of("size", size, "max",
                AiTranslateJob.MAX_JOB_INPUT_BYTES)));
        }
        List<List<AiTranslateJob.Segment>> batches = AiTranslateJob.batches(segments);
        int largest = 0;
        for (List<AiTranslateJob.Segment> batch : batches) {
            largest = Math.max(largest, bytes(AiTranslatePrompt.SYSTEM) + bytes(AiTranslatePrompt.user(job, batch,
                JSON)));
        }
        long reservedInput = AiLimits.reservedInput(largest, 0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("language", input.language());
        params.put("from", base);
        params.put("all", all);
        params.put("segments", segments.size());
        params.put("batches", batches.size());
        params.put("reservedInputTokens", reservedInput);
        long estimate = (long) AiQuestionsJob.ATTEMPTS * batches.size()
            * (reservedInput + AiTranslateJob.BATCH_MAX_TOKENS);
        prepared(ctx, common, QbAi.KIND_TRANSLATION, new AiTranslateJob.Input(job.model(), job.from(), job.to(),
            job.title(), job.segments(), job.userKey(), reservedInput), params, size, estimate, List.of());
    }

    static void checkLimits(ProcessContext ctx, ZoneId zone) {
        String quizId = String.valueOf((Object) ctx.get(QuizEditing.QUIZ_ID));
        List<EntityInstance> open = rows(ctx, OPEN);
        // One job of each kind a quiz at a time: covers may be drawn while questions are written.
        String kind = (String) ctx.get(KIND);
        boolean quizBusy = open.stream().anyMatch(job -> quizId.equals(String.valueOf((Object) job.get("quizId")))
            && kind.equals(job.get("kind")));
        long openEstimate = 0;
        for (EntityInstance job : open) {
            openEstimate += QuizEditing.number(job.get("estimatedTokens"));
        }
        String sponsorId = ctx.request().actorId();
        EntityInstance mine = usageRow(ctx, sponsorId);
        EntityInstance everybody = usageRow(ctx, QbAi.EVERYBODY);
        AiLimits.Standing standing = new AiLimits.Standing(open.size(), quizBusy, rows(ctx, TODAY).size(),
            tokens(mine), openEstimate, tokens(everybody), globalOpenEstimate(ctx));
        long estimate = (long) ctx.get(ESTIMATE);
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
        int size = (int) ctx.get(INPUT_BYTES);
        long estimate = (long) ctx.get(ESTIMATE);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("externalJobId", String.valueOf((Object) ctx.get(EXTERNAL_JOB_ID)));
        values.put("sponsorId", ctx.request().actorId());
        values.put("quizId", ctx.get(QuizEditing.QUIZ_ID));
        values.put("kind", ctx.get(KIND));
        values.put("params", ctx.get(JOB_PARAMS));
        values.put("model", ctx.get(MODEL));
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
        ClaudeApi.Usage usage = charged(completion.usage(), completion.unrecordedAttempts(), job);
        String month = AiLimits.month(completion.finishedTime(), zone);
        if (QbAi.KIND_COVER.equals(job.get("kind"))) {
            completeCover(ctx, completion, job, usage, month);
            return;
        }
        if (QbAi.KIND_TRANSLATION.equals(job.get("kind"))) {
            completeTranslation(ctx, completion, job, usage, month);
            return;
        }
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

    /**
     * A cover job's result: the designs drawn and the files the platform stored for them ({@code cover-1..n}). Kept for
     * the sponsor's choice ({@code READY}, the pictures referred to from the job so that the sweep keeps them), or none
     * drawn ({@code FAILED}, {@link AiCodes#NO_CANDIDATES}).
     */
    private static void completeCover(ProcessContext ctx, ExternalJobCompletion completion, EntityInstance job,
        ClaudeApi.Usage usage, String month) {
        AiCoverJob.Result result = completion.result(AiCoverJob.Result.class);
        BigDecimal cost = AiCost.of(job.get("model"), result.servedModel(), usage, settings(ctx).price());
        Map<String, Object> values = finished(usage, cost, completion.attempts(), completion.finishedTime());
        boolean failed = false;
        if (QbAi.QUEUED.equals(job.get("status"))) {
            values.put("servedModel", result.servedModel());
            List<Map<String, Object>> covers = new ArrayList<>();
            int invalid = result.invalid();
            for (int i = 0; i < result.designs().size(); i++) {
                UUID file = completion.files().get(AiCoverJob.fileKey(i + 1));
                CoverDesigns.Design design = result.designs().get(i);
                // Checked again as stored: the handler's check is not the last word (review L3).
                if (file == null || covers.size() >= CoverDesigns.COVERS || design == null
                    || CoverDesigns.palette(design.palette()) == null || !CoverDesigns.layout(design.layout())
                    || !CoverDesigns.motif(design.motif()) || !CoverDesigns.text(design.text())) {
                    invalid++;
                    continue;
                }
                values.put(QbAi.COVER_FIELDS.get(covers.size()), file);
                covers.add(CoverDesigns.stored(covers.size() + 1, design, file.toString()));
            }
            values.put("invalidCount", BigDecimal.valueOf(invalid));
            values.put("candidateCount", BigDecimal.valueOf(covers.size()));
            if (covers.isEmpty()) {
                failed = true;
                values.put("status", QbAi.FAILED);
                values.put("failureCode", AiCodes.NO_CANDIDATES);
                values.put("failureMessage", "None of the " + invalid + " cover designs could be drawn");
            } else {
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("schema", 1);
                document.put("covers", covers);
                values.put("status", QbAi.READY);
                values.put("candidates", JSON.writeValueAsString(document));
                values.put("expiresAt", completion.finishedTime().plus(Duration.ofDays(settings(ctx).expireDays())));
            }
        }
        ctx.changes().update(QbAi.JOB, job.id(), job.version(), values);
        addUsage(ctx, completion.requestedBy(), month, usage, cost, failed);
        ctx.put(OUTPUT, new JobOutput(String.valueOf(job.id()), (String) values.getOrDefault("status",
            job.get("status"))));
    }

    /**
     * A translation job's result (Q73d-1): its candidates checked again and kept for the sponsor's review
     * ({@code READY}), never written into the quiz here; none usable is {@code FAILED} ({@link AiCodes#NO_CANDIDATES}).
     */
    private static void completeTranslation(ProcessContext ctx, ExternalJobCompletion completion, EntityInstance job,
        ClaudeApi.Usage usage, String month) {
        AiTranslateJob.Result result = completion.result(AiTranslateJob.Result.class);
        BigDecimal cost = AiCost.of(job.get("model"), result.servedModel(), usage, settings(ctx).price());
        Map<String, Object> values = finished(usage, cost, completion.attempts(), completion.finishedTime());
        boolean failed = false;
        if (QbAi.QUEUED.equals(job.get("status"))) {
            values.put("servedModel", result.servedModel());
            AiTranslations.Checked checked = AiTranslations.check(result.translations());
            int invalid = result.invalid() + checked.invalid();
            values.put("invalidCount", BigDecimal.valueOf(invalid));
            values.put("candidateCount", BigDecimal.valueOf(checked.valid().size()));
            if (checked.valid().isEmpty()) {
                failed = true;
                values.put("status", QbAi.FAILED);
                values.put("failureCode", AiCodes.NO_CANDIDATES);
                values.put("failureMessage", "None of the " + invalid + " translations was usable");
            } else {
                values.put("status", QbAi.READY);
                values.put("candidates", AiTranslations.toJson(String.valueOf(params(job).get("language")),
                    checked.valid()));
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
        ClaudeApi.Usage usage = charged(failure.usage(), failure.unrecordedAttempts(), job);
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
            metrics.getOrDefault(AiQuestionsJob.FALLBACK_OUTPUT_TOKENS, 0L),
            metrics.getOrDefault(AiQuestionsJob.LONG_INPUT_TOKENS, 0L),
            metrics.getOrDefault(AiQuestionsJob.LONG_OUTPUT_TOKENS, 0L));
    }

    /**
     * What a finished job is charged: the usage its attempts recorded, and for each attempt that ended without
     * recording any ({@code unrecordedAttempts}, platform decision D52 item 5: its instance died, or it timed out,
     * before it could report) what {@link ClaudeApi#unanswered} charges an attempt that got no answer, since the
     * provider may have billed it all the same (W-6). A late instance's own usage, recorded before the outcome ran,
     * is in {@code metrics} and that attempt is no longer counted, so nothing is charged twice.
     */
    static ClaudeApi.Usage charged(Map<String, Long> metrics, int unrecordedAttempts, EntityInstance job) {
        ClaudeApi.Usage recorded = usage(metrics);
        if (unrecordedAttempts <= 0) {
            return recorded;
        }
        ClaudeApi.Usage each = unrecordedAttempt(job);
        long n = unrecordedAttempts;
        return new ClaudeApi.Usage(recorded.inputTokens() + n * each.inputTokens(),
            recorded.outputTokens() + n * each.outputTokens(), recorded.fallbackInputTokens(),
            recorded.fallbackOutputTokens(), recorded.longPromptInputTokens() + n * each.longPromptInputTokens(),
            recorded.longPromptOutputTokens() + n * each.longPromptOutputTokens());
    }

    /**
     * One unrecorded attempt of the job, from its stored parameters: the input it reserved for an attempt
     * ({@code reservedInputTokens}: the text's estimate, and the pages of a request with PDF documents) and all the
     * output it asked for ({@link ClaudeApi#maxTokens} of its count; {@link AiCoverJob#MAX_TOKENS} for covers),
     * at the requested model's long-prompt rate when the input is above that line, as a recorded call would be.
     */
    static ClaudeApi.Usage unrecordedAttempt(EntityInstance job) {
        Map<String, Object> params = params(job);
        long input = params.get("reservedInputTokens") instanceof Number reserved ? reserved.longValue()
            : AiLimits.reservedInput(QuizEditing.number(job.get("inputBytes")), 0);
        if (QbAi.KIND_TRANSLATION.equals(job.get("kind"))) {
            // Every batch's call (Q73d-1): its reserved input and all the output it asks for.
            int batches = Math.max(1, ((Number) params.getOrDefault("batches", 1)).intValue());
            return ClaudeCalls.priced(job.get("model"), ClaudeApi.unrecorded(input * batches,
                AiTranslateJob.BATCH_MAX_TOKENS * batches));
        }
        int maxTokens = QbAi.KIND_COVER.equals(job.get("kind")) ? AiCoverJob.MAX_TOKENS
            : ClaudeApi.maxTokens(((Number) params.getOrDefault("count", AiQuestionsJob.MAX_COUNT)).intValue());
        return ClaudeCalls.priced(job.get("model"), ClaudeApi.unrecorded(input, maxTokens));
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

    // ---- a cancelled job ---------------------------------------------------------------------------------------------

    /**
     * {@value #CANCELLED_JOB}: both job types' {@code onCancelled}, run once by the platform when a cancelled job has
     * ended (at once when no attempt was running, otherwise after the running one), whoever cancelled it. Under the
     * sponsor's lock and everybody's, the job is closed and charged what its attempts used, as a failure is: the
     * platform's usage of every attempt (the cancelled one included), which the attempts recorded as they went
     * ({@code ClaudeCalls}: an unanswered call its estimate, the fallback model's and the long prompts' parts apart),
     * and the same estimate for each attempt that recorded nothing ({@link #charged}: one whose instance died).
     * A job cancelled before any attempt ran cost nothing and is charged nothing. A job an operator stopped while it
     * was still queued here is {@code CANCELLED} with {@link AiCodes#STOPPED}; the sponsor's own cancellation closed
     * it already ({@value #CANCEL}).
     */
    public static ProcessDefinition<ExternalJobCancellation, JobOutput, ProcessContext> cancelled(ZoneId zone) {
        return ProcessDefinition.define(CANCELLED_JOB, 1, ExternalJobCancellation.class, JobOutput.class,
            ProcessContext.class, pb -> pb
                .description("Closes a cancelled AI job and charges what its attempts used.")
                .permissions(QbPermissions.AI_SYSTEM)
                .internal()
                .contextFactory(AiProcesses::context)
                .outputMapper(AiProcesses::jobOutput)
                .step("Lock the sponsor's AI requests", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAi.lockName(ctx.get(INPUT, ExternalJobCancellation.class).requestedBy())))
                .step("Lock everybody's usage", HoldLock.<ProcessContext>exclusive(ctx -> QbAi.GLOBAL_LOCK))
                .step("Load the job and the usage", outcomeReads(zone,
                    ctx -> ctx.get(INPUT, ExternalJobCancellation.class).jobId(),
                    ctx -> ctx.get(INPUT, ExternalJobCancellation.class).requestedBy(),
                    ctx -> ctx.get(INPUT, ExternalJobCancellation.class).finishedTime()))
                .compute("Close the job", (metadata, ctx) -> closeCancelled(ctx, zone)));
    }

    static void closeCancelled(ProcessContext ctx, ZoneId zone) {
        ExternalJobCancellation cancellation = ctx.get(INPUT, ExternalJobCancellation.class);
        EntityInstance job = unfinished(ctx, cancellation.requestedBy());
        if (job == null) {
            return;
        }
        // Cancelled while it waited: no attempt ran, nothing was sent or billed.
        ClaudeApi.Usage usage = cancellation.attemptRan()
            ? charged(cancellation.usage(), cancellation.unrecordedAttempts(), job) : new ClaudeApi.Usage(0, 0);
        BigDecimal cost = AiCost.of(job.get("model"), null, usage, settings(ctx).price());
        Map<String, Object> values = finished(usage, cost, cancellation.attempts(), cancellation.finishedTime());
        if (QbAi.QUEUED.equals(job.get("status"))) {
            // Not the sponsor's own cancellation (that closed the job already): an operator's.
            values.put("status", QbAi.CANCELLED);
            values.put("failureCode", AiCodes.STOPPED);
            values.put("failureMessage", "Stopped at the platform");
            values.put("closedAt", cancellation.cancelledTime());
        }
        ctx.changes().update(QbAi.JOB, job.id(), job.version(), values);
        if (cancellation.attemptRan()) {
            // A cancellation is no failure: counted among the jobs, not the failed ones (the admin's failure rate).
            addUsage(ctx, cancellation.requestedBy(), AiLimits.month(cancellation.finishedTime(), zone), usage, cost,
                false);
        }
        ctx.put(OUTPUT, new JobOutput(String.valueOf(job.id()), (String) values.getOrDefault("status",
            job.get("status"))));
    }

    // ---- accept ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AcceptInput, AcceptOutput, ProcessContext> accept() {
        return ProcessDefinition.define(ACCEPT, 1, AcceptInput.class, AcceptOutput.class, ProcessContext.class,
            pb -> pb
                .description("Adds the AI candidates the sponsor reviewed to the quiz, as the sponsor left them.")
                .permissions(QbPermissions.AI_USE, QbPermissions.CONTENT_WRITE)
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
        requireKind(job, QbAi.KIND_QUESTIONS);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        if (QbContent.KIND_SURVEY.equals(quiz.get("kind"))) {
            // Candidates have correct options and points; a survey has neither (Q12, D-Q12-9).
            ctx.reject(new Violation("quizId", AiCodes.SURVEY_UNSUPPORTED, "Questions are not written for surveys",
                Map.of()));
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
                clearCovers(values, job);
                return values;
            });
    }

    /**
     * The sponsor stops a queued job of their own: closed here at once, and its platform job (the one of the same id,
     * {@code externalJobId}) cancelled in the same transaction, so that a job still waiting never calls the provider
     * and a running one ends after its attempt. What it used is charged once it has ended ({@value #CANCELLED_JOB}).
     * A platform job that ended meanwhile is no error: its outcome process charges it, as before.
     */
    public static ProcessDefinition<JobRef, JobOutput, ProcessContext> cancel() {
        return own(CANCEL, "Stops an AI job; what its attempts used is charged once it has ended.",
            job -> QbAi.QUEUED,
            (ctx, job) -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", QbAi.CANCELLED);
                values.put("closedAt", ctx.opTime());
                return values;
            },
            pb -> pb
                .step("Cancel the platform's job", CancelExternalJob.<ProcessContext>of(
                    List.of(AiQuestionsJob.TYPE, AiCoverJob.TYPE), AiProcesses::platformJobId).ifOpen()));
    }

    /**
     * The platform's job of the sponsor's job (its {@code externalJobId}, unique). The platform's step cancels only a
     * job of the two AI types that the process's actor, the sponsor, started (anyone else's is 404, under
     * {@value #CANCEL}'s own check of the sponsor's job); one that ended meanwhile is left to its outcome process
     * ({@code ifOpen}).
     */
    private static String platformJobId(ProcessContext ctx) {
        Object externalJobId = first(ctx, JOB).get("externalJobId");
        return String.valueOf(Objects.requireNonNull(externalJobId,
            "The AI job has no platform job (externalJobId is required)"));
    }

    private static ProcessDefinition<JobRef, JobOutput, ProcessContext> own(String name, String description,
        java.util.function.Function<EntityInstance, String> from,
        java.util.function.BiFunction<ProcessContext, EntityInstance, Map<String, Object>> change) {
        return own(name, description, from, change, pb -> { });
    }

    private static ProcessDefinition<JobRef, JobOutput, ProcessContext> own(String name, String description,
        java.util.function.Function<EntityInstance, String> from,
        java.util.function.BiFunction<ProcessContext, EntityInstance, Map<String, Object>> change,
        java.util.function.Consumer<com.jabiz.process.ProcessDefinitionBuilder<JobRef, JobOutput, ProcessContext>>
            after) {
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
            })
            .steps(after));
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
                clearCovers(values, job);
                ctx.changes().update(QbAi.JOB, job.id(), job.version(), values);
                ctx.put(OUTPUT, new JobOutput(String.valueOf(job.id()), QbAi.EXPIRED));
            }));
    }

    // ---- a cover chosen ---------------------------------------------------------------------------------------------

    /**
     * {@value #COVER_ACCEPT}: under {@code qb.quiz:<quiz>} then {@code qb.ai:<sponsor>}, the chosen candidate of the
     * sponsor's ready cover job becomes the quiz's cover through the quiz's own editing (the next revision, unversioned
     * changes, the file the quiz owner's upload), and the quiz {@code aiGenerated}; the job is closed and its pictures
     * no longer referred to, so the ones not chosen go with the file sweep.
     */
    public static ProcessDefinition<CoverAcceptInput, EditOutput, ProcessContext> coverAccept() {
        return ProcessDefinition.define(COVER_ACCEPT, 1, CoverAcceptInput.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Makes the AI cover the sponsor chose the quiz's cover.")
                .permissions(QbPermissions.AI_USE, QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(QuizEditing.QUIZ_ID, input.quizId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .step("Lock the sponsor's AI requests", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAi.lockName(ctx.request().actorId())))
                .step("Load the job", loadJob(ctx -> ctx.get(INPUT, CoverAcceptInput.class).aiJobId()))
                .steps(b -> QuizEditing.loadFiles(b, ctx -> {
                    EntityInstance job = first(ctx, JOB);
                    List<UUID> files = new ArrayList<>();
                    if (job != null) {
                        QbAi.COVER_FIELDS.forEach(field -> {
                            if (job.get(field) != null) {
                                files.add(QuizEditing.uuid(job.get(field)));
                            }
                        });
                    }
                    return files;
                }))
                .compute("Use the cover", (metadata, ctx) -> acceptCover(ctx)));
    }

    static void acceptCover(ProcessContext ctx) {
        CoverAcceptInput input = ctx.get(INPUT, CoverAcceptInput.class);
        EntityInstance quiz = QuizEditing.quiz(ctx);
        EntityInstance job = own(ctx);
        if (!input.quizId().toString().equals(String.valueOf((Object) job.get("quizId")))) {
            throw new EntityNotFoundException("AI job not found");
        }
        requireKind(job, QbAi.KIND_COVER);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        requireReady(ctx, job);
        int no = input.candidateNo();
        Object chosen = no <= QbAi.COVER_FIELDS.size() ? job.get(QbAi.COVER_FIELDS.get(no - 1)) : null;
        if (chosen == null) {
            throw new BusinessRuleViolationException(List.of(new Violation("candidateNo", AiCodes.CANDIDATE_UNKNOWN,
                "Candidate " + no + " is not one of the job's", Map.of("candidateNo", no))));
        }
        UUID file = QuizEditing.uuid(chosen);
        // The platform stored the pictures as the requester's uploads (decision D50): the owner's, as for any cover.
        QuizEditing.requireOwnFile(ctx, quiz.get("ownerId"), "candidateNo", file, quiz.get("cover"));
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> heading = new LinkedHashMap<>();
        heading.put("cover", file);
        heading.put("aiGenerated", true);
        QuizEditing.touch(ctx, quiz, heading, null, List.of());

        Map<String, Object> jobValues = new LinkedHashMap<>();
        jobValues.put("status", QbAi.ACCEPTED);
        jobValues.put("candidates", null);
        jobValues.put("acceptedCount", BigDecimal.ONE);
        jobValues.put("closedAt", ctx.opTime());
        clearCovers(jobValues, job);
        ctx.changes().update(QbAi.JOB, job.id(), job.version(), jobValues);
    }

    /** A closed cover job refers to its pictures no more: the sweep takes the ones not chosen. */
    // ---- translations (Q73d-1) ---------------------------------------------------------------------------------

    /**
     * {@value #TRANSLATION_ACCEPT}: under {@code qb.quiz:<quiz>} then {@code qb.ai:<sponsor>}, the candidate
     * translations the sponsor reviewed (each {@code reviewed}, D-Q73-9; the text as the sponsor left it) are written
     * by the rules of {@code QB_TRANSLATION_SAVE} ({@link TranslationProcesses#apply}), recorded as the AI's with the
     * job and the base text's hash as the AI was given it. The job stays ready with the candidates not yet accepted,
     * and is accepted once none is left. No candidate is ever written without this.
     */
    public static ProcessDefinition<TranslationAcceptInput, EditOutput, ProcessContext> translationAccept() {
        return ProcessDefinition.define(TRANSLATION_ACCEPT, 1, TranslationAcceptInput.class, EditOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Writes the AI translations the sponsor reviewed into the quiz, as the sponsor left them.")
                    .permissions(QbPermissions.AI_USE, QbPermissions.CONTENT_WRITE)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .actsOn(QbContent.QUIZ, "quizId")
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        ctx.put(QuizEditing.QUIZ_ID, input.quizId());
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                    .steps(QuizEditing::lockAndLoad)
                    .step("Lock the sponsor's AI requests", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbAi.lockName(ctx.request().actorId())))
                    .step("Load the job", QueryEntities.<ProcessContext>of(QbAi.defaultDataset(QbAi.JOB),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("aiJobId",
                            ctx.get(INPUT, TranslationAcceptInput.class).aiJobId().toString())).limit(1).build(), JOB));
                TranslationProcesses.loadTexts(pb, ctx -> String.valueOf(params(own(ctx)).get("language")));
                pb.compute("Write the translations", (metadata, ctx) -> acceptTranslations(ctx));
            });
    }

    static void acceptTranslations(ProcessContext ctx) {
        TranslationAcceptInput input = ctx.get(INPUT, TranslationAcceptInput.class);
        EntityInstance job = own(ctx);
        if (!input.quizId().toString().equals(String.valueOf((Object) job.get("quizId")))) {
            throw new EntityNotFoundException("AI job not found");
        }
        requireKind(job, QbAi.KIND_TRANSLATION);
        requireReady(ctx, job);
        List<AiTranslateJob.Translated> candidates = AiTranslations.fromJson(job.get("candidates"));
        Map<Integer, AiTranslateJob.Translated> byNo = new LinkedHashMap<>();
        candidates.forEach(candidate -> byNo.put(candidate.no(), candidate));
        List<Violation> problems = new ArrayList<>();
        List<TranslationProcesses.Entry> entries = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < input.translations().size(); i++) {
            AcceptedTranslation accepted = input.translations().get(i);
            AiTranslateJob.Translated candidate = byNo.get(accepted.candidateNo());
            if (candidate == null || !seen.add(accepted.candidateNo())) {
                problems.add(new Violation("translations[" + i + "].candidateNo", AiCodes.CANDIDATE_UNKNOWN,
                    "Candidate " + accepted.candidateNo() + " is not one of the job's, or is given twice",
                    Map.of("candidateNo", accepted.candidateNo())));
                continue;
            }
            entries.add(new TranslationProcesses.Entry(candidate.key(), accepted.text().strip(), null));
            hashes.put(candidate.key(), candidate.sourceHash());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        String language = String.valueOf(params(job).get("language"));
        if (!TranslationProcesses.apply(ctx, new TranslationProcesses.SaveInput(input.quizId(), language, entries,
            input.baseRevision()), String.valueOf(job.id()), hashes)) {
            return;
        }
        List<AiTranslateJob.Translated> left = candidates.stream()
            .filter(candidate -> !seen.contains(candidate.no())).toList();
        Map<String, Object> jobValues = new LinkedHashMap<>();
        jobValues.put("acceptedCount", BigDecimal.valueOf(QuizEditing.number(job.get("acceptedCount"))
            + entries.size()));
        if (left.isEmpty()) {
            jobValues.put("status", QbAi.ACCEPTED);
            jobValues.put("candidates", null);
            jobValues.put("closedAt", ctx.opTime());
        } else {
            jobValues.put("candidates", AiTranslations.toJson(language, left));
        }
        ctx.changes().update(QbAi.JOB, job.id(), job.version(), jobValues);
    }

    private static void clearCovers(Map<String, Object> values, EntityInstance job) {
        if (QbAi.KIND_COVER.equals(job.get("kind"))) {
            QbAi.COVER_FIELDS.forEach(field -> values.put(field, null));
        }
    }

    private static void requireKind(EntityInstance job, String kind) {
        if (!kind.equals(job.get("kind"))) {
            throw new BusinessRuleViolationException(List.of(new Violation("aiJobId", AiCodes.WRONG_KIND,
                "The AI job is of kind " + job.get("kind"), Map.of("kind", String.valueOf((Object) job.get("kind"))))));
        }
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
