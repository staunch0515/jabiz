package com.jabiz.quizbuks.identity;

import java.util.List;

/**
 * The review states of a sponsor's onboarding (D-Q2-3): pending, approved (final: the profile changes without review
 * afterwards) and rejected (applying again makes it pending). A user who never applied has no {@code QbSponsor}.
 */
public final class SponsorStatus {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    public static final List<String> ALL = List.of(PENDING, APPROVED, REJECTED);

    private SponsorStatus() {}
}
