package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobType;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QbContent;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The asynchronous external job {@value #TYPE} (platform decision D38; docs/quizbuks/plans/Q8-ai.md, D-Q8-4): one
 * request of candidate questions, registered by {@code QB_AI_QUESTIONS_REQUEST}, run by the platform outside any
 * transaction, answered by {@code QB_AI_JOB_COMPLETED} or {@code QB_AI_JOB_FAILED} ({@code internal()}).
 *
 * <p>The input holds only the sponsor's own quiz content (D-Q8-2, A-11): no taker's data, no e-mail address, name,
 * company or user id: the provider's {@code metadata.user_id} is {@link #userKey}, an HMAC it cannot reverse. The
 * platform stores the input as it is, once ({@code sys_external_job}, append-only, P4): it is the quiz's content, kept
 * anyway in the quiz's versions.
 */
public final class AiQuestionsJob {

    public static final String TYPE = "qb.ai.questions";

    /** Usage metrics (per attempt, failed ones too; the platform adds them up). */
    public static final String INPUT_TOKENS = "inputTokens";
    public static final String OUTPUT_TOKENS = "outputTokens";
    /** The part of them used by the provider's fallback model (A-10), billed at its price. */
    public static final String FALLBACK_INPUT_TOKENS = "fallbackInputTokens";
    public static final String FALLBACK_OUTPUT_TOKENS = "fallbackOutputTokens";
    /** The part of them of calls the provider prices at its long-prompt rate (Q8b review L2), priced per call. */
    public static final String LONG_INPUT_TOKENS = "longPromptInputTokens";
    public static final String LONG_OUTPUT_TOKENS = "longPromptOutputTokens";

    /** The most questions asked at once (A-12). */
    public static final int MAX_COUNT = 20;
    /**
     * The most at effort {@code high}, which thinks longer for each: so that the answer fits the output asked for
     * ({@link ClaudeApi#maxTokens}) and the two-minute request.
     */
    public static final int MAX_COUNT_HIGH_EFFORT = 10;
    /** Attempts of a job: the first and two more after a time-out, 429, 5xx or 529. */
    public static final int ATTEMPTS = 3;
    /** Unfinished jobs per sponsor (A-7). */
    public static final int MAX_OPEN = 2;
    public static final int DEFAULT_CONCURRENCY = 4;
    /**
     * The largest input the platform stores for a job (Q8b, platform decision D50): the user message is at most
     * {@link ClaudeApi#MAX_INPUT_BYTES}; PDFs are not in it, they are the job's files.
     */
    public static final int MAX_JOB_INPUT_BYTES = 256 * 1024;
    /** The largest PDF one job reads, whatever {@code quizbuks.ai.pdf.max-bytes} says (the policy's own is 20 MB). */
    public static final long MAX_PDF_BYTES = 20L * 1024 * 1024;
    /**
     * All PDFs of one request together: sent as base64 (a third larger) in a request the provider takes up to 32 MB.
     */
    public static final long MAX_PDF_TOTAL_BYTES = 20L * 1024 * 1024;

    /**
     * A material as sent: its number in the quiz (1..n), its kind, title and description, and an article's text (only
     * articles have text: links, videos, images and audio go by their title and description, A-5). A PDF goes as the
     * file itself (Q8b): {@code fileId} is the job's input file the handler sends as a document.
     */
    public record Material(int no, String kind, String title, String description, String body, UUID fileId) {
        /** A material without a file (Q8a). */
        public Material(int no, String kind, String title, String description, String body) {
            this(no, kind, title, description, body, null);
        }
    }

    /**
     * @param model         one of {@link ClaudeService#MODELS}
     * @param effort        one of {@link ClaudeService#EFFORTS}
     * @param language      {@code en}, {@code zh} or {@code ja}
     * @param count         questions asked, 1 to {@value #MAX_COUNT}
     * @param difficulty    {@code EASY}, {@code MEDIUM} or {@code HARD}
     * @param existingStems the quiz's questions, so that the candidates do not repeat them; empty when not sent
     * @param userKey       the provider's opaque user id: HMAC of the sponsor's id under a key of the server's
     * @param reservedInputTokens what the request reserved for one attempt's input (the text's estimate and, with
     *                      PDFs, the page cap at {@value ClaudeApi#TOKENS_PER_PDF_PAGE} a page): the provider's count
     *                      of a request with documents may not be above it ({@link AiCodes#PDF_TOO_LONG}); null for
     *                      jobs of before Q8b
     */
    public record Input(String model, String effort, String language, int count, String difficulty, String title,
        String intro, List<Material> materials, List<String> existingStems, String userKey, Long reservedInputTokens) {
        public Input {
            materials = materials == null ? List.of() : List.copyOf(materials);
            existingStems = existingStems == null ? List.of() : List.copyOf(existingStems);
        }

        /** An input without a reservation (Q8a). */
        public Input(String model, String effort, String language, int count, String difficulty, String title,
            String intro, List<Material> materials, List<String> existingStems, String userKey) {
            this(model, effort, language, count, difficulty, title, intro, materials, existingStems, userKey, null);
        }
    }

    public record Option(String text, boolean correct) {}

    /** A candidate question; {@code materials} are the numbers of the materials it rests on. */
    public record Candidate(String stem, List<Option> options, Integer points, List<Integer> materials) {
        public Candidate {
            options = options == null ? List.of() : List.copyOf(options);
            materials = materials == null ? List.of() : List.copyOf(materials);
        }
    }

    /**
     * @param servedModel the model that answered (another one when the provider fell back after a refusal)
     * @param candidates  the candidates that met the content rules ({@link AiCandidates})
     * @param invalid     how many did not
     */
    public record Result(String servedModel, boolean fellBack, List<Candidate> candidates, int invalid) {
        public Result {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /** The most questions a request may ask for at {@code effort}. */
    public static int maxCount(String effort) {
        return "high".equals(effort) ? MAX_COUNT_HIGH_EFFORT : MAX_COUNT;
    }

    /**
     * The job type: five minutes an attempt (the provider's answer within the service's two minutes, with room), three
     * attempts with back-off from 10 seconds to 2 minutes (429, 5xx, time-outs; a refusal or a bad request ends it at
     * once), at most {@code maxConcurrency} running across instances, {@value #MAX_OPEN} unfinished per sponsor and one
     * per quiz (the reference). The input is at most {@value #MAX_JOB_INPUT_BYTES} bytes; the PDF materials chosen are
     * the job's files (at most one per material, each at most {@code maxPdfBytes}, of the policy
     * {@value QbContent#PDF_POLICY}: the platform checks that the sponsor may read each one, decision D50).
     */
    public static ExternalJobType<Input, Result> type(int maxConcurrency, long maxPdfBytes) {
        return ExternalJobType.define(TYPE, Input.class, Result.class, t -> t
            .handler(AiQuestionsHandler.class).services(ClaudeService.SERVICE)
            .timeout(Duration.ofMinutes(5)).retry(ATTEMPTS, Duration.ofSeconds(10), Duration.ofMinutes(2))
            .maxConcurrency(maxConcurrency).maxOpenPerRequester(MAX_OPEN).oneOpenPerReference()
            .maxInputSize(MAX_JOB_INPUT_BYTES)
            .inputFiles(ContentLimits.MAX_MATERIALS, maxPdfBytes, QbContent.PDF_POLICY)
            .onCompleted(AiProcesses.COMPLETED).onFailed(AiProcesses.FAILED));
    }

    private AiQuestionsJob() {}
}
