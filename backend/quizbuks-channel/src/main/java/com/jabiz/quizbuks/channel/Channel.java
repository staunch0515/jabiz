package com.jabiz.quizbuks.channel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The engine's channel contract (docs/quizbuks/shopify/S2-storefront.md section 1, S2-1 – S2-4): a merchant system of
 * its own (QuizBuks for Shopify) publishes its quiz on the engine and has its takers answer through these
 * sub-processes, naming the publisher and the taker itself. The engine implements them ({@code subProcessOnly()});
 * the channel's module calls them with these records ({@code CallProcess.of(PUBLISH, 1, …)}).
 */
public final class Channel {

    private Channel() {}

    /** Publishes a channel's quiz, live at once ({@link PublishInput} → {@link PublishOutput}). */
    public static final String PUBLISH = "QB_CHANNEL_PUBLISH";
    /** Pauses, resumes or ends a channel's publication ({@link MoveInput} → {@link MoveOutput}). */
    public static final String MOVE = "QB_CHANNEL_PUBLICATION_MOVE";
    /** Starts or continues a channel's taker's attempt ({@link StartInput} → {@link StartOutput}). */
    public static final String START = "QB_CHANNEL_ATTEMPT_START";
    /** Submits it and scores it ({@link SubmitInput} → {@link SubmitOutput}); the channel rewards a pass. */
    public static final String SUBMIT = "QB_CHANNEL_ATTEMPT_SUBMIT";

    /** The kind of a Shopify shop's publications ({@code PublisherKind.SHOPIFY_SHOP} of the engine). */
    public static final String SHOPIFY_SHOP = "SHOPIFY_SHOP";

    /** A channel's taker key: what the channel makes it of, never a user's id. */
    public static final String TAKER_PATTERN = "[A-Za-z0-9:_-]{1,128}";

    /** The most questions of a channel's quiz: a storefront's quiz is short. */
    public static final int MAX_QUESTIONS = 20;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 6;
    public static final int NAME_LENGTH = 100;
    public static final int STEM_LENGTH = 1_000;
    public static final int OPTION_LENGTH = 300;
    public static final int URL_LENGTH = 2_000;
    public static final int MIN_TIME_LIMIT_SEC = 30;
    public static final int MAX_TIME_LIMIT_SEC = 7_200;

    /** A channel's publisher: any kind but a sponsor's, and its key in its own system. */
    public record Publisher(@NotBlank @Size(max = 20) String kind, @NotBlank @Size(max = 128) String id) {}

    public record OptionInput(@NotBlank @Size(max = OPTION_LENGTH) String text, boolean correct) {}

    public record QuestionInput(@NotBlank @Size(max = STEM_LENGTH) String stem,
        @NotNull @Size(min = MIN_OPTIONS, max = MAX_OPTIONS) List<@Valid @NotNull OptionInput> options) {}

    /**
     * @param name      the publication's name, as its publisher lists it (also the quiz's title)
     * @param video     the YouTube or Vimeo link to watch before answering; none for a quiz alone
     * @param passScore how many questions must be answered right (each question is worth a point)
     */
    public record PublishInput(@NotNull @Valid Publisher publisher, @NotBlank @Size(max = NAME_LENGTH) String name,
        @Size(max = URL_LENGTH) String video,
        @NotNull @Min(MIN_TIME_LIMIT_SEC) @Max(MAX_TIME_LIMIT_SEC) Integer timeLimitSec,
        @NotNull @Min(1) Integer passScore,
        @NotNull @Size(min = 1, max = MAX_QUESTIONS) List<@Valid @NotNull QuestionInput> questions) {}

    /** @param video the player the storefront shows (null without a video) */
    public record PublishOutput(UUID publicationId, UUID versionId, String status, int fullScore, int questionCount,
        String video) {}

    /** @param to {@code PAUSED}, {@code LIVE} (from paused) or {@code ENDED} */
    public record MoveInput(@NotNull @Valid Publisher publisher, @NotNull UUID publicationId,
        @NotNull @Pattern(regexp = "PAUSED|LIVE|ENDED") String to) {}

    public record MoveOutput(UUID publicationId, String status) {}

    public record StartInput(@NotNull @Valid Publisher publisher, @NotNull UUID publicationId,
        @NotBlank @Pattern(regexp = TAKER_PATTERN) String takerId) {}

    /** An option as the taker sees it: its number in this attempt's order, its text. */
    public record Option(int no, String text) {}

    /** A question as the taker sees it, its options in this attempt's order; never which are correct. */
    public record Question(int no, String stem, int points, List<Option> options) {}

    /** The answer of one question: its number and the options chosen (their numbers in the attempt's order). */
    public record Answer(@NotNull Integer questionNo, @Size(max = MAX_OPTIONS) List<@NotNull Integer> selected) {}

    /**
     * An attempt as its taker answers it.
     *
     * @param deadline   when it must be submitted, by the server's clock ({@code serverTime} is that clock now)
     * @param resumed    whether the attempt was already in progress (continuing it)
     */
    public record StartOutput(String attemptId, UUID publicationId, Instant startedAt, Instant deadline,
        Instant serverTime, int timeLimitSec, int currentIndex, boolean resumed, List<Question> questions,
        List<Answer> answers) {}

    public record SubmitInput(@NotNull @Valid Publisher publisher, @NotNull UUID publicationId,
        @NotBlank @Pattern(regexp = TAKER_PATTERN) String takerId, @NotBlank @Size(max = 36) String attemptId,
        Integer currentIndex, @Size(max = MAX_QUESTIONS) List<@Valid @NotNull Answer> answers) {}

    /** The score; whether it passed (then the channel rewards it). */
    public record SubmitOutput(String attemptId, String status, int score, int fullScore, int passScore,
        boolean passed) {}
}
