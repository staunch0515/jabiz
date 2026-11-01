package com.jabiz.quizbuks.setup;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.approval.ApprovalPermissions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QbRolesTest {

    @Test
    void declaresTheFiveRolesOfTheDesign() {
        assertThat(QbRoles.all()).extracting(QbRoles.Role::code).containsExactly(QbRoles.TAKER, QbRoles.SPONSOR,
            QbRoles.SHOPIFY_SHOP, QbRoles.ADMIN_CONTENT, QbRoles.ADMIN_FINANCE, QbRoles.ADMIN_SUPER, QbRoles.OPERATOR);
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.labels()).containsOnlyKeys("en", "zh", "ja"));
    }

    @Test
    void administratorsNeedASecondFactor() {
        assertThat(QbRoles.all()).filteredOn(QbRoles.Role::requireMfa).extracting(QbRoles.Role::code)
            .containsExactly(QbRoles.ADMIN_CONTENT, QbRoles.ADMIN_FINANCE, QbRoles.ADMIN_SUPER, QbRoles.OPERATOR);
    }

    @Test
    void anOperatorReviewsButHandlesNoMoneyUsersOrRights() {
        // Q48a, D-Q48-4: reviews, content, the disputes that move no money and its own acts; nothing of finance, the
        // users' profiles, the legal texts, parameters, roles or headquarters' operator pages.
        assertThat(QbRoles.of(QbRoles.OPERATOR).permissions())
            .contains(QbPermissions.OPERATOR_USE, QbPermissions.REVIEW_SPONSOR, QbPermissions.REVIEW_PUBLICATION,
                "approval.decide", QbPermissions.REDEMPTION_VOID, QbPermissions.DIGITAL_RESOLVE,
                // Q69: its own certification rounds, grants and revocations (headquarters marks the official ones).
                QbPermissions.CERTIFICATION_MANAGE)
            .doesNotContain(QbPermissions.CERTIFICATION_ADMIN, QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_FINANCE_READ,
                QbPermissions.ADMIN_CONTENT_WRITE, QbPermissions.ADMIN_USERS_BAN, QbPermissions.CASH_RESOLVE,
                QbPermissions.TOPUP_BANK_APPROVE, QbPermissions.OPERATORS_READ, QbPermissions.OPERATORS_WRITE,
                QbPermissions.SPOT_CHECK, "control.propose", "control.publish", "platform.param.read",
                "security.user-role.write", "ledger.read")
            .doesNotHaveDuplicates();
    }

    @Test
    void theSuperAdministratorHoldsWhatTheOtherAdministratorsHold() {
        // But publishing controlled changes, Stripe's events and deciding approvals (Q10b, L3: QbSodRules).
        assertThat(QbRoles.of(QbRoles.ADMIN_SUPER).permissions())
            .containsAll(QbRoles.of(QbRoles.ADMIN_CONTENT).permissions().stream()
                .filter(permission -> !permission.equals("approval.decide")).toList())
            .containsAll(QbRoles.of(QbRoles.ADMIN_FINANCE).permissions().stream()
                .filter(permission -> !permission.equals("control.publish") && !permission.equals("inbound.read")
                    && !permission.equals("approval.decide"))
                .toList())
            .doesNotContain("approval.decide", "control.publish")
            .contains(QbPermissions.SETUP, QbPermissions.ADMIN_USERS_BAN, "platform.param.read", "control.propose",
                "security.user-role.write")
            .doesNotHaveDuplicates();
    }

    @Test
    void onlyTheSuperAdministratorDecidesProtectedChanges() {
        // Platform 16u (D51): the four eyes on protected grants are two super administrators, deciding with the
        // permission made for it and reading the facts of the request; no other role decides them.
        assertThat(QbRoles.of(QbRoles.ADMIN_SUPER).permissions()).contains("security.protected-change.decide",
            "approval.read");
        assertThat(QbRoles.all()).filteredOn(role -> role.permissions().contains("security.protected-change.decide"))
            .extracting(QbRoles.Role::code).containsExactly(QbRoles.ADMIN_SUPER);
        // Neither new permission is on a side of a separation of duties: the super administrator breaks no rule.
        assertThat(QbSodRules.ALL).allSatisfy(rule -> assertThat(rule.left()).doesNotContain(
            "security.protected-change.decide", "approval.read"));
        assertThat(QbSodRules.ALL).allSatisfy(rule -> assertThat(rule.right()).doesNotContain(
            "security.protected-change.decide", "approval.read"));
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
        // Who publishes sees what there is to publish: the controlled changes' dataset is read with approval.read.
        assertThat(QbRoles.of(QbRoles.ADMIN_FINANCE).permissions()).contains(ApprovalPermissions.READ);
        // The sponsors' content datasets are read only; their write permission is held by nobody.
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .doesNotContain(QbPermissions.SPONSOR_VIEW_WRITE));
    }

    @Test
    void noRoleHoldsBothSidesOfASeparationOfDuties() {
        // Q10b (L3): QB_SETUP proposes these rules; once published, the platform refuses a role that breaks one.
        for (QbSodRules.Rule rule : QbSodRules.ALL) {
            assertThat(rule.left()).doesNotContainAnyElementsOf(rule.right()).doesNotContain("*");
            assertThat(QbRoles.all()).as(rule.code()).noneSatisfy(role -> {
                assertThat(role.permissions()).containsAnyElementsOf(rule.left());
                assertThat(role.permissions()).containsAnyElementsOf(rule.right());
            });
        }
        assertThat(QbSodRules.ALL).extracting(QbSodRules.Rule::code).doesNotHaveDuplicates();
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
            QbPermissions.SPONSOR_AVATAR_READ, QbPermissions.CONTENT_FILE_READ,
            // Q68: a sponsor's own figures and publications in the app; the sponsor datasets hold the caller's own.
            QbPermissions.SPONSOR_APP);
        // Q9d: besides their own (qb.*), the receipts they issued (the platform shows each sponsor only theirs).
        assertThat(QbRoles.of(QbRoles.SPONSOR).permissions()).allMatch(p -> p.startsWith("qb.")
                || "document.archive.read".equals(p))
            .doesNotContain(QbPermissions.PLAY, QbPermissions.SETUP, "document.issue");
    }

    @Test
    void financeExportsTheJournalAndReadsItsFiles() {
        // Q9d (docs/quizbuks/plans/Q9d-journal-receipts.md section 4); the super administrator is built from finance.
        for (String role : List.of(QbRoles.ADMIN_FINANCE, QbRoles.ADMIN_SUPER)) {
            assertThat(QbRoles.of(role).permissions()).as(role).contains(QbPermissions.JOURNAL_EXPORT,
                QbPermissions.JOURNAL_CONFIRM, "file.generated.read");
        }
        for (String role : List.of(QbRoles.ADMIN_CONTENT, QbRoles.SPONSOR, QbRoles.TAKER)) {
            assertThat(QbRoles.of(role).permissions()).as(role).doesNotContain(QbPermissions.JOURNAL_EXPORT,
                QbPermissions.JOURNAL_CONFIRM, "file.generated.read", "file.generated.archive");
        }
        assertThat(QbRoles.of(QbRoles.SPONSOR).permissions()).contains(QbPermissions.TOPUP, "document.archive.read");
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
        // Every QuizBuks role reads the countries; the Shopify staff's pages have none (S1).
        assertThat(QbRoles.all()).filteredOn(role -> !role.code().equals(QbRoles.SHOPIFY_SHOP))
            .allSatisfy(role -> assertThat(role.permissions()).contains(QbPermissions.COUNTRY_READ));
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
            QbPermissions.PLAY_SYSTEM, QbPermissions.MESSAGING_SYSTEM, "ledger.post",
            "ledger.reverse", "inbound.write", "external-job.complete", "external-job.write"));
    }
}
