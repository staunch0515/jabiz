package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.identity.SponsorApprovals;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.setup.ReviewCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A new installation (a class of its own): onboarding needs the controlled parameter {@code qb.review.sponsor.auto}
 * and the rule {@code qb.sponsor.review}, both proposed by {@code QB_SETUP}. Until both are in effect an application
 * is refused, never let through unreviewed; disabling the rule refuses again (D-Q2-3).
 */
class SponsorReviewSettingsIT extends AccountItSupport {

    @Override
    boolean installs() {
        return false;
    }

    @AfterEach
    void onlyInserted() {
        assertAccountsOnlyInserted();
    }

    private Map<String, Object> application(String company) {
        return body("displayName", "Owner", "company", company, "acceptTerms", true);
    }

    @Test
    void onboardingIsRefusedUntilTheReviewIsConfigured() {
        Map<String, Object> installed = setup();
        @SuppressWarnings("unchecked")
        List<Object> proposals = (List<Object>) installed.get("proposals");
        // The separation of duties (Q10b) comes last and is left unpublished: the rules are counted before it.
        int count = proposals.size() - com.jabiz.quizbuks.setup.QbSodRules.ALL.size();
        String sara = userWithRole(unique("sara"), QbRoles.SPONSOR);
        String token = tokenOf(sara, QbRoles.SPONSOR);

        // Nothing published: the switch has no value.
        assertThat(codes(refused("QB_SPONSOR_APPLY", token, application("Acme"), 422)))
            .containsExactly("PARAM_NOT_FOUND");
        // The parameters published, not the rule: no review would be asked for, which is refused.
        publishProposals(Map.of("proposals", proposals.subList(0, count - 3)));
        assertThat(codes(refused("QB_SPONSOR_APPLY", token, application("Acme"), 422)))
            .containsExactly(ReviewCodes.REVIEW_NOT_CONFIGURED);
        assertThat(onboardingOf(sara)).isNull();

        // With the rule, a review is asked for.
        publishProposals(Map.of("proposals", proposals.subList(count - 2, count - 1)));
        assertThat(run("QB_SPONSOR_APPLY", token, application("Acme"))).containsEntry("reviewStatus",
            SponsorStatus.PENDING);

        // The rule disabled (four eyes): refused again, for another applicant.
        String ruleId = String.valueOf(query("SELECT rule_id FROM sys_approval_rule_version WHERE rule_code = ?",
            SponsorApprovals.RULE).getFirst().get("rule_id"));
        Map<String, Object> disable = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity",
            "SysApprovalRule", "targetId", ruleId, "values", Map.of("enabled", false), "reason", "Not now"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", disable.get("changeId")));
        String ben = userWithRole(unique("ben"), QbRoles.SPONSOR);
        assertThat(codes(refused("QB_SPONSOR_APPLY", tokenOf(ben, QbRoles.SPONSOR), application("Ben Co"), 422)))
            .containsExactly(ReviewCodes.REVIEW_NOT_CONFIGURED);
        assertThat(onboardingOf(ben)).isNull();
    }
}
