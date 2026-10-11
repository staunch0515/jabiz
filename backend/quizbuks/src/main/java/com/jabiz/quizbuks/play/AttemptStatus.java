package com.jabiz.quizbuks.play;

import java.util.List;

/**
 * The states of an attempt (docs/quizbuks/plans/Q5-answering.md, D-Q5-1): in progress until submitted by its taker
 * in time, or finished with its saved answers once its time ran out. Both finished states are scored and rewarded
 * alike.
 */
public final class AttemptStatus {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String EXPIRED = "EXPIRED";

    public static final List<String> ALL = List.of(IN_PROGRESS, SUBMITTED, EXPIRED);

    public static boolean finished(String status) {
        return SUBMITTED.equals(status) || EXPIRED.equals(status);
    }

    private AttemptStatus() {}
}
