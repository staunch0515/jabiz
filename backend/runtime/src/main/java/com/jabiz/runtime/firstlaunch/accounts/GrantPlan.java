package com.jabiz.runtime.firstlaunch.accounts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What the first launch's waiting phase does next (docs/design/09-decisions.md D82; plan F-LAUNCH section 5): pure,
 * judged from the accounts as they are now, so that running it again does no harm.
 * <ul>
 *   <li>A role is granted once its account accepted the invitation and bound a second factor; an administrator's role
 *       (decision D51) also needs the operator's confirmation of the account's {@linkplain #fingerprint fingerprint}.
 *       Nobody waits for anybody else, but in each round the administrators' roles come last: while the setup window is
 *       open the other grants take effect at once, and the window closes only when the second administrator gets
 *       theirs.</li>
 *   <li>A grant that waits for another administrator's approval ({@link RoleState#PENDING}) is handed over: not asked
 *       for again, and listed in what is left for people to do.</li>
 *   <li>The waiting role is withdrawn only once a configured role is {@link RoleState#APPLIED}.</li>
 *   <li>An account never invited is invited; one that has not accepted after {@code remindAfter} is invited once
 *       more.</li>
 * </ul>
 */
public final class GrantPlan {

    private GrantPlan() {}

    /** @param administrator whether the role makes its holder an administrator in the sense of decision D51 */
    public record Role(String code, boolean administrator) {
        public Role {
            Objects.requireNonNull(code, "code must not be null");
        }
    }

    /**
     * Where a configured role of an account stands: never given ({@code NONE}), in effect, waiting for another
     * administrator's approval, or decided by the administrators otherwise - a grant they rejected, a role given and
     * taken away again - which the first launch leaves to them.
     */
    public enum RoleState { NONE, APPLIED, PENDING, DECIDED }

    /**
     * An account of the run as it is now.
     *
     * @param key             the account's key in the configuration
     * @param roles           the configured roles, in the configuration's order
     * @param acceptedAt      when the invitation was accepted (null: not yet)
     * @param mfaConfirmedAt  when the current second factor was confirmed (null: none)
     * @param states          the configured roles' states; a role not named is {@link RoleState#NONE}
     * @param holdsWaitingRole whether the account still holds the waiting role
     * @param invitedAt       when the first invitation was sent (null: never)
     * @param reminded        whether the account was invited once more already
     * @param enabled         whether the user is enabled: a disabled account (someone saw something wrong) gets
     *                        nothing more and is left to people
     * @param invitable       whether an invitation may go to its address (decision D63: nobody else changed it); one
     *                        that may not and has not accepted is left to people
     */
    public record Account(String key, UUID userId, List<Role> roles, Instant acceptedAt, Instant mfaConfirmedAt,
        Map<String, RoleState> states, boolean holdsWaitingRole, Instant invitedAt, boolean reminded, boolean enabled,
        boolean invitable) {
        public Account {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(userId, "userId must not be null");
            roles = List.copyOf(roles);
            states = Map.copyOf(states);
            if (roles.isEmpty()) {
                throw new IllegalArgumentException("The account " + key + " has no roles");
            }
        }

        /** An enabled account whose address may be invited. */
        public Account(String key, UUID userId, List<Role> roles, Instant acceptedAt, Instant mfaConfirmedAt,
            Map<String, RoleState> states, boolean holdsWaitingRole, Instant invitedAt, boolean reminded) {
            this(key, userId, roles, acceptedAt, mfaConfirmedAt, states, holdsWaitingRole, invitedAt, reminded, true,
                true);
        }

        public RoleState state(String role) {
            return states.getOrDefault(role, RoleState.NONE);
        }

        /** Enabled, accepted the invitation and bound a second factor. */
        public boolean ready() {
            return enabled && acceptedAt != null && mfaConfirmedAt != null;
        }

        /** Disabled, or not accepted and not invitable: the first launch does nothing more for it. */
        public boolean leftToPeople() {
            return !enabled || acceptedAt == null && !invitable;
        }

        /** Whether a configured role is in effect (with {@link #ready()}, the account is ready for the application's steps). */
        public boolean anyApplied() {
            return roles.stream().anyMatch(role -> state(role.code()) == RoleState.APPLIED);
        }

        /**
         * Every configured role applied or handed over, and the waiting role withdrawn where it may be. An account
         * whose roles are all pending keeps the waiting role until the run ends, which disables it.
         */
        boolean settled() {
            if (leftToPeople()) {
                return true;
            }
            return !(holdsWaitingRole && anyApplied()) && roles.stream().allMatch(role -> state(role.code()) != RoleState.NONE);
        }
    }

    /** What to do. */
    public sealed interface Action {
        String key();

        UUID userId();

        /** Assign the role ({@code SEC_USER_ROLE_ASSIGN}). */
        record Assign(String key, UUID userId, String role) implements Action {}

        /** Withdraw the waiting role: a configured role is in effect. */
        record WithdrawWaitingRole(String key, UUID userId) implements Action {}

        /** Tell the operator (in the log) the fingerprint to confirm before an administrator's role is granted. */
        record AwaitConfirmation(String key, UUID userId, String fingerprint) implements Action {}

        /** Invite for the first time ({@code SEC_USER_INVITE}), once the content phase is done. */
        record Invite(String key, UUID userId) implements Action {}

        /** Invite once more ({@code SEC_USER_INVITE}). */
        record Remind(String key, UUID userId) implements Action {}
    }

    /** A grant waiting for another administrator's approval: the first launch's part is done. */
    public record HandedOver(String key, UUID userId, String role) {}

    /** The actions of one round, in order (see the class comment). */
    public static List<Action> next(String runId, List<Account> accounts, Set<String> confirmed, Instant now,
        Duration remindAfter) {
        List<Action> others = new ArrayList<>();
        List<Action> withdrawals = new ArrayList<>();
        List<Action> administrators = new ArrayList<>();
        List<Action> waiting = new ArrayList<>();
        List<Action> reminders = new ArrayList<>();
        for (Account account : accounts) {
            if (account.leftToPeople()) {
                continue;
            }
            if (!account.ready()) {
                if (account.acceptedAt() == null && account.invitedAt() == null) {
                    reminders.add(new Action.Invite(account.key(), account.userId()));
                } else if (account.acceptedAt() == null && !account.reminded()
                    && !now.isBefore(account.invitedAt().plus(remindAfter))) {
                    reminders.add(new Action.Remind(account.key(), account.userId()));
                }
                continue;
            }
            String fingerprint = fingerprint(runId, account.userId(), account.mfaConfirmedAt());
            boolean awaiting = false;
            for (Role role : account.roles()) {
                if (account.state(role.code()) != RoleState.NONE) {
                    continue;
                }
                Action assign = new Action.Assign(account.key(), account.userId(), role.code());
                if (!role.administrator()) {
                    others.add(assign);
                } else if (confirmed.contains(fingerprint)) {
                    administrators.add(assign);
                } else if (!awaiting) {
                    awaiting = true;
                    waiting.add(new Action.AwaitConfirmation(account.key(), account.userId(), fingerprint));
                }
            }
            if (account.holdsWaitingRole() && account.anyApplied()) {
                withdrawals.add(new Action.WithdrawWaitingRole(account.key(), account.userId()));
            }
        }
        List<Action> all = new ArrayList<>(others);
        all.addAll(withdrawals);
        all.addAll(administrators);
        all.addAll(waiting);
        all.addAll(reminders);
        return List.copyOf(all);
    }

    /** Whether every configured role is applied or handed over, and every due withdrawal of the waiting role done. */
    public static boolean complete(List<Account> accounts) {
        return accounts.stream().allMatch(Account::settled);
    }

    /** The accounts not settled yet, for the report. */
    public static List<Account> unfinished(List<Account> accounts) {
        return accounts.stream().filter(account -> !account.settled()).toList();
    }

    /** The accounts the first launch leaves to people (disabled, or an address it may not invite), for the report. */
    public static List<Account> leftToPeople(List<Account> accounts) {
        return accounts.stream().filter(Account::leftToPeople).toList();
    }

    /** The grants handed over to the administrators' approval, for the report. */
    public static List<HandedOver> handedOver(List<Account> accounts) {
        List<HandedOver> pending = new ArrayList<>();
        for (Account account : accounts) {
            for (Role role : account.roles()) {
                if (account.state(role.code()) == RoleState.PENDING) {
                    pending.add(new HandedOver(account.key(), account.userId(), role.code()));
                }
            }
        }
        return List.copyOf(pending);
    }

    /** Whether the run's deadline has passed: {@code deadline} after the end of the content phase. */
    public static boolean expired(Instant contentDoneAt, Duration deadline, Instant now) {
        return !now.isBefore(contentDoneAt.plus(deadline));
    }

    /**
     * The fingerprint the operator confirms before an administrator's role is granted: the first 12 hex digits of the
     * SHA-256 of the run, the user and the moment the user's second factor was confirmed. Enrolling again changes it,
     * so a confirmation holds only for the enrolment the operator checked with the person.
     */
    public static String fingerprint(String runId, UUID userId, Instant mfaConfirmedAt) {
        Objects.requireNonNull(mfaConfirmedAt, "mfaConfirmedAt must not be null");
        String text = runId + "|" + userId + "|" + mfaConfirmedAt;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
