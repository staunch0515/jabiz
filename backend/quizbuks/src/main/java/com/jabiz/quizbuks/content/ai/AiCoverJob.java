package com.jabiz.quizbuks.content.ai;

import com.jabiz.quizbuks.setup.QbParams;

import java.time.Duration;
import java.util.List;

/**
 * The withdrawn job {@value #TYPE} of AI covers (docs/quizbuks/plans/Q98-covers.md, Q98a; it was Q8b and Q96): nobody
 * asks for covers any more, but a deployment may still hold jobs queued before the withdrawal. The type stays
 * registered so that they end: its handler ({@link AiCoverWithdrawnHandler}) fails them at once without calling the
 * provider, and the outcome processes close and charge them as before ({@link AiProcesses}); the jobs' rows and their
 * kind {@code COVER} stay for the history. Remove the type in a later release, once no such job is left.
 */
public final class AiCoverJob {

    public static final String TYPE = "qb.ai.cover";
    public static final String ILLUSTRATION = "ILLUSTRATION";
    public static final String GRAPHIC = "GRAPHIC";
    /** The output tokens a catalogue design asked for (Q8b), for the charge of an attempt that went unrecorded. */
    public static final int MAX_TOKENS = 8_000;
    /** The output tokens an illustration asked for (Q96), likewise. */
    public static final int ILLUSTRATION_MAX_TOKENS = 12_000;

    /** The output tokens a job of {@code style} asked for. */
    public static int maxTokens(String style) {
        return ILLUSTRATION.equals(style) ? ILLUSTRATION_MAX_TOKENS : MAX_TOKENS;
    }

    /** The input a job of before the withdrawal holds (read, never used). */
    public record Input(String model, String effort, String language, String title, String intro, String userKey,
        Long reservedInputTokens, String style, String requirements, Boolean titleOverlay, String titleText) {}

    /** No job of this type has a result any more. */
    public record Result(String servedModel, boolean fellBack, List<Object> designs, int invalid) {}

    /** The type as before (attempts, concurrency, the switch, the outcome processes), with the withdrawn handler. */
    public static com.jabiz.external.ExternalJobType<Input, Result> type(int maxConcurrency) {
        return com.jabiz.external.ExternalJobType.define(TYPE, Input.class, Result.class, t -> t
            .handler(AiCoverWithdrawnHandler.class)
            .timeout(Duration.ofMinutes(5)).retry(AiQuestionsJob.ATTEMPTS, Duration.ofSeconds(10), Duration.ofMinutes(2))
            .maxConcurrency(maxConcurrency).maxOpenPerRequester(AiQuestionsJob.MAX_OPEN).oneOpenPerReference()
            .onCompleted(AiProcesses.COMPLETED).onFailed(AiProcesses.FAILED)
            .onCancelled(AiProcesses.CANCELLED_JOB).enabledBy(QbParams.AI_ENABLED));
    }

    private AiCoverJob() {}
}
