package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.publishing.SponsorChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.StartExternalJob;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drafting a profile package and a "get to know us" quiz from the sponsor's website (F-C7,
 * docs/quizbuks/plans/impl/F-C7.md): {@value #REQUEST} asks the AI ({@link SiteProfileJob}); its outcome is the shared
 * {@value AiProcesses#COMPLETED} / {@value AiProcesses#FAILED} / {@value AiProcesses#CANCELLED_JOB} of every AI job
 * (kind {@code SITE_PROFILE}, {@link #completeSite}). The request takes the sponsor's AI lock then everybody's, as every
 * AI request does, and is held to one at a time, a number a day and a number a month (parameters), besides the AI
 * limits; the draft is kept for the sponsor ({@code READY}) until it expires and is never written anywhere by the job.
 *
 * <p><b>The AI credit (F-M9):</b> the cost is counted in {@code QbAiUsage} and against the AI limits like every AI job's.
 * Charging it to the sponsor's prepaid credit is not here yet: the request reserves its estimate
 * ({@code estimatedTokens}) and the outcome knows the actual usage, which is where F-M9 reserves and settles; until it
 * does, the switch {@value QbParams#SITE_PROFILE_ENABLED} stays off.
 */
public final class SiteProfileProcesses {

    public static final String REQUEST = "QB_SITE_PROFILE_REQUEST";

    private static final String PARAMS = "siteProfileParams";
    private static final String SITE_JOBS = "siteJobs";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The pages' tokens a read may bring in, besides the instructions and the answer. */
    private static final long FRAMING_TOKENS = 5_000;

    /**
     * @param url               the sponsor's website
     * @param language          the base language of the package ({@code en}, {@code zh} or {@code ja})
     * @param otherLanguages    up to two more, translated by the AI and marked as machine-translated
     * @param profile           whether to draft the basics and the page (yes unless false)
     * @param products          whether to draft the products (yes unless false)
     * @param quiz              whether to draft the "get to know us" quiz (yes unless false)
     * @param rightsDeclared    must be true: the website is the sponsor's, or one they may use
     * @param aiNoticeVersionNo the version of the AI notice the page showed, when the sponsor accepts it now
     */
    public record RequestInput(@NotBlank @Size(max = 300) String url,
        @NotNull @Pattern(regexp = "en|zh|ja") String language,
        @Size(max = 2) List<@NotNull @Pattern(regexp = "en|zh|ja") String> otherLanguages, Boolean profile,
        Boolean products, Boolean quiz, @NotNull Boolean rightsDeclared, @Positive Integer aiNoticeVersionNo) {

        @Override
        public String toString() {
            // Recorded in the operation's summary: the website is public, nothing of the sponsor is in it.
            return "RequestInput[url=" + url + ", language=" + language + ", others=" + otherLanguages + "]";
        }
    }

    public static ProcessDefinition<RequestInput, AiProcesses.RequestOutput, ProcessContext> request(ZoneId zone,
        AiUserKeys userKeys) {
        return ProcessDefinition.define(REQUEST, 1, RequestInput.class, AiProcesses.RequestOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Asks the AI to read the sponsor's website and draft a profile package and a quiz about "
                        + "the company, to review.")
                    .permissions(QbPermissions.AI_USE)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(AiProcesses.INPUT, input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(AiProcesses.OUTPUT, AiProcesses.RequestOutput.class))
                    .steps(SponsorChecks::load)
                    .steps(b -> ConsentChecks.load(b, "Load the AI notice", List.of(QbLegal.AI_NOTICE),
                        ctx -> ctx.request().actorId()))
                    .step("Load the settings", AiProcesses.loadSettings())
                    .step("Load the parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                        QbParams.SITE_PROFILE_ENABLED, QbParams.SITE_PROFILE_MONTHLY, QbParams.SITE_PROFILE_DAILY,
                        QbParams.SITE_PROFILE_MAX_PAGES, QbParams.SITE_PROFILE_QUESTIONS,
                        QbParams.SITE_PROFILE_OPTIONS, QbParams.SITE_PROFILE_MATERIALS_MAX, QbParams.PRODUCTS_MAX))
                    .compute("Prepare the request", (metadata, ctx) -> prepare(ctx, userKeys));
                AiProcesses.limited(pb, zone);
                pb.step("Load the sponsor's site requests", QueryEntities.<ProcessContext>of(
                        QbAi.defaultDataset(QbAi.JOB), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                                new QueryPredicate.Eq("kind", QbAi.KIND_SITE_PROFILE),
                                new QueryPredicate.Gte("requestedAt", monthStart(ctx.opTime(), zone)))))
                            .limit(1_001).build(), SITE_JOBS))
                    .compute("Check the site limits", (metadata, ctx) -> checkSiteLimits(ctx, zone))
                    .step("Start the job", StartExternalJob.<ProcessContext>of(SiteProfileJob.TYPE,
                            ctx -> ctx.get(AiProcesses.JOB_INPUT))
                        .reference(ctx -> "site:" + ctx.request().actorId())
                        .outputKey(AiProcesses.EXTERNAL_JOB_ID))
                    .compute("Record the job", (metadata, ctx) -> AiProcesses.record(ctx, zone));
            });
    }

    static java.time.Instant monthStart(java.time.Instant at, ZoneId zone) {
        return at.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant();
    }

    // ---- the request -----------------------------------------------------------------------------------------------

    /** The website's address as the job takes it: {@code https://host/}, or null when it is not a website's. */
    static String siteUrl(String given) {
        if (given == null) {
            return null;
        }
        String text = given.strip();
        if (!text.contains("://")) {
            text = "https://" + text;
        }
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
            || uri.getPort() != -1 || !host.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+") || host.length() > 253
            || !host.substring(host.lastIndexOf('.') + 1).matches("[a-z]{2,}")
            || List.of("local", "internal", "localhost", "lan", "home", "corp")
                .contains(host.substring(host.lastIndexOf('.') + 1))) {
            return null;
        }
        return "https://" + host + "/";
    }

    /** The domains the provider may fetch from: the host and its {@code www.} twin. */
    static List<String> domainsOf(String url) {
        String host = URI.create(url).getHost();
        return host.startsWith("www.") ? List.of(host, host.substring(4)) : List.of(host, "www." + host);
    }

    private static boolean on(ParamValues params, String key) {
        return Boolean.TRUE.equals(params.get(key, Boolean.class));
    }

    private static void prepare(ProcessContext ctx, AiUserKeys userKeys) {
        RequestInput input = ctx.get(AiProcesses.INPUT, RequestInput.class);
        AiProcesses.Common common = AiProcesses.common(ctx, input.aiNoticeVersionNo());
        List<Violation> problems = common.problems();
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        if (!on(params, QbParams.SITE_PROFILE_ENABLED)) {
            problems.add(new Violation(null, AiCodes.UNAVAILABLE, "Drafting from a website is unavailable: switched off",
                Map.of("enabled", false)));
        }
        if (!Boolean.TRUE.equals(input.rightsDeclared())) {
            problems.add(new Violation("rightsDeclared", AiCodes.SITE_RIGHTS_REQUIRED, "Declare that the website is "
                + "yours, or one you may use", Map.of()));
        }
        String url = siteUrl(input.url());
        if (url == null) {
            problems.add(new Violation("url", AiCodes.SITE_URL_INVALID, "Give the website's address: a host name, "
                + "like https://www.example.com/, with no user name, port or address of a machine", Map.of()));
        }
        List<String> languages = new ArrayList<>(List.of(input.language()));
        for (String other : input.otherLanguages() == null ? List.<String>of() : input.otherLanguages()) {
            if (languages.contains(other)) {
                problems.add(new Violation("otherLanguages", AiCodes.SITE_REQUEST_INVALID, "A language is given twice",
                    Map.of("language", other)));
            } else {
                languages.add(other);
            }
        }
        boolean profile = !Boolean.FALSE.equals(input.profile());
        boolean products = !Boolean.FALSE.equals(input.products());
        boolean quiz = !Boolean.FALSE.equals(input.quiz());
        if (!profile && !products && !quiz) {
            problems.add(new Violation(null, AiCodes.SITE_REQUEST_INVALID, "Ask for the page, the products or the "
                + "quiz", Map.of()));
        }
        if (url == null || !problems.isEmpty()) {
            AiProcesses.prepared(ctx, common, QbAi.KIND_SITE_PROFILE, null, Map.of(), 0, 0, List.of());
            return;
        }
        int maxPages = QbParams.limit(params, QbParams.SITE_PROFILE_MAX_PAGES);
        SiteProfileJob.Input job = new SiteProfileJob.Input(common.settings().model(), common.settings().effort(), url,
            domainsOf(url), input.language(), languages, profile, products, quiz, maxPages,
            QbParams.limit(params, QbParams.PRODUCTS_MAX), QbParams.limit(params, QbParams.SITE_PROFILE_MATERIALS_MAX),
            QbParams.limit(params, QbParams.SITE_PROFILE_QUESTIONS), QbParams.limit(params,
                QbParams.SITE_PROFILE_OPTIONS), userKeys.of(ctx.request().actorId()));
        int size = AiProcesses.bytes(JSON.writeValueAsString(job));
        if (size > SiteProfileJob.MAX_JOB_INPUT_BYTES) {
            problems.add(new Violation(null, AiCodes.INPUT_TOO_LARGE, "The request is " + size + " bytes, more than "
                + SiteProfileJob.MAX_JOB_INPUT_BYTES, Map.of("size", size, "max", SiteProfileJob.MAX_JOB_INPUT_BYTES)));
        }
        // An attempt may read every page it is allowed (the provider counts the pages' tokens as input) and writes
        // all the output it asks for; a job has its attempts.
        long reservedInput = Math.max(ClaudeWebResearch.RESERVED_INPUT_TOKENS,
            (long) maxPages * ClaudeWebResearch.MAX_CONTENT_TOKENS + FRAMING_TOKENS);
        long estimate = (long) SiteProfileJob.ATTEMPTS * (reservedInput + SiteProfileJob.MAX_OUTPUT_TOKENS);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("url", url);
        stored.put("language", input.language());
        stored.put("languages", languages);
        stored.put("profile", profile);
        stored.put("products", products);
        stored.put("quiz", quiz);
        stored.put("maxPages", maxPages);
        stored.put("reservedInputTokens", reservedInput);
        stored.put("maxOutputTokens", SiteProfileJob.MAX_OUTPUT_TOKENS);
        // The declaration, as it is recorded with the request (the sponsor's own website, or one they may use).
        stored.put("rightsDeclaredAt", ctx.opTime().toString());
        AiProcesses.prepared(ctx, common, QbAi.KIND_SITE_PROFILE, job, stored, size, estimate, List.of());
    }

    private static void checkSiteLimits(ProcessContext ctx, ZoneId zone) {
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        List<EntityInstance> month = AiProcesses.rows(ctx, SITE_JOBS);
        List<EntityInstance> open = AiProcesses.rows(ctx, AiProcesses.OPEN);
        java.time.Instant today = AiLimits.dayStart(ctx.opTime(), zone);
        long todays = month.stream().filter(job -> !today.isAfter((java.time.Instant) job.get("requestedAt"))).count();
        List<Violation> problems = new ArrayList<>();
        if (open.stream().anyMatch(job -> QbAi.KIND_SITE_PROFILE.equals(job.get("kind")))) {
            problems.add(new Violation(null, AiCodes.SITE_BUSY, "A draft from your website is already being made",
                Map.of()));
        }
        int daily = QbParams.limit(params, QbParams.SITE_PROFILE_DAILY);
        if (todays >= daily) {
            problems.add(new Violation(null, AiCodes.SITE_DAILY_LIMIT, "You have asked " + daily + " times today",
                Map.of("max", daily)));
        }
        int monthly = QbParams.limit(params, QbParams.SITE_PROFILE_MONTHLY);
        if (month.size() >= monthly) {
            problems.add(new Violation(null, AiCodes.SITE_MONTHLY_LIMIT, "You have asked " + monthly
                + " times this month", Map.of("max", monthly)));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    // ---- the outcome -----------------------------------------------------------------------------------------------

    /**
     * A site job's result (called by {@link AiProcesses#complete}): the site's failure, with its code, or the draft kept
     * for the sponsor ({@code READY} until it expires), never written into anything here. A draft without a package or
     * any article is {@code FAILED} ({@link AiCodes#NO_CANDIDATES}).
     */
    static void completeSite(ProcessContext ctx, ExternalJobCompletion completion, EntityInstance job,
        ClaudeApi.Usage usage, String month) {
        SiteProfileJob.Result result = completion.result(SiteProfileJob.Result.class);
        BigDecimal cost = AiCost.of(job.get("model"), result.servedModel(), usage, AiProcesses.settings(ctx).price());
        Map<String, Object> values = AiProcesses.finished(usage, cost, completion.attempts(), completion.finishedTime());
        boolean failed = false;
        if (QbAi.QUEUED.equals(job.get("status"))) {
            values.put("servedModel", result.servedModel());
            String code = result.failure() == null ? null : failureCode(result.failure());
            boolean empty = result.profile().isEmpty() && result.materials().isEmpty();
            if (code != null || empty) {
                failed = true;
                values.put("status", QbAi.FAILED);
                values.put("failureCode", code == null ? AiCodes.NO_CANDIDATES : code);
                values.put("failureMessage", code == null ? "The website gave nothing usable" : "The website could "
                    + "not be read (" + result.failure() + ")");
            } else {
                values.put("status", QbAi.READY);
                values.put("candidates", JSON.writeValueAsString(stored(result)));
                values.put("candidateCount", BigDecimal.valueOf(result.questions().size()));
                values.put("invalidCount", BigDecimal.valueOf(result.invalidQuestions()));
                values.put("expiresAt", completion.finishedTime().plus(Duration.ofDays(
                    AiProcesses.settings(ctx).expireDays())));
            }
        }
        ctx.changes().update(QbAi.JOB, job.id(), job.version(), values);
        AiProcesses.addUsage(ctx, completion.requestedBy(), month, usage, cost, failed);
        ctx.put(AiProcesses.OUTPUT, new AiProcesses.JobOutput(String.valueOf(job.id()),
            (String) values.getOrDefault("status", job.get("status"))));
    }

    private static String failureCode(String failure) {
        return switch (failure) {
            case "UNREACHABLE" -> AiCodes.SITE_UNREACHABLE;
            case "ROBOTS" -> AiCodes.SITE_ROBOTS;
            case "NO_TEXT" -> AiCodes.SITE_NO_TEXT;
            default -> AiCodes.BAD_RESPONSE;
        };
    }

    /** What the sponsor's confirmation reads: the draft, without the model's name. */
    private static Map<String, Object> stored(SiteProfileJob.Result result) {
        Map<String, Object> kept = new LinkedHashMap<>();
        kept.put("profile", result.profile());
        kept.put("sources", result.sources());
        kept.put("notFound", result.notFound());
        kept.put("dropped", result.dropped());
        kept.put("translated", result.translated());
        kept.put("materials", result.materials());
        kept.put("questions", result.questions());
        return kept;
    }

    private SiteProfileProcesses() {}
}
