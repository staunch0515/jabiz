package com.jabiz.quizbuks.setup;

import com.jabiz.quizbuks.QbPermissions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QbRolesTest {

    @Test
    void declaresTheFiveRolesOfTheDesign() {
        assertThat(QbRoles.all()).extracting(QbRoles.Role::code).containsExactly(QbRoles.TAKER, QbRoles.SPONSOR,
            QbRoles.ADMIN_CONTENT, QbRoles.ADMIN_FINANCE, QbRoles.ADMIN_SUPER);
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.labels()).containsOnlyKeys("en", "zh", "ja"));
    }

    @Test
    void administratorsNeedASecondFactor() {
        assertThat(QbRoles.all()).filteredOn(QbRoles.Role::requireMfa).extracting(QbRoles.Role::code)
            .containsExactly(QbRoles.ADMIN_CONTENT, QbRoles.ADMIN_FINANCE, QbRoles.ADMIN_SUPER);
    }

    @Test
    void theSuperAdministratorHoldsWhatTheOtherAdministratorsHold() {
        assertThat(QbRoles.of(QbRoles.ADMIN_SUPER).permissions())
            .containsAll(QbRoles.of(QbRoles.ADMIN_CONTENT).permissions())
            .containsAll(QbRoles.of(QbRoles.ADMIN_FINANCE).permissions().stream()
                .filter(permission -> !permission.equals("control.publish") && !permission.equals("inbound.read"))
                .toList())
            .contains(QbPermissions.SETUP, QbPermissions.ADMIN_USERS_BAN, "platform.param.read", "control.propose",
                "security.user-role.write")
            .doesNotHaveDuplicates();
    }

    @Test
    void noRoleWritesParametersAndOnlyFinancePublishesControlledChanges() {
        // Parameters change through controlled changes (platform decision D40): the super administrator proposes,
        // the finance administrator publishes, and no role holds both.
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .doesNotContain("platform.param.write"));
        assertThat(QbRoles.all()).filteredOn(role -> role.permissions().contains("control.publish"))
            .extracting(QbRoles.Role::code).containsExactly(QbRoles.ADMIN_FINANCE);
        assertThat(QbRoles.all()).noneSatisfy(role -> assertThat(role.permissions())
            .contains("control.propose", "control.publish"));
        // The sponsors' content datasets are read only; their write permission is held by nobody.
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .doesNotContain(QbPermissions.SPONSOR_VIEW_WRITE));
    }

    @Test
    void financeIssuesAndReadsTheMonthlyReports() {
        // Q9c (C-4): issuing a report needs the template's own permissions too (qb.admin.finance.read).
        assertThat(QbRoles.of(QbRoles.ADMIN_FINANCE).permissions()).contains("report.issue", "report.archive.read",
            QbPermissions.ADMIN_FINANCE_READ, "ledger.read");
        // The super administrator holds them too (built from finance's list): intended (review L2).
        assertThat(QbRoles.of(QbRoles.ADMIN_SUPER).permissions()).contains("report.issue", "report.archive.read");
        assertThat(QbRoles.of(QbRoles.ADMIN_CONTENT).permissions()).doesNotContain("report.issue",
            "report.archive.read", QbPermissions.ADMIN_FINANCE_READ);
    }

    @Test
    void takersAndSponsorsHoldOnlyTheirOwnPermissions() {
        assertThat(QbRoles.of(QbRoles.TAKER).permissions()).containsExactlyInAnyOrder(QbPermissions.COUNTRY_READ,
            QbPermissions.PLAY, QbPermissions.ME, QbPermissions.PAYOUT_ONBOARD, QbPermissions.ACCOUNT_READ,
            QbPermissions.SPONSOR_AVATAR_READ, QbPermissions.CONTENT_FILE_READ);
        assertThat(QbRoles.of(QbRoles.SPONSOR).permissions()).allMatch(p -> p.startsWith("qb."))
            .doesNotContain(QbPermissions.PLAY, QbPermissions.SETUP);
    }

    @Test
    void contentIsWrittenBySponsorsAndReadByContentAdministrators() {
        // Q3 (docs/quizbuks/plans/Q3-content.md, requirement 1); takers get the file permission in Q5.
        assertThat(QbRoles.of(QbRoles.SPONSOR).permissions())
            .contains(QbPermissions.CONTENT_WRITE, QbPermissions.CONTENT_FILE_READ)
            .doesNotContain(QbPermissions.ADMIN_QUIZ_READ);
        assertThat(QbRoles.of(QbRoles.ADMIN_CONTENT).permissions())
            .contains(QbPermissions.ADMIN_QUIZ_READ, QbPermissions.CONTENT_FILE_READ)
            .doesNotContain(QbPermissions.CONTENT_WRITE);
        assertThat(QbRoles.of(QbRoles.ADMIN_SUPER).permissions())
            .contains(QbPermissions.ADMIN_QUIZ_READ, QbPermissions.CONTENT_FILE_READ)
            .doesNotContain(QbPermissions.CONTENT_WRITE);
        // Takers read the covers, pictures and materials of the quizzes they answer (Q5, D-Q5-6).
        assertThat(QbRoles.of(QbRoles.TAKER).permissions()).contains(QbPermissions.CONTENT_FILE_READ);
        assertThat(QbRoles.of(QbRoles.ADMIN_FINANCE).permissions())
            .doesNotContain(QbPermissions.ADMIN_QUIZ_READ, QbPermissions.CONTENT_FILE_READ);
    }

    @Test
    void publicationsAreWrittenBySponsorsReviewedByContentAdministratorsAndReadByAdministrators() {
        // Q4 (docs/quizbuks/plans/Q4-publishing.md, requirement 1).
        assertThat(QbRoles.of(QbRoles.SPONSOR).permissions()).contains(QbPermissions.PUBLICATION_WRITE)
            .doesNotContain(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.REVIEW_PUBLICATION);
        for (String admin : List.of(QbRoles.ADMIN_CONTENT, QbRoles.ADMIN_FINANCE, QbRoles.ADMIN_SUPER)) {
            assertThat(QbRoles.of(admin).permissions()).as(admin).contains(QbPermissions.ADMIN_PUBLICATION_READ)
                .doesNotContain(QbPermissions.PUBLICATION_WRITE);
        }
        assertThat(QbRoles.of(QbRoles.ADMIN_CONTENT).permissions())
            .contains(QbPermissions.REVIEW_PUBLICATION, "approval.decide", "task.read");
        assertThat(QbRoles.of(QbRoles.TAKER).permissions()).doesNotContain(QbPermissions.ADMIN_PUBLICATION_READ);
        // Only the platform runs the system's publication processes (the job, the approval events).
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .doesNotContain(QbPermissions.PUBLICATION_SYSTEM));
    }

    @Test
    void accountsAreTheirOwnersAndOnlyAdministratorsReadOrBanOthers() {
        // Q2 (docs/quizbuks/plans/Q2-accounts.md, requirement 2).
        for (String own : List.of(QbRoles.TAKER, QbRoles.SPONSOR)) {
            assertThat(QbRoles.of(own).permissions()).as(own).contains(QbPermissions.ACCOUNT_READ,
                QbPermissions.SPONSOR_AVATAR_READ).doesNotContain(QbPermissions.ADMIN_USERS_READ,
                QbPermissions.ADMIN_USERS_BAN, QbPermissions.REVIEW_SPONSOR, "security.user.read",
                "security.login-record.read");
        }
        assertThat(QbRoles.of(QbRoles.TAKER).permissions()).contains(QbPermissions.ME)
            .doesNotContain(QbPermissions.SPONSOR_ME);
        assertThat(QbRoles.of(QbRoles.SPONSOR).permissions()).contains(QbPermissions.SPONSOR_ME)
            .doesNotContain(QbPermissions.ME);
        assertThat(QbRoles.of(QbRoles.ADMIN_CONTENT).permissions()).contains(QbPermissions.REVIEW_SPONSOR,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.SPONSOR_AVATAR_READ, "approval.decide")
            .doesNotContain(QbPermissions.ADMIN_USERS_BAN);
        assertThat(QbRoles.of(QbRoles.ADMIN_SUPER).permissions()).contains(QbPermissions.ADMIN_USERS_BAN,
            QbPermissions.REVIEW_SPONSOR);
        // Only the platform applies an onboarding review (the approval events).
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .doesNotContain(QbPermissions.SPONSOR_SYSTEM, QbPermissions.CONTENT_SYSTEM));
    }

    @Test
    void noRoleWritesTheLedgerDirectly() {
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .doesNotContainAnyElementsOf(List.of("ledger.post", "ledger.reverse", "ledger.account.write", "*")));
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .contains(QbPermissions.COUNTRY_READ));
    }

    @Test
    void onlyThePlatformRunsTheAnsweringSystemProcessesAndNobodyCreditsWallets() {
        // Q5 (docs/quizbuks/plans/Q5-answering.md, requirement 1).
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .doesNotContain(QbPermissions.PLAY_SYSTEM, QbPermissions.WALLET_DEV_CREDIT));
        assertThat(QbRoles.of(QbRoles.TAKER).permissions()).contains(QbPermissions.PLAY, QbPermissions.ME);
        assertThat(QbRoles.of(QbRoles.ADMIN_FINANCE).permissions()).contains(QbPermissions.ADMIN_FINANCE_READ);
        assertThat(QbRoles.of(QbRoles.ADMIN_CONTENT).permissions()).contains(QbPermissions.ADMIN_USERS_READ);
    }

    @Test
    void refusesUnknownRoles() {
        assertThatThrownBy(() -> QbRoles.of("QB_NOBODY")).hasMessageContaining("QB_NOBODY");
    }

    @Test
    void topUpsAndReconciliationGrants() {
        // Q6a: Stripe's events as received hold payers' details: the finance administrator alone reads them.
        assertThat(QbRoles.all()).filteredOn(role -> role.permissions().contains("inbound.read"))
            .extracting(QbRoles.Role::code).containsExactly(QbRoles.ADMIN_FINANCE);
        assertThat(QbRoles.of(QbRoles.ADMIN_FINANCE).permissions()).contains("external-job.read",
            QbPermissions.RECONCILE, QbPermissions.ADMIN_FINANCE_READ);
        assertThat(QbRoles.of(QbRoles.SPONSOR).permissions()).contains(QbPermissions.TOPUP,
            QbPermissions.SPONSOR_FINANCE_READ);
        // The system's processes, the development credit and the ledger's writes are nobody's.
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions()).doesNotContain(
            QbPermissions.WALLET_SYSTEM, QbPermissions.PAYOUT_SYSTEM, QbPermissions.WALLET_DEV_CREDIT,
            QbPermissions.PLAY_SYSTEM, "ledger.post",
            "ledger.reverse", "inbound.write", "external-job.complete", "external-job.write"));
    }
}
