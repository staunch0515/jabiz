package com.jabiz.quizbuks.exposure;

import java.util.List;

/** The error codes of the exposures (F-S13); each has its three-language message. */
public final class ExposureCodes {

    /** The purchased exposures of an ended publication are not set any more. */
    public static final String PUBLICATION_ENDED = "QB_EXPOSURE_PUBLICATION_ENDED";

    /** A call with more reports than {@code qb.exposure.report.max-per-call}. */
    public static final String BATCH_TOO_LARGE = "QB_EXPOSURE_BATCH_TOO_LARGE";

    public static final List<String> ALL = List.of(PUBLICATION_ENDED, BATCH_TOO_LARGE);

    private ExposureCodes() {}
}
