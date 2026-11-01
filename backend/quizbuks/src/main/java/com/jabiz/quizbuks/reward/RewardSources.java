package com.jabiz.quizbuks.reward;

import java.util.List;

/**
 * What earned a cash reward, a prize to collect or a digital prize (Q70, D-Q70-20): an attempt, as every record before
 * Q70 (whose {@code sourceKind} is empty), or a referral (Q70b), whose id then stands in {@code attemptId}: a
 * referral's id is a UUIDv7 like an attempt's, so the unique {@code attemptId} still holds. Every read that joins a
 * record to {@code QbAttempt} by {@code attemptId} asks {@link #referral} first.
 */
public final class RewardSources {

    public static final String ATTEMPT = "ATTEMPT";
    public static final String REFERRAL = "REFERRAL";
    public static final List<String> ALL = List.of(ATTEMPT, REFERRAL);

    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:reward-source";

    /** Whether a record's source is a referral (empty, as before Q70, is an attempt). */
    public static boolean referral(Object sourceKind) {
        return REFERRAL.equals(sourceKind);
    }

    private RewardSources() {}
}
