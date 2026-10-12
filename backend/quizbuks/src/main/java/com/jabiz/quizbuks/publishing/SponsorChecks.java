package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.identity.BanRules;
import com.jabiz.quizbuks.identity.SponsorStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Whether the caller may publish as a sponsor (M-31 "there is a sponsor"; docs/quizbuks/plans/Q2-accounts.md, D-Q2-6):
 * their onboarding is approved ({@code QbSponsor.reviewStatus}) and they are not banned ({@code QbUserBan}). A ban
 * takes effect at sign-in and at the next refresh only, so the submission checks it again (D-Q2-4). Every problem is
 * reported at once, with the submission's other problems ({@link PublicationReadiness}).
 */
public final class SponsorChecks {

    /**
     * @param sponsor the caller's current {@code QbSponsor}; null when they never applied
     * @param ban     the caller's current {@code QbUserBan}; null when they were never banned
     */
    public static List<Violation> check(Map<String, Object> sponsor, Map<String, Object> ban) {
        List<Violation> problems = new ArrayList<>();
        if (sponsor == null || !SponsorStatus.APPROVED.equals(sponsor.get("reviewStatus"))) {
            problems.add(new Violation(null, AccountCodes.SPONSOR_NOT_APPROVED, "The sponsor onboarding is not "
                + "approved", Map.of("status", sponsor == null ? "NONE" : String.valueOf(sponsor.get("reviewStatus")))));
        }
        if (BanRules.banned(ban)) {
            problems.add(new Violation(null, AccountCodes.USER_BANNED, "The account is banned", Map.of()));
        }
        return List.copyOf(problems);
    }

    /**
     * Adds the steps that read the caller's onboarding and ban, for {@link #problems} (other modules' processes that
     * act as a sponsor, such as a top-up, Q6).
     */
    public static <I, O> void load(com.jabiz.process.ProcessDefinitionBuilder<I, O, com.jabiz.process.ProcessContext>
        pb) {
        PublicationEditing.loadSponsor(pb);
    }

    /** Why the caller, read by {@link #load}, may not act as a sponsor; none when they may. */
    public static List<Violation> problems(com.jabiz.process.ProcessContext ctx) {
        return PublicationEditing.sponsorProblems(ctx);
    }

    private SponsorChecks() {}
}
