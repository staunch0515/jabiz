package com.jabiz.quizbuks.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QbAccountsTest {

    @Test
    void theEntitiesAreDefined() {
        assertThat(QbAccounts.PROFILE_ENTITY.primaryKeyColumn()).isEqualTo("profile_id");
        assertThat(QbAccounts.SPONSOR_ENTITY.primaryKeyColumn()).isEqualTo("onboarding_id");
        assertThat(QbAccounts.BAN_ENTITY.primaryKeyColumn()).isEqualTo("ban_id");
    }

    @Test
    void anOnboardingIsApprovedOnceAndForAll() {
        assertThat(QbAccounts.SPONSOR_ENTITY.allowsTransition(SponsorStatus.PENDING, SponsorStatus.APPROVED)).isTrue();
        assertThat(QbAccounts.SPONSOR_ENTITY.allowsTransition(SponsorStatus.PENDING, SponsorStatus.REJECTED)).isTrue();
        assertThat(QbAccounts.SPONSOR_ENTITY.allowsTransition(SponsorStatus.REJECTED, SponsorStatus.PENDING)).isTrue();
        assertThat(QbAccounts.SPONSOR_ENTITY.allowsTransition(SponsorStatus.REJECTED, SponsorStatus.APPROVED))
            .isTrue();
        assertThat(QbAccounts.SPONSOR_ENTITY.allowsTransition(SponsorStatus.APPROVED, SponsorStatus.PENDING))
            .isFalse();
        assertThat(QbAccounts.SPONSOR_ENTITY.allowsTransition(SponsorStatus.APPROVED, SponsorStatus.REJECTED))
            .isFalse();
    }

    @Test
    void locksAndTaskSourcesAreByUser() {
        assertThat(QbAccounts.lockName("u-1")).isEqualTo("qb.account:u-1");
        assertThat(QbAccounts.lockName(null)).isNull();
        assertThat(QbAccounts.taskSource("u-1")).isEqualTo("qb.sponsor:u-1");
    }

    @Test
    void theSponsorConsoleIsOnTheMailHostUnlessSet() {
        assertThat(AccountLinks.of("https://sponsor.example.com/", "https://admin.example.com/admin").sponsor())
            .isEqualTo("https://sponsor.example.com/");
        assertThat(AccountLinks.of(null, "https://quizbuks.example.com:8443/admin").sponsor())
            .isEqualTo("https://quizbuks.example.com:8443/sponsor/");
        assertThat(AccountLinks.of("", "").sponsor()).isEmpty();
        assertThat(AccountLinks.of(" ", "not a url").sponsor()).isEmpty();
    }

    @Test
    void mailWithoutTheConsolesAddressIsWarnedOf() {
        assertThat(AccountLinksCheck.problems(true, new AccountLinks(""))).singleElement()
            .satisfies(problem -> assertThat(problem.isError()).isFalse());
        assertThat(AccountLinksCheck.problems(true, new AccountLinks("https://q.example.com/sponsor/"))).isEmpty();
        assertThat(AccountLinksCheck.problems(false, new AccountLinks(""))).isEmpty();
    }

    @Test
    void administratorsAreTheAdministrationsRoles() {
        assertThat(BanProcesses.administrative(java.util.Set.of("ADMIN", "QB_ADMIN_SUPER"), "QB_ADMIN_SUPER")).isTrue();
        assertThat(BanProcesses.administrative(java.util.Set.of("ADMIN", "QB_ADMIN_SUPER"), "QB_TAKER")).isFalse();
        assertThat(BanProcesses.administrative(java.util.Set.of("ADMIN"), null)).isFalse();
        // An administration accepting every role (not configured) protects every role.
        assertThat(BanProcesses.administrative(java.util.Set.of("*"), "QB_TAKER")).isTrue();
    }
}
