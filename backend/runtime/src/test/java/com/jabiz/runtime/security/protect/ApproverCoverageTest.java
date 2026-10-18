package com.jabiz.runtime.security.protect;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Review H2: nobody approves a protected change giving (or taking over) more than they hold themselves. */
class ApproverCoverageTest {

    @Test
    void anAdministratorHoldingEverythingItGivesMayApprove() {
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("*"), Set.of("*", "ledger.post"))).isTrue();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.user-role.write", "ledger.post"),
            Set.of("ledger.post"))).isTrue();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.role.write", "ledger.post",
            "approval.decide"), Set.of("ledger.post", "approval.decide"))).isTrue();
    }

    @Test
    void lessThanItGivesOrNoAdministratorsPermissionIsNotEnough() {
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.user-role.write"), Set.of("ledger.post")))
            .isFalse();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.user-role.write", "ledger.post"),
            Set.of("*"))).isFalse();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.user.password", "approval.decide",
            "ledger.post"), Set.of("ledger.post"))).isFalse();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of(), Set.of())).isFalse();
    }

    /** At apply time, deciding itself is judged again too: approval.decide and the level's permission. */
    @Test
    void whenAppliedTheApproverMustStillBeAbleToDecideAtThatLevel() {
        Set<String> full = Set.of("approval.decide", "security.user-role.write", "ledger.post");
        assertThat(ProtectedChangeDecisionCheck.mayDecide(full, "USER_ROLE", Set.of("ledger.post"))).isTrue();
        assertThat(ProtectedChangeDecisionCheck.mayDecide(Set.of("*"), "ROLE_PERMISSION", Set.of("*"))).isTrue();
        assertThat(ProtectedChangeDecisionCheck.mayDecide(Set.of("security.user-role.write", "ledger.post"),
            "USER_ROLE", Set.of("ledger.post"))).as("without approval.decide").isFalse();
        assertThat(ProtectedChangeDecisionCheck.mayDecide(full, "ROLE_PERMISSION", Set.of("ledger.post")))
            .as("the level of a role's permission is security.role.write").isFalse();
        assertThat(ProtectedChangeDecisionCheck.mayDecide(Set.of("approval.decide", "security.user-role.write"),
            "PASSWORD", Set.of())).isTrue();
    }
}
