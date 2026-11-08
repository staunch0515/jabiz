package com.jabiz.quizbuks.sharedvideo;

import java.util.List;

/** The error codes of the shared videos (F-S12); each has a message in every language. */
public final class SharedVideoCodes {

    /** Only a video link of the library is shared ({@code kind}). */
    public static final String NOT_VIDEO = "QB_SHARED_VIDEO_NOT_VIDEO";
    /** The asset's link is not one QuizBuks plays, or it has no duration: fix the asset first. */
    public static final String ASSET_INCOMPLETE = "QB_SHARED_VIDEO_ASSET_INCOMPLETE";
    /** A title is missing in a language ({@code language}). */
    public static final String TITLE_REQUIRED = "QB_SHARED_VIDEO_TITLE_REQUIRED";
    /** A shared video has at least one topic of the word list. */
    public static final String TOPIC_REQUIRED = "QB_SHARED_VIDEO_TOPIC_REQUIRED";
    /** The asset is shared already (in review or approved): withdraw it first to share it again. */
    public static final String ALREADY_SHARED = "QB_SHARED_VIDEO_ALREADY_SHARED";
    /** The shared video is not in a state that allows this ({@code status}). */
    public static final String STATE = "QB_SHARED_VIDEO_STATE";
    /** A publication picks, or is submitted with, a video not in the catalog (withdrawn, taken down, in review). */
    public static final String UNAVAILABLE = "QB_SHARED_VIDEO_UNAVAILABLE";

    /**
     * A publication in review whose shared video was taken down meanwhile: it went back to its draft instead of going
     * on (its {@code rejectCode}; the sponsor is mailed).
     */
    public static final String TAKEN_DOWN = "QB_SHARED_VIDEO_TAKEN_DOWN";

    public static final List<String> ALL = List.of(NOT_VIDEO, ASSET_INCOMPLETE, TITLE_REQUIRED, TOPIC_REQUIRED,
        ALREADY_SHARED, STATE, UNAVAILABLE, TAKEN_DOWN);

    private SharedVideoCodes() {}
}
