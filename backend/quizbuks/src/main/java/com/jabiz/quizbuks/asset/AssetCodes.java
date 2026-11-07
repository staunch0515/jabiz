package com.jabiz.quizbuks.asset;

import java.util.List;

/** The error codes of the sponsors' asset library (F-C1); each has a message in every language. */
public final class AssetCodes {

    public static final String KIND_INVALID = "QB_ASSET_KIND_INVALID";
    /** An asset keeps the kind it was made with. */
    public static final String KIND_FIXED = "QB_ASSET_KIND_FIXED";
    public static final String NAME_BLANK = "QB_ASSET_NAME_BLANK";
    /** A text is longer than its field allows ({@code field}, {@code max}). */
    public static final String TOO_LONG = "QB_ASSET_TOO_LONG";
    /** A content field another kind uses ({@code kind}, {@code field}). */
    public static final String FIELD_NOT_ALLOWED = "QB_ASSET_FIELD_NOT_ALLOWED";
    /** The kind's content is missing ({@code kind}, {@code field}). */
    public static final String CONTENT_REQUIRED = "QB_ASSET_CONTENT_REQUIRED";
    public static final String URL_FORMAT = "QB_ASSET_URL_FORMAT";
    /** A video link of a site QuizBuks does not play. */
    public static final String VIDEO_SITE = "QB_ASSET_VIDEO_SITE";
    public static final String DURATION_RANGE = "QB_ASSET_DURATION_RANGE";
    public static final String USAGE_INVALID = "QB_ASSET_USAGE_INVALID";
    public static final String PRIZE_METHOD_INVALID = "QB_ASSET_PRIZE_METHOD_INVALID";
    public static final String DIGITAL_DELIVERY_INVALID = "QB_ASSET_DIGITAL_DELIVERY_INVALID";
    public static final String SEND_DAYS_RANGE = "QB_ASSET_SEND_DAYS_RANGE";
    public static final String LANGUAGE_INVALID = "QB_ASSET_LANGUAGE_INVALID";
    public static final String TAG_BLANK = "QB_ASSET_TAG_BLANK";
    public static final String TAG_TWICE = "QB_ASSET_TAG_TWICE";
    public static final String TAG_TOO_LONG = "QB_ASSET_TAG_TOO_LONG";
    public static final String TOO_MANY_TAGS = "QB_ASSET_TOO_MANY_TAGS";
    /** The sponsor has as many assets as {@code qb.assets.max-count} allows. */
    public static final String LIMIT = "QB_ASSET_LIMIT";
    /** The asset changed since the caller read it. */
    public static final String CHANGED = "QB_ASSET_CHANGED";
    /** A file newly put into an asset that the sponsor did not upload. */
    public static final String FILE_NOT_OWN = "QB_ASSET_FILE_NOT_OWN";

    public static final List<String> ALL = List.of(KIND_INVALID, KIND_FIXED, NAME_BLANK, TOO_LONG, FIELD_NOT_ALLOWED,
        CONTENT_REQUIRED, URL_FORMAT, VIDEO_SITE, DURATION_RANGE, USAGE_INVALID, PRIZE_METHOD_INVALID,
        DIGITAL_DELIVERY_INVALID, SEND_DAYS_RANGE, LANGUAGE_INVALID, TAG_BLANK, TAG_TWICE, TAG_TOO_LONG, TOO_MANY_TAGS,
        LIMIT, CHANGED, FILE_NOT_OWN);

    private AssetCodes() {}
}
