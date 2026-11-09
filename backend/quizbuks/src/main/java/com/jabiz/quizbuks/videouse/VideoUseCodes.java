package com.jabiz.quizbuks.videouse;

import java.util.List;

/** The error codes of the ledger of shared videos' uses (F-S14); each has its three-language message. */
public final class VideoUseCodes {

    /** The publication does not use another sponsor's shared video, or never went on: it has no use. */
    public static final String NOT_SHARED = "QB_VIDEO_USE_NOT_SHARED";
    /** The publication has its use already (one each). */
    public static final String EXISTS = "QB_VIDEO_USE_EXISTS";
    /** The act does not start from the use's state (its status in the parameters). */
    public static final String STATE = "QB_VIDEO_USE_STATE";
    /** The use changed since the caller read it. */
    public static final String CHANGED = "QB_VIDEO_USE_CHANGED";
    /** The period's days are missing, out of order or too many. */
    public static final String PERIOD = "QB_VIDEO_USE_PERIOD";
    /** An unknown pricing. */
    public static final String PRICING = "QB_VIDEO_USE_PRICING";
    /** The price is not a whole number in range. */
    public static final String PRICE = "QB_VIDEO_USE_PRICE";

    public static final List<String> ALL = List.of(NOT_SHARED, EXISTS, STATE, CHANGED, PERIOD, PRICING, PRICE);

    private VideoUseCodes() {}
}
