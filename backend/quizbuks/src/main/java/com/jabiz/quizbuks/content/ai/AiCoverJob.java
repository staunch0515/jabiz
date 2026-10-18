package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobType;
import com.jabiz.quizbuks.content.QbContent;

import java.time.Duration;
import java.util.List;

/**
 * The asynchronous external job {@value #TYPE} (docs/quizbuks/plans/Q8-ai.md, Q8b, M-25; platform decisions D38, D50):
 * {@value CoverDesigns#COVERS} candidate covers of a quiz from its title and introduction. Claude designs them from
 * the fixed catalogue ({@link CoverDesigns}), the handler draws each ({@link CoverRenderer}) and returns the pictures
 * as the job's files; the platform stores them as uploads of the sponsor under {@value QbContent#IMAGE_POLICY} (type
 * recognised from the content, re-encoded, metadata dropped) and hands their ids to {@code QB_AI_JOB_COMPLETED}. The
 * sponsor picks one ({@code QB_AI_COVER_ACCEPT}); the others, referred to by nothing once the job is closed, go with
 * the platform's file sweep.
 *
 * <p>The input holds only the quiz's title and introduction (A-11): no taker's data, no e-mail address, name, company or
 * user id; the provider's {@code metadata.user_id} is the sponsor's HMAC key.
 */
public final class AiCoverJob {

    public static final String TYPE = "qb.ai.cover";
    /** The output tokens asked for: the designs are short, the rest is thinking. */
    public static final int MAX_TOKENS = 8_000;
    /** The largest picture: a drawn 1200 × 630 PNG is far smaller. */
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    /** The key of cover {@code no} (1..3) among the job's files. */
    public static String fileKey(int no) {
        return "cover-" + no;
    }

    /**
     * @param model    one of {@link ClaudeService#MODELS}
     * @param effort   one of {@link ClaudeService#EFFORTS}
     * @param language {@code en}, {@code zh} or {@code ja}: the covers' text
     * @param userKey  the provider's opaque user id: HMAC of the sponsor's id under a key of the server's
     */
    public record Input(String model, String effort, String language, String title, String intro, String userKey) {}

    /**
     * @param servedModel the model that answered (another one when the provider fell back after a refusal)
     * @param designs     the designs drawn, in order; design {@code n} is the job's file {@link #fileKey}(n)
     * @param invalid     how many designs were dropped
     */
    public record Result(String servedModel, boolean fellBack, List<CoverDesigns.Design> designs, int invalid) {
        public Result {
            designs = designs == null ? List.of() : List.copyOf(designs);
        }
    }

    /**
     * The job type: as {@link AiQuestionsJob#type} (five minutes an attempt, three attempts, the concurrency shared by
     * configuration, {@value AiQuestionsJob#MAX_OPEN} unfinished per sponsor, one per quiz), producing at most
     * {@value CoverDesigns#COVERS} files of at most {@value #MAX_FILE_BYTES} bytes each.
     */
    public static ExternalJobType<Input, Result> type(int maxConcurrency) {
        return ExternalJobType.define(TYPE, Input.class, Result.class, t -> t
            .handler(AiCoverHandler.class).services(ClaudeService.SERVICE)
            .timeout(Duration.ofMinutes(5)).retry(AiQuestionsJob.ATTEMPTS, Duration.ofSeconds(10), Duration.ofMinutes(2))
            .maxConcurrency(maxConcurrency).maxOpenPerRequester(AiQuestionsJob.MAX_OPEN).oneOpenPerReference()
            .outputFiles(QbContent.IMAGE_POLICY, CoverDesigns.COVERS, MAX_FILE_BYTES)
            .onCompleted(AiProcesses.COMPLETED).onFailed(AiProcesses.FAILED));
    }

    private AiCoverJob() {}
}
