package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.runtime.firstlaunch.accounts.GrantPlan.Account;
import com.jabiz.runtime.firstlaunch.accounts.GrantPlan.Action;
import com.jabiz.runtime.firstlaunch.accounts.GrantPlan.Role;
import com.jabiz.runtime.firstlaunch.accounts.GrantPlan.RoleState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the first launch's waiting phase does next for each account (docs/design/09-decisions.md D82). */
class GrantPlanTest {

    private static final String RUN = "0190a1b2-0000-7000-8000-0000000000f1";
    private static final Instant INVITED = Instant.parse("2026-12-01T00:00:00Z");
    private static final Instant NOW = INVITED.plus(Duration.ofHours(5));
    private static final Duration REMIND_AFTER = Duration.ofDays(3);

    private static final UUID A = UUID.fromString("0190a1b2-0000-7000-8000-00000000000a");
    private static final UUID B = UUID.fromString("0190a1b2-0000-7000-8000-00000000000b");
    private static final UUID C = UUID.fromString("0190a1b2-0000-7000-8000-00000000000c");

    private static final Role OPERATOR = new Role("QB_OPERATOR", false);
    private static final Role FINANCE = new Role("QB_ADMIN_FINANCE", false);
    private static final Role SUPER = new Role("QB_ADMIN_SUPER", true);

    private static Account account(String key, UUID user, List<Role> roles, Instant accepted, Instant mfa,
        Map<String, RoleState> states, boolean waiting) {
        return new Account(key, user, roles, accepted, mfa, states, waiting, INVITED, false);
    }

    private static List<Action> next(List<Account> accounts, Set<String> confirmed) {
        return GrantPlan.next(RUN, accounts, confirmed, NOW, REMIND_AFTER);
    }

    @Test
    void nothingIsGrantedUntilTheInvitationIsAcceptedAndASecondFactorBound() {
        Account notAccepted = account("C", C, List.of(OPERATOR), null, null, Map.of(), true);
        Account noFactor = account("B", B, List.of(FINANCE), INVITED.plusSeconds(60), null, Map.of(), true);
        assertThat(next(List.of(notAccepted, noFactor), Set.of())).isEmpty();
    }

    @Test
    void anAccountReadyGetsEachConfiguredRoleNotHeldYet() {
        Instant mfa = INVITED.plusSeconds(120);
        Account b = account("B", B, List.of(FINANCE, OPERATOR), INVITED.plusSeconds(60), mfa,
            Map.of("QB_ADMIN_FINANCE", RoleState.APPLIED), true);
        assertThat(next(List.of(b), Set.of())).containsExactly(
            new Action.Assign("B", B, "QB_OPERATOR"),
            new Action.WithdrawWaitingRole("B", B));
    }

    @Test
    void theWaitingRoleStaysWhileNoConfiguredRoleIsApplied() {
        Instant mfa = INVITED.plusSeconds(120);
        Account pendingOnly = account("B", B, List.of(FINANCE), INVITED, mfa,
            Map.of("QB_ADMIN_FINANCE", RoleState.PENDING), true);
        assertThat(next(List.of(pendingOnly), Set.of())).isEmpty();
        assertThat(GrantPlan.complete(List.of(pendingOnly))).as("handed over; the run's end disables the role").isTrue();
        Account notYet = account("C", C, List.of(OPERATOR), INVITED, mfa, Map.of(), true);
        assertThat(next(List.of(notYet), Set.of())).containsExactly(new Action.Assign("C", C, "QB_OPERATOR"));
    }

    @Test
    void aPendingGrantIsHandedOverAndNotAskedForAgain() {
        Instant mfa = INVITED.plusSeconds(120);
        Account a = account("A", A, List.of(OPERATOR, SUPER), INVITED, mfa,
            Map.of("QB_OPERATOR", RoleState.APPLIED, "QB_ADMIN_SUPER", RoleState.PENDING), false);
        assertThat(next(List.of(a), Set.of())).isEmpty();
        assertThat(GrantPlan.complete(List.of(a))).isTrue();
        assertThat(GrantPlan.handedOver(List.of(a))).containsExactly(new GrantPlan.HandedOver("A", A,
            "QB_ADMIN_SUPER"));
    }

    @Test
    void anAdministratorsRoleWaitsForTheOperatorsConfirmationOfTheFingerprint() {
        Instant mfa = INVITED.plusSeconds(120);
        Account a = account("A", A, List.of(OPERATOR, SUPER), INVITED, mfa, Map.of(), true);
        String fingerprint = GrantPlan.fingerprint(RUN, A, mfa);
        assertThat(next(List.of(a), Set.of())).containsExactly(
            new Action.Assign("A", A, "QB_OPERATOR"),
            new Action.AwaitConfirmation("A", A, fingerprint));
        assertThat(next(List.of(a), Set.of("000000000000"))).contains(
            new Action.AwaitConfirmation("A", A, fingerprint));
        assertThat(next(List.of(a), Set.of(fingerprint))).containsExactly(
            new Action.Assign("A", A, "QB_OPERATOR"),
            new Action.Assign("A", A, "QB_ADMIN_SUPER"));
    }

    @Test
    void theFingerprintIsBoundToTheEnrolmentSoEnrollingAgainVoidsIt() {
        Instant first = INVITED.plusSeconds(120);
        Instant again = INVITED.plusSeconds(3600);
        String fingerprint = GrantPlan.fingerprint(RUN, A, first);
        assertThat(fingerprint).hasSize(12).matches("[0-9a-f]{12}");
        assertThat(GrantPlan.fingerprint(RUN, A, again)).isNotEqualTo(fingerprint);
        assertThat(GrantPlan.fingerprint(RUN, B, first)).isNotEqualTo(fingerprint);
        assertThat(GrantPlan.fingerprint("0190a1b2-0000-7000-8000-0000000000f2", A, first)).isNotEqualTo(fingerprint);
        Account a = account("A", A, List.of(SUPER), INVITED, again, Map.of(), true);
        assertThat(next(List.of(a), Set.of(fingerprint))).containsExactly(
            new Action.AwaitConfirmation("A", A, GrantPlan.fingerprint(RUN, A, again)));
    }

    @Test
    void administratorsComeLastSoTheSetupWindowStaysOpenForEveryoneElse() {
        Instant mfa = INVITED.plusSeconds(120);
        Account a = account("A", A, List.of(SUPER, OPERATOR), INVITED, mfa, Map.of(), true);
        Account b = account("B", B, List.of(FINANCE), INVITED, mfa, Map.of(), true);
        String fa = GrantPlan.fingerprint(RUN, A, mfa);
        assertThat(next(List.of(a, b), Set.of(fa))).containsExactly(
            new Action.Assign("A", A, "QB_OPERATOR"),
            new Action.Assign("B", B, "QB_ADMIN_FINANCE"),
            new Action.Assign("A", A, "QB_ADMIN_SUPER"));
    }

    @Test
    void anAccountNotAcceptingInTimeIsInvitedOnceMore() {
        Account c = new Account("C", C, List.of(OPERATOR), null, null, Map.of(), true, INVITED, false);
        assertThat(GrantPlan.next(RUN, List.of(c), Set.of(), INVITED.plus(REMIND_AFTER).minusSeconds(1),
            REMIND_AFTER)).isEmpty();
        assertThat(GrantPlan.next(RUN, List.of(c), Set.of(), INVITED.plus(REMIND_AFTER), REMIND_AFTER))
            .containsExactly(new Action.Remind("C", C));
        Account reminded = new Account("C", C, List.of(OPERATOR), null, null, Map.of(), true, INVITED, true);
        assertThat(GrantPlan.next(RUN, List.of(reminded), Set.of(), INVITED.plus(REMIND_AFTER.multipliedBy(2)),
            REMIND_AFTER)).isEmpty();
    }

    @Test
    void theRunIsCompleteWhenEveryRoleIsAppliedOrHandedOver() {
        Instant mfa = INVITED.plusSeconds(120);
        Account done = account("B", B, List.of(FINANCE), INVITED, mfa, Map.of("QB_ADMIN_FINANCE", RoleState.APPLIED),
            false);
        Account open = account("C", C, List.of(OPERATOR), null, null, Map.of(), true);
        assertThat(GrantPlan.complete(List.of(done))).isTrue();
        assertThat(GrantPlan.complete(List.of(done, open))).isFalse();
        // Still holding the waiting role with a role applied: not complete until it is withdrawn.
        Account stillWaiting = account("B", B, List.of(FINANCE), INVITED, mfa,
            Map.of("QB_ADMIN_FINANCE", RoleState.APPLIED), true);
        assertThat(GrantPlan.complete(List.of(stillWaiting))).isFalse();
        assertThat(GrantPlan.unfinished(List.of(done, open))).extracting(Account::key).containsExactly("C");
    }

    @Test
    void theDeadlineCountsFromTheEndOfTheContentPhase() {
        Duration deadline = Duration.ofDays(14);
        assertThat(GrantPlan.expired(INVITED, deadline, INVITED.plus(deadline).minusMillis(1))).isFalse();
        assertThat(GrantPlan.expired(INVITED, deadline, INVITED.plus(deadline))).isTrue();
    }

    @Test
    void anAccountWithoutRolesIsRefused() {
        assertThatThrownBy(() -> new Account("X", A, List.of(), null, null, Map.of(), true, INVITED, false))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
