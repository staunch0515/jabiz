package com.jabiz.quizbuks.help;

import com.jabiz.external.ExternalJobType;
import com.jabiz.quizbuks.content.ai.ClaudeService;
import com.jabiz.quizbuks.setup.QbParams;

import java.time.Duration;
import java.util.List;

/**
 * The asynchronous external job {@value #TYPE} (F-C10): one question to the AI help, answered from the published
 * materials only (part b gives them as the job's file; until then the prompt has none). Started by
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
    public static final int ATTEMPTS = 2;
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

    /** Three minutes an attempt, two attempts; at most {@value #MAX_OPEN} open per person, one per conversation. */
    public static ExternalJobType<Input, Result> type(int maxConcurrency) {
        return ExternalJobType.define(TYPE, Input.class, Result.class, t -> t
            .handler(HelpHandler.class).services(ClaudeService.SERVICE)
            .timeout(Duration.ofMinutes(3)).retry(ATTEMPTS, Duration.ofSeconds(5), Duration.ofSeconds(30))
            .maxConcurrency(maxConcurrency).maxOpenPerRequester(MAX_OPEN).oneOpenPerReference()
            .maxInputSize(MAX_JOB_INPUT_BYTES)
            .onCompleted(HelpOutcomes.COMPLETED).onFailed(HelpOutcomes.FAILED)
            .onCancelled(HelpOutcomes.CANCELLED).enabledBy(QbParams.HELP_ENABLED));
    }

    private HelpJob() {}
}
