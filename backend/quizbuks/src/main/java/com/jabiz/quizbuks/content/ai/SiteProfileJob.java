package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobType;
import com.jabiz.quizbuks.setup.QbParams;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The asynchronous external job {@value #TYPE} (F-C7, docs/quizbuks/plans/Q104-site-to-profile.md, platform decision
 * D38): Claude reads one website and drafts a sponsor's profile package and the material for a "get to know us" quiz.
 * It only drafts: the result waits for the sponsor in the usual import's confirmation, and nothing is written until
 * they confirm (P1).
 *
 * <p>The input holds the address asked for, the languages and the limits, and nothing of the sponsor (P5): not the
 * name, the company, the e-mail address or the user id; the provider's {@code metadata.user_id} is {@code userKey}, an
 * HMAC it cannot reverse. The handler reads no database. The address is the sponsor's own website, kept with the input
 * as the platform keeps every input (append-only); it is a public address.
 */
public final class SiteProfileJob {

    public static final String TYPE = "qb.ai.site-profile";

    /** Attempts of a job: the first and one more (a read of ten pages is dear, so the retry is not repeated). */
    public static final int ATTEMPTS = 2;
    /** Unfinished site jobs per sponsor (Q104 P6: one at a time). */
    public static final int MAX_OPEN = 1;
    /** Each attempt: reading ten pages and writing the answer takes a few minutes. */
    public static final Duration ATTEMPT_TIMEOUT = Duration.ofMinutes(10);
    /** The largest input the platform stores for a job: an address, the languages and the limits. */
    public static final int MAX_JOB_INPUT_BYTES = 8 * 1024;
    /** The output asked for, thinking included: a package in three languages, five articles and their questions. */
    public static final int MAX_OUTPUT_TOKENS = 32_000;
    /** The most failures the answer may name: why a site could not be read. */
    public static final List<String> FAILURES = List.of("UNREACHABLE", "ROBOTS", "NO_TEXT");

    /**
     * @param model        one of {@link ClaudeService#MODELS} (the AI parameters', never a literal here)
     * @param url          the sponsor's website, {@code https://host/}
     * @param domains      the only domains the provider may fetch from: the host and its {@code www.} twin
     * @param base         the base language of the package ({@code en}, {@code zh} or {@code ja})
     * @param languages    the base first, then the others the sponsor chose (translated, marked machine-translated)
     * @param profile      whether to draft the basics and the page
     * @param products     whether to draft the products
     * @param quiz         whether to draft the material and questions of the "get to know us" quiz
     * @param maxPages     the most pages the provider reads ({@code qb.ai.site-profile.max-pages})
     * @param maxProducts  the most products ({@code qb.products.max})
     * @param materialsMax the most articles ({@code qb.ai.site-profile.materials-max})
     * @param questions    the questions asked ({@code qb.ai.site-profile.questions})
     * @param options      the options of each ({@code qb.ai.site-profile.options})
     * @param userKey      the provider's opaque user id
     */
    public record Input(String model, String effort, String url, List<String> domains, String base,
        List<String> languages, boolean profile, boolean products, boolean quiz, int maxPages, int maxProducts,
        int materialsMax, int questions, int options, String userKey) {
        public Input {
            domains = domains == null ? List.of() : List.copyOf(domains);
            languages = languages == null ? List.of() : List.copyOf(languages);
        }
    }

    /** A field of the answer that was left out, and why. */
    public record Dropped(String path, String reason) {}

    /** One article for the quiz: a title, the page's text tidied, and the page it was taken from. */
    public record Material(String title, String body, String url) {}

    /**
     * What the job gives back. {@code failure} is one of {@link #FAILURES} when the site could not be read (nothing
     * else is then set); otherwise {@code profile} is the profile package as a document (without the contact name and
     * the mail server), {@code sources} the page each field was taken from by its path, {@code notFound} the fields the
     * site did not show, {@code dropped} what failed the package's checks, {@code translated} the languages the
     * provider translated, and {@code materials} and {@code questions} the "get to know us" quiz's articles and
     * candidate questions (which rest on the articles by their numbers, from 1).
     */
    public record Result(String servedModel, boolean fellBack, String failure, Map<String, Object> profile,
        Map<String, String> sources, List<String> notFound, List<Dropped> dropped, List<String> translated,
        List<Material> materials, List<AiQuestionsJob.Candidate> questions, int invalidQuestions) {
        public Result {
            profile = profile == null ? Map.of() : profile;
            sources = sources == null ? Map.of() : Map.copyOf(sources);
            notFound = notFound == null ? List.of() : List.copyOf(notFound);
            dropped = dropped == null ? List.of() : List.copyOf(dropped);
            translated = translated == null ? List.of() : List.copyOf(translated);
            materials = materials == null ? List.of() : List.copyOf(materials);
            questions = questions == null ? List.of() : List.copyOf(questions);
        }

        /** A site that could not be read. */
        public static Result failed(String servedModel, boolean fellBack, String failure) {
            return new Result(servedModel, fellBack, failure, null, null, null, null, null, null, null, 0);
        }
    }

    /**
     * The job type: held while {@code qb.ai.enabled} is not {@code true} (platform decision D52 item 4: the emergency
     * stop of every AI job); at most {@code maxConcurrency} running across instances, one unfinished per sponsor. The
     * request itself needs {@code qb.ai.site-profile.enabled} as well (the switch every deployment keeps off until
     * the AI credit can be charged, docs/quizbuks/plans/impl/F-C7.md): a job already asked for runs to its end.
     */
    public static ExternalJobType<Input, Result> type(int maxConcurrency) {
        return ExternalJobType.define(TYPE, Input.class, Result.class, t -> t
            .handler(SiteProfileHandler.class).services(ClaudeService.SERVICE)
            .timeout(ATTEMPT_TIMEOUT).retry(ATTEMPTS, Duration.ofSeconds(30), Duration.ofMinutes(2))
            .maxConcurrency(maxConcurrency).maxOpenPerRequester(MAX_OPEN).oneOpenPerReference()
            .maxInputSize(MAX_JOB_INPUT_BYTES)
            .onCompleted(AiProcesses.COMPLETED).onFailed(AiProcesses.FAILED)
            .onCancelled(AiProcesses.CANCELLED_JOB).enabledBy(QbParams.AI_ENABLED));
    }

    private SiteProfileJob() {}
}
