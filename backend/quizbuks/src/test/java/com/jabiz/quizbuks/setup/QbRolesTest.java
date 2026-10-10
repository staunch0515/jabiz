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
            .containsAll(QbRoles.of(QbRoles.ADMIN_FINANCE).permissions())
            .contains(QbPermissions.SETUP, QbPermissions.ADMIN_USERS_BAN, "platform.param.read", "control.propose",
                "security.user-role.write")
            .doesNotHaveDuplicates();
    }

    @Test
    void noRoleWritesParametersOrPublishesControlledChanges() {
        // Parameters change through controlled changes (platform decision D40); the platform's administrator, or a
        // role an administrator grants it to, publishes them.
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .doesNotContain("platform.param.write", "control.publish"));
        // The sponsors' content datasets are read only; their write permission is held by nobody.
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .doesNotContain(QbPermissions.SPONSOR_VIEW_WRITE));
    }

    @Test
    void takersAndSponsorsHoldOnlyTheirOwnPermissions() {
        assertThat(QbRoles.of(QbRoles.TAKER).permissions()).containsExactlyInAnyOrder(QbPermissions.COUNTRY_READ,
            QbPermissions.PLAY, QbPermissions.ME, QbPermissions.PAYOUT_ONBOARD);
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
        assertThat(QbRoles.of(QbRoles.TAKER).permissions()).doesNotContain(QbPermissions.CONTENT_FILE_READ);
        assertThat(QbRoles.of(QbRoles.ADMIN_FINANCE).permissions())
            .doesNotContain(QbPermissions.ADMIN_QUIZ_READ, QbPermissions.CONTENT_FILE_READ);
    }

    @Test
    void noRoleWritesTheLedgerDirectly() {
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .doesNotContainAnyElementsOf(List.of("ledger.post", "ledger.reverse", "ledger.account.write", "*")));
        assertThat(QbRoles.all()).allSatisfy(role -> assertThat(role.permissions())
            .contains(QbPermissions.COUNTRY_READ));
    }

    @Test
    void refusesUnknownRoles() {
        assertThatThrownBy(() -> QbRoles.of("QB_NOBODY")).hasMessageContaining("QB_NOBODY");
    }
}
