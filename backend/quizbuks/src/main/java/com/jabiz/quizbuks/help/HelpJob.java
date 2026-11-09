package com.jabiz.quizbuks.help;

import com.jabiz.external.ExternalJobType;
import com.jabiz.quizbuks.content.ai.ClaudeService;
import com.jabiz.quizbuks.setup.QbParams;

import java.time.Duration;
import java.util.List;

/**
 * The asynchronous external job {@value #TYPE} (F-C10): one question to the AI help, answered from the published
 * materials only: the snapshot of the entry's and language's materials is the job's one file (F-C10b; none before the
 * first snapshot is stored), sent as a cached system block. Started by
 * {@code QB_HELP_ASK}, answered by {@code QB_HELP_JOB_COMPLETED} / {@code _FAILED} / {@code _CANCELLED}. The handler
 * never reads the database; the input holds only what the request needs: the model, the entry, the language, the page's
 * name (from the list), the conversation's latest turns, the question and the user's anonymous key.
 * Paused while {@code qb.help.enabled} is not {@code true} (platform decision D52 item 4).
 */
public final class HelpJob {

    public static final String TYPE = "qb.ai.help";

    /** The output one answer may use, thinking included. */
    public static final int MAX_TOKENS = 4_000;
    /** Short answers: thinking kept shallow. */
    public static final String EFFORT = "low";
    /** The attempts and each attempt's time unless configured ({@code quizbuks.help.attempts} / {@code .attempt-timeout}). */
    public static final int ATTEMPTS = 2;
    public static final Duration ATTEMPT_TIMEOUT = Duration.ofMinutes(3);
    /** The configuration's bounds: an operator tunes them within these. */
    public static final int MAX_ATTEMPTS = 5;
    public static final Duration MIN_ATTEMPT_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration MAX_ATTEMPT_TIMEOUT = Duration.ofMinutes(10);
    /** Jobs one person has open at a time (one per conversation besides). */
    public static final int MAX_OPEN = 3;
    public static final int MAX_JOB_INPUT_BYTES = 64 * 1024;
    /** Jobs of this type running at a time across instances. */
    public static final int DEFAULT_CONCURRENCY = 4;

    /** One earlier turn of the conversation: the question and the AI's answer to it. */
    public record Turn(String question, String answer) {}

    /**
     * @param page                the page's name (on the entry's list) or null
     * @param reservedInputTokens what an attempt's input may come to (the estimate the outcome charges an attempt that
     *                            recorded nothing)
     */
    public record Input(String model, String entry, String language, String page, List<Turn> history, String question,
        String userKey, long reservedInputTokens) {

        public Input {
            history = history == null ? List.of() : List.copyOf(history);
        }

        /** The question's size only: it is the user's. */
        @Override
        public String toString() {
            return "Input[model=" + model + ", entry=" + entry + ", language=" + language + ", page=" + page
                + ", history=" + history.size() + " turns, question=" + (question == null ? 0 : question.length())
                + " chars]";
        }
    }

    /**
     * @param quizRequest whether the AI says it was asked to answer a quiz's question (the outcome then keeps a refusal)
     */
    public record Result(String servedModel, boolean fellBack, String answer, List<String> links, boolean quizRequest) {

        public Result {
            links = links == null ? List.of() : List.copyOf(links);
        }

        @Override
        public String toString() {
            return "Result[servedModel=" + servedModel + ", answer=" + (answer == null ? 0 : answer.length())
                + " chars, links=" + links.size() + ", quizRequest=" + quizRequest + "]";
        }
    }

    /**
     * {@code attempts} attempts of at most {@code attemptTimeout} each (deployment configuration, not business
     * parameters: the job type is fixed at startup), within {@link #MAX_ATTEMPTS} and {@link #MIN_ATTEMPT_TIMEOUT} to
     * {@link #MAX_ATTEMPT_TIMEOUT} (outside: a startup error); at most {@value #MAX_OPEN} open per person.
     */
    public static ExternalJobType<Input, Result> type(int maxConcurrency, int attempts, Duration attemptTimeout) {
        if (attempts < 1 || attempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("quizbuks.help.attempts: 1 to " + MAX_ATTEMPTS);
        }
        if (attemptTimeout.compareTo(MIN_ATTEMPT_TIMEOUT) < 0 || attemptTimeout.compareTo(MAX_ATTEMPT_TIMEOUT) > 0) {
            throw new IllegalArgumentException("quizbuks.help.attempt-timeout: " + MIN_ATTEMPT_TIMEOUT + " to "
                + MAX_ATTEMPT_TIMEOUT);
        }
        return ExternalJobType.define(TYPE, Input.class, Result.class, t -> t
            .handler(HelpHandler.class).services(ClaudeService.SERVICE)
            .timeout(attemptTimeout).retry(attempts, Duration.ofSeconds(5), Duration.ofSeconds(30))
            .maxConcurrency(maxConcurrency).maxOpenPerRequester(MAX_OPEN).oneOpenPerReference()
            .maxInputSize(MAX_JOB_INPUT_BYTES)
            // F-C10b: the entry's and language's materials snapshot, the one file a question reads.
            .inputFiles(1, QbHelpMaterials.MAX_FILE_BYTES, QbHelpMaterials.POLICY)
            .onCompleted(HelpOutcomes.COMPLETED).onFailed(HelpOutcomes.FAILED)
            .onCancelled(HelpOutcomes.CANCELLED).enabledBy(QbParams.HELP_ENABLED));
    }

    private HelpJob() {}
}
