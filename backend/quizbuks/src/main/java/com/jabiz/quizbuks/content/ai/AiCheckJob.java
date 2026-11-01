package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobType;
import com.jabiz.quizbuks.setup.QbParams;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The asynchronous external job {@value #TYPE} (Q73d-2, docs/quizbuks/plans/Q73-content-languages.md §5.3): the AI
 * compares each of a publication's translated texts in one language with its base text and says whether they mean the
 * same, for the reviewer only (D-Q73-4: the reviewer decides; this is a help, never a condition). Requested by
 * {@code QB_AI_TRANSLATION_CHECK_REQUEST}, answered by {@code QB_AI_JOB_COMPLETED} / {@code _FAILED} as the other AI
 * jobs. The pairs are texts only: no correct answers, points, images, files or keys.
 */
public final class AiCheckJob {

    public static final String TYPE = "qb.ai.translation-check";

    /** The source and translated characters one call compares at most. */
    public static final int BATCH_CHARS = 12_000;
    /** The output one call asks for: a verdict and a short note per pair, with room for its thinking. */
    public static final int BATCH_MAX_TOKENS = 12_000;
    public static final String EFFORT = "low";
    public static final int MAX_JOB_INPUT_BYTES = 1024 * 1024;
    /** The longest note kept for a pair. */
    public static final int NOTE_LENGTH = 500;

    /** One text and its translation: the number in the job, the element (never sent), and the two texts. */
    public record Pair(int no, String key, String source, String translation) {}

    public record Input(String model, String from, String to, List<Pair> pairs, String userKey,
        Long reservedInputTokens) {
        public Input {
            pairs = pairs == null ? List.of() : List.copyOf(pairs);
        }
    }

    /** The AI's verdict on one pair: whether it means the same, and what differs when it does not. */
    public record Finding(int no, String key, boolean consistent, String note) {}

    public record Result(String servedModel, boolean fellBack, List<Finding> findings, int invalid) {
        public Result {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    /** The pairs in batches of about {@value #BATCH_CHARS} characters, in order. */
    public static List<List<Pair>> batches(List<Pair> pairs) {
        List<List<Pair>> batches = new ArrayList<>();
        List<Pair> batch = new ArrayList<>();
        int chars = 0;
        for (Pair pair : pairs) {
            int length = pair.source().length() + pair.translation().length();
            if (!batch.isEmpty() && chars + length > BATCH_CHARS) {
                batches.add(List.copyOf(batch));
                batch.clear();
                chars = 0;
            }
            batch.add(pair);
            chars += length;
        }
        if (!batch.isEmpty()) {
            batches.add(List.copyOf(batch));
        }
        return batches;
    }

    /** As {@link AiTranslateJob#type}: thirty minutes an attempt, three attempts, one open per publication. */
    public static ExternalJobType<Input, Result> type(int maxConcurrency) {
        return ExternalJobType.define(TYPE, Input.class, Result.class, t -> t
            .handler(AiCheckHandler.class).services(ClaudeService.SERVICE)
            .timeout(Duration.ofMinutes(30)).retry(AiQuestionsJob.ATTEMPTS, Duration.ofSeconds(10),
                Duration.ofMinutes(2))
            .maxConcurrency(maxConcurrency).maxOpenPerRequester(AiQuestionsJob.MAX_OPEN).oneOpenPerReference()
            .maxInputSize(MAX_JOB_INPUT_BYTES)
            .onCompleted(AiProcesses.COMPLETED).onFailed(AiProcesses.FAILED)
            .onCancelled(AiProcesses.CANCELLED_JOB).enabledBy(QbParams.AI_ENABLED));
    }

    private AiCheckJob() {}
}
