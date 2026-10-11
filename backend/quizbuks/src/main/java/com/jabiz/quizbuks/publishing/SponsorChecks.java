package com.jabiz.quizbuks.publishing;

import com.jabiz.process.ProcessContext;

/**
 * Whether the caller may publish as a sponsor (M-31 "there is a sponsor"; docs/quizbuks/plans/Q4-publishing.md,
 * question Q4-2): their sponsor onboarding is approved. Onboarding comes with Q2; until then every caller holding
 * {@code qb.publication.write} passes, since administrators create sponsor accounts by hand. Q2 reads
 * {@code QbSponsor.reviewStatus} here and refuses with {@link PublishingCodes#SPONSOR_NOT_APPROVED} (with its tests).
 */
public final class SponsorChecks {

    /** Rejects the submission unless the caller's onboarding is approved; always passes before Q2. */
    public static void requireApproved(ProcessContext ctx) {
        // Q2: ctx.reject(new Violation(null, PublishingCodes.SPONSOR_NOT_APPROVED, …)) unless approved.
    }

    private SponsorChecks() {}
}
