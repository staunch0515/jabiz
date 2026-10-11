package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.identity.SponsorStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SponsorChecksTest {

    private static Map<String, Object> sponsor(String status) {
        return Map.of("reviewStatus", status, "company", "Acme");
    }

    @Test
    void anApprovedSponsorWhoIsNotBannedPasses() {
        assertThat(SponsorChecks.check(sponsor(SponsorStatus.APPROVED), null)).isEmpty();
        assertThat(SponsorChecks.check(sponsor(SponsorStatus.APPROVED), Map.of("banned", false))).isEmpty();
    }

    @Test
    void withoutAnApprovedOnboardingThereIsNoSponsor() {
        assertThat(SponsorChecks.check(null, null)).extracting(Violation::ruleCode)
            .containsExactly(AccountCodes.SPONSOR_NOT_APPROVED);
        assertThat(SponsorChecks.check(sponsor(SponsorStatus.PENDING), null)).extracting(Violation::ruleCode)
            .containsExactly(AccountCodes.SPONSOR_NOT_APPROVED);
        assertThat(SponsorChecks.check(sponsor(SponsorStatus.REJECTED), null)).extracting(Violation::ruleCode)
            .containsExactly(AccountCodes.SPONSOR_NOT_APPROVED);
    }

    @Test
    void aBannedSponsorIsRefused() {
        assertThat(SponsorChecks.check(sponsor(SponsorStatus.APPROVED), Map.of("banned", true)))
            .extracting(Violation::ruleCode).containsExactly(AccountCodes.USER_BANNED);
    }

    @Test
    void bothProblemsAreReportedAtOnce() {
        assertThat(SponsorChecks.check(sponsor(SponsorStatus.PENDING), Map.of("banned", true)))
            .extracting(Violation::ruleCode).containsExactly(AccountCodes.SPONSOR_NOT_APPROVED,
                AccountCodes.USER_BANNED);
    }
}
