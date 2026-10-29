package com.jabiz.quizbuks.prospect;

import com.jabiz.external.ExternalJobType;
import com.jabiz.quizbuks.content.ai.ClaudeService;
import com.jabiz.quizbuks.setup.QbParams;

import java.time.Duration;
import java.util.List;

/**
 * The two asynchronous external jobs of prospecting (platform decision D38; docs/quizbuks/plans/Q48-operators.md,
 * Q48c), started by the daily item {@code QB_PROSPECT_DAY} (as the system), run outside any transaction, answered by
 * {@code internal()} processes. Their handlers read no database: the input holds what they send, and nothing of a
 * person but the business address the provider itself reads on the business's site.
 * <ul>
 *   <li>{@value #FIND}: one direction's search for businesses ({@link ProspectFindHandler});</li>
 *   <li>{@value #RESEARCH}: one business's site read, scored, and a trial quiz and a mail drafted
 *       ({@link ProspectResearchHandler}).</li>
 * </ul>
 * Both are held while {@code qb.prospect.enabled} is not {@code true} ({@code enabledBy}, D52 item 4): the platform
 * then claims none of them.
 */
public final class ProspectJobs {

    public static final String FIND = "qb.prospect.find";
    public static final String RESEARCH = "qb.prospect.research";

    /** The most searches and fetches one job's request may run. */
    public static final int FIND_SEARCHES = 5;
    public static final int RESEARCH_SEARCHES = 2;
    public static final int RESEARCH_FETCHES = 4;
    /** The questions of a trial quiz, and the options of each. */
    public static final int TRIAL_QUESTIONS = 5;
    public static final int TRIAL_OPTIONS = 4;
    /** Attempts of a job: the first and two more after a time-out, 429, 5xx or 529. */
    public static final int ATTEMPTS = 3;

    /**
     * Input of {@value #FIND}.
     *
     * @param excludedDomains domains the owner knows already, and the blocked ones: not to bring again
     */
    public record FindInput(String owner, String directionId, String brief, String country, String language,
        int perSearch, List<String> excludedDomains, String model, String effort, String userKey) {
        public FindInput {
            excludedDomains = excludedDomains == null ? List.of() : List.copyOf(excludedDomains);
        }
    }

    /** A business a search found: its domain, the page it was found by, and its name as the page gives it. */
    public record Candidate(String domain, String url, String company) {}

    /** @param invalid candidates that were not a usable site (dropped) */
    public record FindResult(String servedModel, List<Candidate> candidates, int invalid) {
        public FindResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /**
     * Input of {@value #RESEARCH}: the business's site, what the owner looks for (the direction's brief), and the score
     * a business needs before a quiz and a mail are drafted for it.
     */
    public record ResearchInput(String owner, String prospectId, String domain, String url, String brief,
        String language, int minScore, String model, String effort, String userKey) {}

    public record Product(String name, String description) {}

    public record TrialOption(String text, boolean correct) {}

    public record TrialQuestion(String stem, List<TrialOption> options) {
        public TrialQuestion {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** The trial quiz: made from the business's own products, a few questions with one right option each. */
    public record Trial(String title, String intro, List<TrialQuestion> questions) {
        public Trial {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    /**
     * What the research found, as checked ({@link ProspectAnswers#research}).
     *
     * @param language         the site's language ({@code ja}, {@code zh}, {@code en}, or another ISO 639-1 code)
     * @param publicEmail      the business address the site publishes; null when it publishes none
     * @param refusesMarketing whether the site says it refuses advertising or promotional mail
     * @param score            0–100: how well the business fits QuizBuks
     * @param trial            null when the business did not get far enough to need one
     */
    public record ResearchResult(String servedModel, String company, String language, String country,
        Boolean sellsOnline, Boolean shopify, String publicEmail, boolean refusesMarketing, int score,
        List<String> reasons, List<Product> products, Trial trial, String mailSubject, String mailBody) {
        public ResearchResult {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            products = products == null ? List.of() : List.copyOf(products);
        }
    }

    /** The job types; {@code maxConcurrency} across instances (the provider's rate limit is the owner's too). */
    public static ExternalJobType<FindInput, FindResult> findType(int maxConcurrency) {
        return ExternalJobType.define(FIND, FindInput.class, FindResult.class, t -> t
            .handler(ProspectFindHandler.class).services(ClaudeService.SERVICE)
            .timeout(Duration.ofMinutes(5)).retry(ATTEMPTS, Duration.ofSeconds(10), Duration.ofMinutes(2))
            .maxConcurrency(maxConcurrency).maxOpenPerRequester(ExternalJobType.MAX_OPEN_PER_REQUESTER)
            .oneOpenPerReference().maxInputSize(64 * 1024)
            .onCompleted(ProspectProcesses.FOUND).onFailed(ProspectProcesses.FIND_FAILED)
            .onCancelled(ProspectProcesses.FIND_CANCELLED).enabledBy(QbParams.PROSPECT_ENABLED));
    }

    public static ExternalJobType<ResearchInput, ResearchResult> researchType(int maxConcurrency) {
        return ExternalJobType.define(RESEARCH, ResearchInput.class, ResearchResult.class, t -> t
            .handler(ProspectResearchHandler.class).services(ClaudeService.SERVICE)
            .timeout(Duration.ofMinutes(5)).retry(ATTEMPTS, Duration.ofSeconds(10), Duration.ofMinutes(2))
            .maxConcurrency(maxConcurrency).maxOpenPerRequester(ExternalJobType.MAX_OPEN_PER_REQUESTER)
            .oneOpenPerReference().maxInputSize(16 * 1024)
            .onCompleted(ProspectProcesses.RESEARCHED).onFailed(ProspectProcesses.RESEARCH_FAILED)
            .onCancelled(ProspectProcesses.RESEARCH_CANCELLED).enabledBy(QbParams.PROSPECT_ENABLED));
    }

    private ProspectJobs() {}
}
