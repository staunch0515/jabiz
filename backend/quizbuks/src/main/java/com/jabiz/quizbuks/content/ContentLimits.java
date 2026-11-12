package com.jabiz.quizbuks.content;

import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;

import java.util.List;

/**
 * Every limit of quiz content in one place (docs/quizbuks/plans/Q3-content.md, D-Q3-5). The requirements give none
 * of them; they are the plan's values (question Q3-3), used by the entities, the process inputs and the checks alike.
 */
public final class ContentLimits {

    /**
     * F-S6: the technical ceiling of questions in one quiz (input sizes, dataset write limits, column widths). The
     * business limit is the parameter {@code qb.content.max-questions} (1000 by default, {@code
     * QuizBankLimits.maxQuestions}), never above this.
     */
    public static final int MAX_QUESTIONS_TECH = 2_000;
    /**
     * F-F11a: the technical ceiling of the bytes one import request carries. The business value is the parameter
     * {@code qb.content.import.batch-bytes} (3 MiB by default, {@code QuizBankLimits.importBatchBytes}), never above
     * this; it stays below {@code spring.http.codecs.max-in-memory-size} (4 MB) with room for the envelope and the
     * field names, which the framework refuses a request above before any process sees it.
     */
    public static final int IMPORT_BATCH_BYTES_TECH = 3_500_000;
    /** F-F11a: the smallest value {@code qb.content.import.batch-bytes} counts as (below it a batch could hold no question). */
    public static final int IMPORT_BATCH_BYTES_MIN = 65_536;
    /**
     * F-F11a: the most parts one call of {@code QB_QUIZ_CLEAR_CONTENT} deletes. The platform deletes a row of a
     * temporal entity with three statements (about 2 ms), so 2000 rows are a few seconds, far below what a proxy
     * waits; it is also the write limit of the question dataset ({@link #MAX_QUESTIONS_TECH}).
     */
    public static final int CLEAR_ROWS_TECH = 2_000;
    /** Materials in one quiz. */
    public static final int MAX_MATERIALS = 10;
    /** Options of one question, in a draft; a version needs at least {@link #MIN_OPTIONS}. */
    public static final int MAX_OPTIONS = 6;
    public static final int MIN_OPTIONS = 2;
    /** Images of one image-group material. */
    public static final int MAX_IMAGES = 20;

    public static final int TITLE_LENGTH = 100;
    public static final int INTRO_LENGTH = 5_000;
    public static final int STEM_LENGTH = 1_000;
    public static final int OPTION_LENGTH = 300;
    public static final int MATERIAL_TITLE_LENGTH = 100;
    public static final int MATERIAL_DESCRIPTION_LENGTH = 500;
    public static final int MATERIAL_BODY_LENGTH = 20_000;
    public static final int URL_LENGTH = 2_000;
    public static final int CAPTION_LENGTH = 200;

    /** Time limit of a quiz in seconds, when it has one. */
    public static final int MIN_TIME_LIMIT_SEC = 30;
    public static final int MAX_TIME_LIMIT_SEC = 7_200;

    /** Q75: the duration a sponsor gives the video to watch, in seconds (D-Q75-5). */
    public static final int MIN_VIDEO_SECONDS = 1;
    public static final int MAX_VIDEO_SECONDS = 7_200;

    public static final int MIN_POINTS = 1;
    public static final int MAX_POINTS = 1_000;

    /** Largest place ({@code seq}, column {@code numeric(4, 0)}) of a question, material, option or image. */
    public static final int MAX_SEQ = 9_999;

    /**
     * The most files one version can refer to: the cover, each question's and option's image, the materials', and
     * (Q73a) the same again in each of the quiz's other languages.
     */
    public static final int MAX_FILES_PER_VERSION = 1 + ContentLanguages.MAX_LANGUAGES
        * (MAX_QUESTIONS_TECH * (1 + MAX_OPTIONS) + MAX_MATERIALS * MAX_IMAGES);

    /**
     * Version files released per commit when a quiz is removed (a version registers all of its files, at most
     * {@link #MAX_FILES_PER_VERSION}, in its one commit).
     */
    public static final int RELEASE_CHUNK = 3_000;

    /**
     * Largest canonical snapshot text of a version, in UTF-8 bytes: a bank of 1000 questions with six options is some
     * 1-2 MB in three languages (F-S6); a version over this cannot be saved (the daily reconciliation reports versions
     * near it).
     */
    public static final int MAX_SNAPSHOT_BYTES = 6 * 1024 * 1024;

    // The content files' policies (ContentConfig) and the import's checks of a quiz file (Q16) use these.
    public static final List<MediaTypes> IMAGE_TYPES = List.of(MediaTypes.JPEG, MediaTypes.PNG);
    public static final long IMAGE_MAX_BYTES = 10 * FilePolicy.MB;
    public static final List<MediaTypes> PDF_TYPES = List.of(MediaTypes.PDF);
    public static final long PDF_MAX_BYTES = 20 * FilePolicy.MB;
    public static final List<MediaTypes> AUDIO_TYPES = List.of(MediaTypes.MP3, MediaTypes.M4A, MediaTypes.OGG);
    public static final long AUDIO_MAX_BYTES = 50 * FilePolicy.MB;

    private ContentLimits() {}
}
