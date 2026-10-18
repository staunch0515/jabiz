package com.jabiz.runtime.security.protect;

import org.junit.jupiter.api.Test;

import java.util.List;
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

    /**
     * Review L-c: another change's approval covers a pair of user and permission only while what it wrote lives, over
     * a period at least as wide, in effect no later.
     */
    @Test
    void anotherApprovalCoversOnlyWhileAliveAndAtLeastAsWide() {
        java.time.Instant t0 = java.time.Instant.parse("2026-01-01T00:00:00Z");
        java.time.Instant t1 = java.time.Instant.parse("2026-06-01T00:00:00Z");
        ProtectedGrants.Approved open = new ProtectedGrants.Approved(Set.of("ledger.post"), Set.of("v"), null, null,
            t0, true);
        assertThat(open.covers("v", "ledger.post", null, null, t1)).isTrue();
        assertThat(open.covers("v", "ledger.post", t0, t1, t1)).isTrue();
        assertThat(open.covers("u", "ledger.post", null, null, t1)).isFalse();
        assertThat(open.covers("v", "*", null, null, t1)).isFalse();
        assertThat(open.covers("v", "ledger.post", null, null, t0.minusSeconds(1))).as("in effect later").isFalse();
        ProtectedGrants.Approved revoked = new ProtectedGrants.Approved(Set.of("ledger.post"), Set.of("v"), null, null,
            t0, false);
        assertThat(revoked.covers("v", "ledger.post", null, null, t1)).isFalse();
        ProtectedGrants.Approved narrow = new ProtectedGrants.Approved(Set.of("ledger.post"), Set.of("v"), t0, t1,
            t0, true);
        assertThat(narrow.covers("v", "ledger.post", null, null, t1)).as("unbounded is wider").isFalse();
        assertThat(narrow.covers("v", "ledger.post", t0, t1.plusSeconds(1), t1)).isFalse();
        assertThat(narrow.covers("v", "ledger.post", t0.plusSeconds(1), t1, t1)).isTrue();
    }

    /** Review M-a: addresses outside the configured domains are flagged; nothing is without a configuration. */
    @Test
    void trustedDomainsIncludeTheirSubdomains() {
        ProtectedGrantSettings none = new ProtectedGrantSettings(true, false, List.of(), List.of());
        assertThat(none.trustedAddress("x@gmail.com")).isTrue();
        ProtectedGrantSettings corp = new ProtectedGrantSettings(true, false, List.of(), List.of(" Corp.Example ", ""));
        assertThat(corp.trustedAddress("bob@corp.example")).isTrue();
        assertThat(corp.trustedAddress("bob@mail.corp.example")).isTrue();
        assertThat(corp.trustedAddress("bob@notcorp.example")).isFalse();
        assertThat(corp.trustedAddress("bob.corp.example@gmail.com")).isFalse();
        assertThat(corp.trustedAddress(null)).isTrue();
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
