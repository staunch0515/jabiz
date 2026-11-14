package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.transfer.QuizFileFormat;
import com.jabiz.quizbuks.content.transfer.QuizTransferProcesses;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Get to know us" (F-C7b-2, docs/quizbuks/plans/impl/F-C7.md): from a ready website draft ({@link SiteProfileJob}) the
 * sponsor makes a quiz draft whose materials are the articles taken from the website, and keeps the drafted questions
 * for review. {@value #QUIZ} writes nothing the sponsor has not asked for and nothing without their review:
 * <ul>
 *   <li>the quiz is made by the usual {@value QuizTransferProcesses#IMPORT} (the same checks and limits as any quiz
 *       file), as a draft;</li>
 *   <li>the questions are kept as a {@code READY} {@code QUESTIONS} job of that quiz, checked again against the content
 *       rules ({@link AiCandidates}); they reach the quiz only through {@value AiProcesses#ACCEPT}, question by
 *       question, each marked reviewed by the sponsor;</li>
 *   <li>no usage is counted: the website job counted what the AI used.</li>
 * </ul>
 * One quiz a draft (the website job's {@code siteQuizId}); the draft stays for the profile's review.
 */
public final class SiteQuizProcesses {

    public static final String QUIZ = "QB_SITE_PROFILE_QUIZ";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DOCUMENT = "siteQuizDocument";
    private static final String IMPORTED = "siteQuizImported";
    private static final String MATERIALS = "siteQuizMaterials";

    /** The titles of the quiz in the draft's base language, with the company's name. */
    private static final Map<String, String> TITLES = Map.of("en", "Get to know %s", "zh", "认识 %s", "ja", "%sを知ろう");
    /** The wording when the draft has no company name ("us" in the quiz's language): a title and an introduction. */
    private static final Map<String, String> US_TITLES = Map.of("en", "Get to know us", "zh", "认识我们", "ja", "私たちを知ろう");
    private static final Map<String, String> US_INTROS = Map.of(
        "en", "Read these articles from our website, then answer the questions.",
        "zh", "读一读我们官网上的这几篇文章，再回答问题。",
        "ja", "私たちの公式サイトの記事を読んで、問題に答えましょう。");
    private static final Map<String, String> INTROS = Map.of(
        "en", "Read these articles from the website of %s, then answer the questions.",
        "zh", "读一读 %s 官网上的这几篇文章，再回答问题。",
        "ja", "%s の公式サイトの記事を読んで、問題に答えましょう。");

    /** @param aiJobId the website draft (kind {@code SITE_PROFILE}, {@code READY}) the quiz is made from */
    public record QuizInput(@NotNull UUID aiJobId) {}

    /**
     * @param quizId       the new draft
     * @param questionsJob the job that keeps the drafted questions for review (null when none met the rules)
     */
    public record QuizOutput(UUID quizId, String questionsJob, int materials, int questions) {}

    public static ProcessDefinition<QuizInput, QuizOutput, ProcessContext> quiz() {
        return ProcessDefinition.define(QUIZ, 1, QuizInput.class, QuizOutput.class, ProcessContext.class, pb -> pb
            .description("Makes a draft of a \"get to know us\" quiz from a website draft's articles and keeps its "
                + "drafted questions for the sponsor's review.")
            .permissions(QbPermissions.AI_USE, QbPermissions.CONTENT_WRITE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory(AiProcesses::context)
            .outputMapper(ctx -> (QuizOutput) ctx.get(AiProcesses.OUTPUT))
            .step("Lock the sponsor's AI requests", HoldLock.<ProcessContext>exclusive(
                ctx -> QbAi.lockName(ctx.request().actorId())))
            .step("Load the draft", AiProcesses.loadJob(ctx -> ctx.get(AiProcesses.INPUT, QuizInput.class).aiJobId()))
            .compute("Write the quiz file", (metadata, ctx) -> write(ctx))
            .step("Make the quiz", CallProcess.<ProcessContext>when(ctx -> !ctx.hasViolations()
                    && ctx.contains(DOCUMENT), QuizTransferProcesses.IMPORT, 1,
                ctx -> new QuizTransferProcesses.ImportInput(document(ctx), Map.of(), null, true), IMPORTED))
            .compute("Keep the questions for review", (metadata, ctx) -> keep(ctx)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> document(ProcessContext ctx) {
        return (Map<String, Object>) ctx.get(DOCUMENT);
    }

    private static EntityInstance draft(ProcessContext ctx) {
        EntityInstance job = AiProcesses.first(ctx, AiProcesses.JOB);
        if (job == null || !ctx.request().actorId().equals(job.get("sponsorId"))) {
            throw new EntityNotFoundException("AI job not found");
        }
        return job;
    }

    private static void refuse(String code, String message) {
        throw new BusinessRuleViolationException(List.of(new Violation("aiJobId", code, message, Map.of())));
    }

    /** Checks the draft and writes the quiz file from its articles; nothing is made here. */
    private static void write(ProcessContext ctx) {
        EntityInstance job = draft(ctx);
        if (!QbAi.KIND_SITE_PROFILE.equals(job.get("kind"))) {
            refuse(AiCodes.WRONG_KIND, "The AI job is of kind " + job.get("kind"));
        }
        if (!QbAi.READY.equals(job.get("status"))) {
            refuse(AiCodes.JOB_NOT_READY, "The AI job is " + job.get("status"));
        }
        if (!com.jabiz.quizbuks.content.QuizEditing.instant(job.get("expiresAt")).isAfter(ctx.opTime())) {
            refuse(AiCodes.JOB_EXPIRED, "The AI candidates expired");
        }
        if (job.get("siteQuizId") != null) {
            refuse(AiCodes.SITE_QUIZ_MADE, "A quiz was made from this draft already");
        }
        JsonNode stored = JSON.readTree(String.valueOf((Object) job.get("candidates")));
        JsonNode materials = stored.path("materials");
        if (!materials.isArray() || materials.isEmpty()) {
            refuse(AiCodes.SITE_NO_QUIZ, "The draft has no articles for a quiz");
        }
        Map<String, Object> params = AiProcesses.params(job);
        String language = params.get("language") instanceof String l && TITLES.containsKey(l) ? l : "en";
        String company = company(stored.path("profile"));
        List<Map<String, Object>> articles = new ArrayList<>();
        for (JsonNode material : materials) {
            Map<String, Object> article = new LinkedHashMap<>();
            article.put("kind", "ARTICLE");
            article.put("title", material.path("title").asString());
            article.put("body", material.path("body").asString());
            // The page it was taken from; a longer address would not fit a material's description.
            if (material.path("url").isString()
                && material.path("url").asString().length() <= com.jabiz.quizbuks.content.ContentLimits.MATERIAL_DESCRIPTION_LENGTH) {
                article.put("description", material.path("url").asString());
            }
            articles.add(article);
        }
        Map<String, Object> quiz = new LinkedHashMap<>();
        quiz.put("kind", "QUIZ");
        quiz.put("title", clip(title(language, company), com.jabiz.quizbuks.content.ContentLimits.TITLE_LENGTH));
        quiz.put("intro", intro(language, company));
        quiz.put("language", language);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", QuizFileFormat.FORMAT);
        document.put("formatVersion", QuizFileFormat.CURRENT);
        document.put("quiz", quiz);
        // The other languages the sponsor asked for: the quiz's title and introduction in each (fixed wording with the
        // company's name, not the AI's); the articles and questions stay in the base language until translated in the
        // quiz editor, which has the AI translation for that.
        Map<String, Object> translations = new LinkedHashMap<>();
        if (params.get("languages") instanceof List<?> asked) {
            for (Object other : asked) {
                if (other instanceof String code && !code.equals(language) && TITLES.containsKey(code)) {
                    translations.put(code, Map.of("texts", Map.of(
                        "title", clip(title(code, company), com.jabiz.quizbuks.content.ContentLimits.TITLE_LENGTH),
                        "intro", intro(code, company))));
                }
            }
        }
        if (!translations.isEmpty()) {
            document.put("translations", translations);
        }
        document.put("materials", articles);
        document.put("questions", List.of());
        document.put("files", Map.of());
        ctx.put(DOCUMENT, document);
        ctx.put(MATERIALS, articles.size());
    }

    /** The company's name as the draft gives it; null when the draft has none (only the quiz was asked for, or not found). */
    private static String company(JsonNode profile) {
        JsonNode name = profile.path("basics").path("company");
        String text = name.isString() ? name.asString().strip() : "";
        return text.isEmpty() ? null : text;
    }

    private static String title(String language, String company) {
        return company == null ? US_TITLES.get(language) : TITLES.get(language).formatted(company);
    }

    private static String intro(String language, String company) {
        return company == null ? US_INTROS.get(language) : INTROS.get(language).formatted(company);
    }

    private static String clip(String text, int max) {
        return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
    }

    /** Keeps the drafted questions, checked again, as a ready job of the new quiz; marks the draft as used. */
    private static void keep(ProcessContext ctx) {
        if (ctx.hasViolations() || !ctx.contains(IMPORTED)) {
            return;
        }
        EntityInstance job = draft(ctx);
        QuizTransferProcesses.ImportOutput imported = ctx.get(IMPORTED, QuizTransferProcesses.ImportOutput.class);
        int materials = (int) ctx.get(MATERIALS);
        JsonNode stored = JSON.readTree(String.valueOf((Object) job.get("candidates")));
        List<AiQuestionsJob.Candidate> raw = new ArrayList<>();
        for (JsonNode question : stored.path("questions")) {
            raw.add(JSON.treeToValue(question, AiQuestionsJob.Candidate.class));
        }
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i <= materials; i++) {
            numbers.add(i);
        }
        AiCandidates.Checked checked = AiCandidates.check(raw, raw.size(), numbers);
        String questionsJob = null;
        if (!checked.valid().isEmpty()) {
            Map<String, Object> kept = new LinkedHashMap<>();
            kept.put("count", checked.valid().size());
            kept.put("materialNos", numbers);
            kept.put("fromSiteDraft", String.valueOf(job.id()));
            Map<String, Object> values = new LinkedHashMap<>();
            // The platform's job id is unique and names nothing here: this job is the draft's, already finished.
            // Derived from the draft's id (one quiz a draft), not random: under BlockHound a random UUID reads
            // SecureRandom's seed (a blocking read of the system's entropy) and fails the test.
            values.put("externalJobId", UUID.nameUUIDFromBytes(("site-quiz:" + job.id())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            values.put("sponsorId", ctx.request().actorId());
            values.put("fromSiteDraftId", String.valueOf(job.id()));
            values.put("quizId", imported.quizId().toString());
            values.put("kind", QbAi.KIND_QUESTIONS);
            values.put("params", JSON.writeValueAsString(kept));
            values.put("model", String.valueOf((Object) job.get("model")));
            values.put("status", QbAi.READY);
            values.put("candidates", AiProcesses.candidatesJson(checked.valid()));
            values.put("candidateCount", BigDecimal.valueOf(checked.valid().size()));
            values.put("invalidCount", BigDecimal.valueOf(checked.invalid()));
            values.put("inputBytes", BigDecimal.ZERO);
            values.put("estimatedTokens", BigDecimal.ZERO);
            values.put("inputTokens", BigDecimal.ZERO);
            values.put("outputTokens", BigDecimal.ZERO);
            values.put("requestedAt", ctx.opTime());
            values.put("finishedAt", ctx.opTime());
            values.put("expiresAt", job.get("expiresAt"));
            values.put("zone", String.valueOf((Object) job.get("zone")));
            // F-M9 (plan §3, review M4): not a request of the AI (no provider, no outcome): never charged.
            values.put("feeMode", QbAi.FEE_NONE);
            values.put("reservedYen", BigDecimal.ZERO);
            questionsJob = String.valueOf(ctx.changes().insert(QbAi.JOB, values));
        }
        // The draft is used once for a quiz (the profile's review of it goes on).
        Map<String, Object> used = new LinkedHashMap<>();
        used.put("siteQuizId", imported.quizId().toString());
        ctx.changes().update(QbAi.JOB, job.id(), job.version(), used);
        ctx.put(AiProcesses.OUTPUT, new QuizOutput(imported.quizId(), questionsJob, materials, checked.valid().size()));
    }

    private SiteQuizProcesses() {}
}
