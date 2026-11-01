package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobType;
import com.jabiz.quizbuks.setup.QbParams;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The asynchronous external job {@value #TYPE} (Q73d-1, docs/quizbuks/plans/Q73-content-languages.md §5): candidate
 * translations of a quiz's text into one of its other languages, registered by {@code QB_AI_TRANSLATE_REQUEST},
 * answered by {@code QB_AI_JOB_COMPLETED} or {@code QB_AI_JOB_FAILED} as the other AI jobs are.
 *
 * <p>The input holds the texts only (D-Q73-9): the title, the introduction, the questions' and the options' texts,
 * each a numbered segment. Which options are correct, the points, the images and the files are never in it. The
 * segments are sent in batches of about {@value #BATCH_CHARS} characters, one call each, all of them one attempt.
 */
public final class AiTranslateJob {

    public static final String TYPE = "qb.ai.translate";

    /** The source characters one call translates at most (a segment longer than this is a call of its own). */
    public static final int BATCH_CHARS = 8_000;
    /** The output tokens one call asks for: its translations at low effort, with room for its thinking. */
    public static final int BATCH_MAX_TOKENS = 16_000;
    /** Translation needs no long thinking: every call is made at this effort, whatever the questions' setting. */
    public static final String EFFORT = "low";
    /** The largest input the platform stores: every text of the largest quiz, in the widest script. */
    public static final int MAX_JOB_INPUT_BYTES = 1024 * 1024;

    /**
     * One text to translate: its number in the job (1..n), the element it is ({@code ContentLanguages} key) and the
     * hash of the base text as requested ({@code ContentLanguages.sourceHash}), neither ever sent to the provider, the
     * text and whether it is Markdown (the introduction and the questions' texts are).
     */
    public record Segment(int no, String key, String sourceHash, String text, boolean markdown) {}

    /**
     * @param model    one of {@link ClaudeService#MODELS}
     * @param from     the quiz's base language
     * @param to       the language to translate into, one of the quiz's other languages
     * @param title    the quiz's title, as context for every batch
     * @param userKey  the provider's opaque user id: HMAC of the sponsor's id under a key of the server's
     * @param reservedInputTokens what the request reserved for one call's input at most
     */
    public record Input(String model, String from, String to, String title, List<Segment> segments, String userKey,
        Long reservedInputTokens) {
        public Input {
            segments = segments == null ? List.of() : List.copyOf(segments);
        }
    }

    /** One segment's candidate translation, with its element and base hash as the input gave them. */
    public record Translated(int no, String key, String sourceHash, String text) {}

    /**
     * @param servedModel the model that answered (another one when the provider fell back after a refusal)
     * @param translations the candidates, by segment number
     * @param invalid how many segments came back without a usable translation
     */
    public record Result(String servedModel, boolean fellBack, List<Translated> translations, int invalid) {
        public Result {
            translations = translations == null ? List.of() : List.copyOf(translations);
        }
    }

    /** The segments in batches of about {@value #BATCH_CHARS} characters, in order. */
    public static List<List<Segment>> batches(List<Segment> segments) {
        List<List<Segment>> batches = new ArrayList<>();
        List<Segment> batch = new ArrayList<>();
        int chars = 0;
        for (Segment segment : segments) {
            int length = segment.text().length();
            if (!batch.isEmpty() && chars + length > BATCH_CHARS) {
                batches.add(List.copyOf(batch));
                batch.clear();
                chars = 0;
            }
            batch.add(segment);
            chars += length;
        }
        if (!batch.isEmpty()) {
            batches.add(List.copyOf(batch));
        }
        return batches;
    }

    /**
     * The job type: up to thirty minutes an attempt (a large quiz is many calls of up to two minutes each), three
     * attempts as the other AI jobs, the concurrency shared by configuration, {@value AiQuestionsJob#MAX_OPEN}
     * unfinished per sponsor, one per quiz, and held while {@code qb.ai.enabled} is not {@code true}.
     */
    public static ExternalJobType<Input, Result> type(int maxConcurrency) {
        return ExternalJobType.define(TYPE, Input.class, Result.class, t -> t
            .handler(AiTranslateHandler.class).services(ClaudeService.SERVICE)
            .timeout(Duration.ofMinutes(30)).retry(AiQuestionsJob.ATTEMPTS, Duration.ofSeconds(10),
                Duration.ofMinutes(2))
            .maxConcurrency(maxConcurrency).maxOpenPerRequester(AiQuestionsJob.MAX_OPEN).oneOpenPerReference()
            .maxInputSize(MAX_JOB_INPUT_BYTES)
            .onCompleted(AiProcesses.COMPLETED).onFailed(AiProcesses.FAILED)
            .onCancelled(AiProcesses.CANCELLED_JOB).enabledBy(QbParams.AI_ENABLED));
    }

    private AiTranslateJob() {}
}
