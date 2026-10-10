package com.jabiz.quizbuks.content;

/**
 * Every limit of quiz content in one place (docs/quizbuks/plans/Q3-content.md, D-Q3-5). The requirements give none
 * of them; they are the plan's values (question Q3-3), used by the entities, the process inputs and the checks alike.
 */
public final class ContentLimits {

    /** Questions in one quiz. */
    public static final int MAX_QUESTIONS = 100;
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

    public static final int MIN_POINTS = 1;
    public static final int MAX_POINTS = 1_000;

    /** Largest place ({@code seq}, column {@code numeric(4, 0)}) of a question, material, option or image. */
    public static final int MAX_SEQ = 9_999;

    /** The most files one version can refer to: the cover, each question's and option's image, the materials'. */
    public static final int MAX_FILES_PER_VERSION = 1 + MAX_QUESTIONS * (1 + MAX_OPTIONS)
        + MAX_MATERIALS * MAX_IMAGES;

    /**
     * Version files released per commit when a quiz is removed, and the write limit of their dataset: a version
     * registers at most {@link #MAX_FILES_PER_VERSION} files at once, and a removal any number, chunk by chunk.
     */
    public static final int RELEASE_CHUNK = 1_000;

    /** Largest canonical snapshot text of a version, in UTF-8 bytes; the limits above keep it near 1 MB. */
    public static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;

    private ContentLimits() {}
}
