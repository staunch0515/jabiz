package com.jabiz.runtime.security.protect;

import com.jabiz.security.SodRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Review H2: nobody approves a protected change giving (or taking over) more than they hold themselves. */
class ApproverCoverageTest {

    private static final Set<String> ADMIN = new ProtectedGrantSettings(true, false, List.of(), List.of())
        .administratorPermissions();
    private static final List<SodRule> NONE = List.of();
    /** As QuizBuks: who administers security does not decide approvals nor publish controls. */
    private static final List<SodRule> SECURITY_APART = List.of(new SodRule("sec.apart",
        Set.of("security.user-role.write", "security.role.write"), Set.of("approval.decide", "control.publish")));
    private static final Set<String> SECURITY_ADMIN = Set.of("security.user-role.write", "security.role.write",
        "security.protected-change.decide");

    @Test
    void anAdministratorHoldingEverythingItGivesMayApprove() {
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("*"), Set.of("*", "ledger.post"), ADMIN, NONE))
            .isTrue();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.user-role.write", "ledger.post"),
            Set.of("ledger.post"), ADMIN, NONE)).isTrue();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.role.write", "ledger.post",
            "approval.decide"), Set.of("ledger.post", "approval.decide"), ADMIN, NONE)).isTrue();
    }

    @Test
    void lessThanItGivesOrNoAdministratorsPermissionIsNotEnough() {
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.user-role.write"), Set.of("ledger.post"),
            ADMIN, NONE)).isFalse();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.user-role.write", "ledger.post"),
            Set.of("*"), ADMIN, NONE)).isFalse();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.user.password", "approval.decide",
            "ledger.post"), Set.of("ledger.post"), ADMIN, NONE)).isFalse();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.protected-change.decide", "ledger.post"),
            Set.of("ledger.post"), ADMIN, NONE)).as("deciding is no administrator's permission").isFalse();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of(), Set.of(), ADMIN, NONE)).isFalse();
    }

    /** Decision D51: the administrators' permissions are configurable; * always is one. */
    @Test
    void theAdministratorsPermissionsAreConfigurable() {
        assertThat(ADMIN).containsExactlyInAnyOrder("*", "security.user-role.write", "security.role.write");
        ProtectedGrantSettings own = new ProtectedGrantSettings(true, false, List.of(), List.of(),
            List.of(" qb.security.admin ", ""));
        assertThat(own.administratorPermissions()).containsExactlyInAnyOrder("*", "qb.security.admin");
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("qb.security.admin", "ledger.post"),
            Set.of("ledger.post"), own.administratorPermissions(), NONE)).isTrue();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.user-role.write", "ledger.post"),
            Set.of("ledger.post"), own.administratorPermissions(), NONE)).isFalse();
        // Review L5: an unprotected administrator's permission is an error; leaving out both permissions that change
        // rights a warning.
        assertThat(own.check()).extracting(problem -> problem.severity() + " " + problem.message())
            .anySatisfy(line -> assertThat(line).startsWith("ERROR").contains("qb.security.admin")
                .contains("not a protected permission"))
            .anySatisfy(line -> assertThat(line).startsWith("WARNING").contains("security.user-role.write"));
        assertThat(new ProtectedGrantSettings(true, false, List.of(), List.of(), List.of("security.role.write"))
            .check()).isEmpty();
        assertThat(new ProtectedGrantSettings(true, false, List.of(), List.of()).check()).isEmpty();
        assertThat(new ProtectedGrantSettings(true, false, List.of(), List.of(), List.of("bad code")).check())
            .singleElement().satisfies(problem -> assertThat(problem.toString()).contains("bad code"));
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

    /**
     * At apply time, deciding itself is judged again too: security.protected-change.decide (decision D51; not
     * approval.decide) and the level's permission.
     */
    @Test
    void whenAppliedTheApproverMustStillBeAbleToDecideAtThatLevel() {
        Set<String> full = Set.of("security.protected-change.decide", "security.user-role.write", "ledger.post");
        assertThat(ProtectedChangeDecisionCheck.mayDecide(full, "USER_ROLE", Set.of("ledger.post"), ADMIN, NONE))
            .isTrue();
        assertThat(ProtectedChangeDecisionCheck.mayDecide(Set.of("*"), "ROLE_PERMISSION", Set.of("*"), ADMIN, NONE))
            .isTrue();
        assertThat(ProtectedChangeDecisionCheck.mayDecide(Set.of("security.user-role.write", "ledger.post"),
            "USER_ROLE", Set.of("ledger.post"), ADMIN, NONE)).as("without the permission to decide").isFalse();
        assertThat(ProtectedChangeDecisionCheck.mayDecide(Set.of("approval.decide", "security.user-role.write",
            "ledger.post"), "USER_ROLE", Set.of("ledger.post"), ADMIN, NONE)).as("approval.decide is not it").isFalse();
        assertThat(ProtectedChangeDecisionCheck.mayDecide(full, "ROLE_PERMISSION", Set.of("ledger.post"), ADMIN, NONE))
            .as("the level of a role's permission is security.role.write").isFalse();
        assertThat(ProtectedChangeDecisionCheck.mayDecide(Set.of("security.protected-change.decide",
            "security.user-role.write"), "PASSWORD", Set.of(), ADMIN, NONE)).isTrue();
    }

    /**
     * Decision D51 amendment: a gained permission a published SoD rule forbids the approver to hold alongside what
     * they hold need not be held; everything else, and * always, still must.
     */
    @Test
    void whatAnSodRuleForbidsTheApproverToHoldIsNotRequired() {
        Set<String> finance = Set.of("approval.decide", "control.publish", "ledger.post");
        assertThat(ProtectedChangeDecisionCheck.exempt(SECURITY_ADMIN, finance, SECURITY_APART))
            .containsExactly(Map.entry("approval.decide", "sec.apart"), Map.entry("control.publish", "sec.apart"));
        // ledger.post is in no rule: still required.
        assertThat(ProtectedChangeDecisionCheck.covers(SECURITY_ADMIN, finance, ADMIN, SECURITY_APART)).isFalse();
        Set<String> withLedger = new java.util.TreeSet<>(SECURITY_ADMIN);
        withLedger.add("ledger.post");
        assertThat(ProtectedChangeDecisionCheck.covers(withLedger, finance, ADMIN, SECURITY_APART)).isTrue();
        assertThat(ProtectedChangeDecisionCheck.mayDecide(withLedger, "USER_ROLE", finance, ADMIN, SECURITY_APART))
            .isTrue();
        // Without the rule, coverage as before.
        assertThat(ProtectedChangeDecisionCheck.covers(withLedger, finance, ADMIN, NONE)).isFalse();
        assertThat(ProtectedChangeDecisionCheck.mayDecide(withLedger, "USER_ROLE", finance, ADMIN, NONE)).isFalse();
        // * is never exempt, whatever a rule says.
        List<SodRule> star = List.of(new SodRule("odd", Set.of("security.user-role.write"), Set.of("approval.decide")));
        assertThat(ProtectedChangeDecisionCheck.exempt(SECURITY_ADMIN, Set.of("*"), star)).isEmpty();
        assertThat(ProtectedChangeDecisionCheck.covers(SECURITY_ADMIN, Set.of("*"), ADMIN, star)).isFalse();
        // Only against what the approver holds: a rule they do not touch exempts nothing; an administrator's
        // permission is still needed.
        assertThat(ProtectedChangeDecisionCheck.exempt(Set.of("security.protected-change.decide"), finance,
            SECURITY_APART)).isEmpty();
        assertThat(ProtectedChangeDecisionCheck.covers(Set.of("security.protected-change.decide", "approval.decide"),
            Set.of("ledger.post"), ADMIN, List.of(new SodRule("r", Set.of("approval.decide"), Set.of("ledger.post")))))
            .as("no administrator's permission").isFalse();
        // The facts say so for the approvers.
        assertThat(AssessProtectedChange.exemptions(finance, SECURITY_APART)).containsExactly(
            "approver holding any of security.role.write, security.user-role.write cannot hold approval.decide by"
                + " rule sec.apart: not required of them",
            "approver holding any of security.role.write, security.user-role.write cannot hold control.publish by"
                + " rule sec.apart: not required of them");
        assertThat(AssessProtectedChange.exemptions(Set.of("*"), star)).isEmpty();
    }
}
