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
            .contains(QbPermissions.SETUP, QbPermissions.ADMIN_USERS_BAN, "platform.param.write", "control.propose",
                "security.user-role.write")
            .doesNotHaveDuplicates();
    }

    @Test
    void takersAndSponsorsHoldOnlyTheirOwnPermissions() {
        assertThat(QbRoles.of(QbRoles.TAKER).permissions()).containsExactlyInAnyOrder(QbPermissions.COUNTRY_READ,
            QbPermissions.PLAY, QbPermissions.ME, QbPermissions.PAYOUT_ONBOARD);
        assertThat(QbRoles.of(QbRoles.SPONSOR).permissions()).allMatch(p -> p.startsWith("qb."))
            .doesNotContain(QbPermissions.PLAY, QbPermissions.SETUP);
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
