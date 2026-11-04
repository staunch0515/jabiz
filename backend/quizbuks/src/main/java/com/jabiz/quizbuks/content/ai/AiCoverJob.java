package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobType;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.setup.QbParams;

import java.time.Duration;
import java.util.List;

/**
 * The asynchronous external job {@value #TYPE} (docs/quizbuks/plans/Q8-ai.md, Q8b, M-25; platform decisions D38, D50):
 * a candidate cover of a quiz from its title, introduction and, since Q96 (docs/quizbuks/plans/Q96-ai-cover-illustration.md),
 * the sponsor's requirements. Claude draws an illustration as SVG ({@link CoverSvg}) or designs it from the fixed
 * catalogue ({@link CoverDesigns}); the handler draws it ({@link CoverRenderer}) and returns the picture
 * as the job's file; the platform stores it as an upload of the sponsor under {@value QbContent#IMAGE_POLICY} (type
 * recognised from the content, re-encoded, metadata dropped) and hands its id to {@code QB_AI_JOB_COMPLETED}. The
 * sponsor uses it ({@code QB_AI_COVER_ACCEPT}) or asks again, which closes it; a picture not used, referred to by
 * nothing once the job is closed, goes with the platform's file sweep.
 *
 * <p>The input holds only the quiz's title and introduction and the sponsor's requirements and cover text (A-11): no
 * taker's data, no e-mail address, name, company or user id; the provider's {@code metadata.user_id} is the sponsor's
 * HMAC key.
 */
public final class AiCoverJob {

    public static final String TYPE = "qb.ai.cover";
    /** Q96: an illustration Claude draws as SVG (the default of a request) or a design of the catalogue (Q8b). */
    public static final String ILLUSTRATION = "ILLUSTRATION";
    public static final String GRAPHIC = "GRAPHIC";
    public static final List<String> STYLES = List.of(ILLUSTRATION, GRAPHIC);
    /** The output tokens asked for a catalogue design: the designs are short, the rest is thinking. */
    public static final int MAX_TOKENS = 8_000;
    /**
     * The output tokens asked for an illustration (D-Q96-6): the whole answer must arrive within the platform's two
     * minutes for one request, so little thinking ({@link #ILLUSTRATION_EFFORT}) and an SVG the prompt keeps short.
     */
    public static final int ILLUSTRATION_MAX_TOKENS = 12_000;
    public static final String ILLUSTRATION_EFFORT = "low";
    /** The longest requirements a sponsor gives, in characters. */
    public static final int MAX_REQUIREMENTS = 500;
    /** The largest picture: a drawn 1200 × 630 PNG is far smaller. */
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    /** The key of cover {@code no} among the job's files. */
    public static String fileKey(int no) {
        return "cover-" + no;
    }

    /** The output tokens a request of {@code style} asks for. */
    public static int maxTokens(String style) {
        return ILLUSTRATION.equals(style) ? ILLUSTRATION_MAX_TOKENS : MAX_TOKENS;
    }

    /**
     * @param model    one of {@link ClaudeService#MODELS}
     * @param effort   one of {@link ClaudeService#EFFORTS}
     * @param language {@code en}, {@code zh} or {@code ja}: the covers' text
     * @param userKey  the provider's opaque user id: HMAC of the sponsor's id under a key of the server's
     * @param reservedInputTokens what the request reserved for one attempt's input (for the warning when the usage
     *                 goes above it); null when not given
     * @param style        one of {@link #STYLES}; null (a job of before Q96) is {@link #GRAPHIC}
     * @param requirements what the sponsor wants the picture to show or look like; null when nothing
     * @param titleOverlay whether an illustration shows a title (null: yes)
     * @param titleText    the text the cover shows, as the sponsor wrote it; null: Claude's wording of the title
     */
    public record Input(String model, String effort, String language, String title, String intro, String userKey,
        Long reservedInputTokens, String style, String requirements, Boolean titleOverlay, String titleText) {

        public Input(String model, String effort, String language, String title, String intro, String userKey,
            Long reservedInputTokens) {
            this(model, effort, language, title, intro, userKey, reservedInputTokens, null, null, null, null);
        }

        public Input(String model, String effort, String language, String title, String intro, String userKey) {
            this(model, effort, language, title, intro, userKey, null);
        }

        /** The style asked for: a job of before Q96 is a catalogue design. */
        public String styleOrDefault() {
            return ILLUSTRATION.equals(style) ? ILLUSTRATION : GRAPHIC;
        }

        /** Whether an illustration shows a title. */
        public boolean overlay() {
            return titleOverlay == null || titleOverlay;
        }

        /** The same input with what the request reserved for one attempt's input. */
        public Input reserved(long tokens) {
            return new Input(model, effort, language, title, intro, userKey, tokens, style, requirements,
                titleOverlay, titleText);
        }
    }

    /** The title on an illustration (Q96): its text (null: none) and where ({@link CoverRenderer#PLACEMENTS}). */
    public record Overlay(String text, String placement) {}

    /**
     * @param servedModel the model that answered (another one when the provider fell back after a refusal)
     * @param designs     the catalogue designs drawn, in order; design {@code n} is the job's file {@link #fileKey}(n)
     * @param invalid     how many designs were dropped
     * @param style       {@link #ILLUSTRATION} or {@link #GRAPHIC}; null (a result of before Q96) is the latter
     * @param overlay     an illustration's title: its picture is the job's file {@link #fileKey}(1)
     */
    public record Result(String servedModel, boolean fellBack, List<CoverDesigns.Design> designs, int invalid,
        String style, Overlay overlay) {
        public Result {
            designs = designs == null ? List.of() : List.copyOf(designs);
        }

        public Result(String servedModel, boolean fellBack, List<CoverDesigns.Design> designs, int invalid) {
            this(servedModel, fellBack, designs, invalid, GRAPHIC, null);
        }

        public boolean illustration() {
            return ILLUSTRATION.equals(style);
        }
    }

    /**
     * The job type: as {@link AiQuestionsJob#type} (five minutes an attempt, three attempts, the concurrency shared by
     * configuration, {@value AiQuestionsJob#MAX_OPEN} unfinished per sponsor, one per quiz), producing at most
     * {@value CoverDesigns#COVERS} files of at most {@value #MAX_FILE_BYTES} bytes each, and held while
     * {@code qb.ai.enabled} is not {@code true} as that type is.
     */
    public static ExternalJobType<Input, Result> type(int maxConcurrency) {
        return ExternalJobType.define(TYPE, Input.class, Result.class, t -> t
            .handler(AiCoverHandler.class).services(ClaudeService.SERVICE)
            .timeout(Duration.ofMinutes(5)).retry(AiQuestionsJob.ATTEMPTS, Duration.ofSeconds(10), Duration.ofMinutes(2))
            .maxConcurrency(maxConcurrency).maxOpenPerRequester(AiQuestionsJob.MAX_OPEN).oneOpenPerReference()
            .outputFiles(QbContent.IMAGE_POLICY, CoverDesigns.COVERS, MAX_FILE_BYTES)
            .onCompleted(AiProcesses.COMPLETED).onFailed(AiProcesses.FAILED)
            .onCancelled(AiProcesses.CANCELLED_JOB).enabledBy(QbParams.AI_ENABLED));
    }

    private AiCoverJob() {}
}
