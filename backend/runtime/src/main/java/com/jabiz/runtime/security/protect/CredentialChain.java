package com.jabiz.runtime.security.protect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Whether a user's way in is theirs alone (decision D48, reviews M3 and its verification; docs/design/10-security.md
 * section 16.2): a chain of ownership over the history of the account, in the order of the operations.
 *
 * <p>A credential is <em>own</em> when it was established while nobody else could have known a working credential:
 * <ul>
 *   <li>A <b>password</b> is own when it came from self-registration (or self-provisioning at an identity provider);
 *       when the user changed it themselves while the account was <em>clean</em> (its password own); or when a mailed
 *       one-time link set it (reset, joining, verifying a pre-claimable account) at an address that is own. Any other
 *       password - set by an administrator, at creation or later, or applied after an approval - is not own, and
 *       neither is anything the user derives from it by a change of their own: an administrator who knows the old
 *       password can change it as well as the user.</li>
 *   <li>An <b>address</b> is own when the user registered with it, or set it themselves while clean; an address an
 *       administrator entered when creating the account becomes own once a mailed link proves it (whoever reads that
 *       mailbox chose what follows - the approvers see the address and who entered it, as they would that of a
 *       self-registered account). An address someone else changed later is not own: that is how an account is taken
 *       over.</li>
 *   <li>A <b>link to an identity provider</b> is own when made by self-provisioning, or by the user while clean; a
 *       <b>second factor</b> is own when the user set its secret while clean - judged at the latest version that
 *       changed its secret or confirmation (restarting an enrolment writes a new secret on the same row), not at
 *       versions that only use up recovery codes (review L-a).</li>
 * </ul>
 * The account is clean while its password is not foreign, every link is own, and no foreign address is trusted (a
 * verified address someone else chose gets the reset link); the user's credentials are own when the account is clean
 * and every second factor is own. The versions of each row must follow the order of their operations; when they do
 * not (two transactions committing out of order), nothing is judged own (review L-b). Nothing can tell who reads a
 * mailbox or holds a device: an administrator who controls the user's mailbox or phone can still act as the user
 * (detective controls).
 */
final class CredentialChain {

    private CredentialChain() {}

    /** Processes that create a user the person registered themselves. */
    static final Set<String> SELF_REGISTRATION = Set.of("SPONSOR_SIGN_UP", "SPONSOR_OIDC_SIGN_UP",
        "SPONSOR_EXTERNAL_TOKEN_SIGN_UP");
    /** Processes in which a mailed one-time link to the user's address is used (they prove the address). */
    static final Set<String> MAIL_PROVEN = Set.of("SPONSOR_PASSWORD_RESET_CONFIRM", "SPONSOR_EMAIL_VERIFY",
        "SPONSOR_SIGN_UP_JOIN");
    /** The process in which a signed-in user changes their own password. */
    static final String SELF_PASSWORD_CHANGE = "SEC_ACCOUNT_PASSWORD_CHANGE";
    /** Processes that open an account for an identity a provider vouched for, linking it as they open it. */
    static final Set<String> PROVIDER_SIGN_UP = Set.of("SPONSOR_OIDC_SIGN_UP", "SPONSOR_EXTERNAL_TOKEN_SIGN_UP");
    /** Processes in which a user links their own account at a provider. */
    static final Set<String> SELF_IDENTITY = Set.of("SEC_IDENTITY_LINK_SELF", "SPONSOR_OIDC_SIGN_UP",
        "SPONSOR_EXTERNAL_TOKEN_SIGN_UP");
    /** Processes in which a user enrols their own second factor. */
    static final Set<String> SELF_MFA = Set.of("SEC_MFA_ENROLL_BEGIN", "SEC_MFA_ENROLL_CONFIRM");

    enum Source { USER, IDENTITY, MFA }

    /**
     * One version of the user, of a link or of a second factor, with the operation that wrote it.
     *
     * @param key          the link's or second factor's id (null for the user)
     * @param passwordHash the user's (USER only); compared, never shown
     * @param provider     the link's provider (IDENTITY only)
     * @param versionNo    the row's version number (negative: unknown, the operation's order is taken)
     * @param factor       what makes a second factor what it is - its secret and whether confirmed (MFA only);
     *                     compared, never shown; null: unknown, only the first version counts
     */
    record Event(long seq, Source source, String key, boolean deleted, String passwordHash, String email,
        String verifiedEmail, String provider, String actorId, String actorName, String process, Instant at,
        int versionNo, String factor) {
        Event {
            Objects.requireNonNull(source, "source must not be null");
        }

        Event(long seq, Source source, String key, boolean deleted, String passwordHash, String email,
            String verifiedEmail, String provider, String actorId, String actorName, String process, Instant at) {
            this(seq, source, key, deleted, passwordHash, email, verifiedEmail, provider, actorId, actorName, process,
                at, -1, null);
        }
    }

    /**
     * @param own                   whether the user's credentials are theirs alone
     * @param setBy                 who made the last credential that is not (null when own)
     * @param setAt                 when
     * @param summary               for the approvers: who last set the password, and when
     * @param history               for the approvers: every change of the user's credentials, oldest first; the
     *                              first line is the account's creation
     * @param email                 the current address (null when none)
     * @param address               for the approvers: the current address, who set it and when, and whether a mailed
     *                              link proved it
     * @param clean                 whether nobody else could sign in as the user now
     * @param addressChangedByOther whether someone other than the user changed the address after the account was
     *                              created
     */
    record Judgement(boolean own, String setBy, Instant setAt, String summary, List<String> history, String email,
        String address, boolean clean, boolean addressChangedByOther) {
        Judgement {
            history = List.copyOf(history);
        }
    }

    private enum State { NONE, OWN, UNPROVEN, FOREIGN }

    /** Judges {@code user}'s credentials from the versions of the account, its links and second factors. */
    static Judgement judge(String user, List<Event> events) {
        List<Event> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparingLong(Event::seq).thenComparing(Event::source)
            .thenComparingInt(Event::versionNo));
        Walk walk = new Walk(user);
        walk.disordered = disordered(events);
        ordered.forEach(walk::accept);
        return walk.judgement();
    }

    /** Whether the versions of some row do not follow the order of their operations (review L-b). */
    private static boolean disordered(List<Event> events) {
        Map<String, List<Event>> rows = new LinkedHashMap<>();
        for (Event event : events) {
            if (event.versionNo() >= 0) {
                rows.computeIfAbsent(event.source() + ":" + event.key(), row -> new ArrayList<>()).add(event);
            }
        }
        for (List<Event> row : rows.values()) {
            row.sort(Comparator.comparingInt(Event::versionNo));
            for (int i = 1; i < row.size(); i++) {
                if (row.get(i).seq() < row.get(i - 1).seq()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class Walk {

        private final String user;
        private boolean disordered;
        private State password = State.NONE;
        private State address = State.NONE;
        private boolean created;
        private String hash;
        private String email;
        private boolean verified;
        private final Map<String, Boolean> links = new LinkedHashMap<>();
        private final Map<String, Boolean> factors = new LinkedHashMap<>();
        private final Map<String, String> factorStates = new LinkedHashMap<>();
        private final List<String> history = new ArrayList<>();
        private String passwordBy;
        private Instant passwordAt;
        private String foreignBy;
        private Instant foreignAt;
        private String addressHow;
        private Instant addressProvenAt;
        private boolean addressChangedByOther;

        Walk(String user) {
            this.user = user;
        }

        boolean clean() {
            return !disordered && password != State.FOREIGN && !links.containsValue(false)
                && !(address == State.FOREIGN && verified);
        }

        void accept(Event event) {
            switch (event.source()) {
                case USER -> user(event);
                case IDENTITY -> link(event);
                case MFA -> factor(event);
            }
        }

        private boolean byUser(Event event) {
            return user.equals(event.actorId());
        }

        private void user(Event event) {
            List<String> changes = new ArrayList<>();
            boolean wasClean = clean();
            State passwordBefore = password;
            String hashBefore = created ? hash : null;
            String emailBefore = created ? email : null;
            String newEmail = event.email() == null || event.email().isBlank() ? null : event.email();
            if (!created) {
                created = true;
                boolean self = SELF_REGISTRATION.contains(event.process());
                password = event.passwordHash() == null ? State.NONE : self ? State.OWN : State.FOREIGN;
                address = newEmail == null ? State.NONE : self ? State.OWN : State.UNPROVEN;
                addressHow = newEmail == null ? null
                    : (self ? "registered by the user" : "entered by " + who(event)) + " at " + event.at();
                changes.add("created");
                if (event.passwordHash() != null) {
                    changes.add("password " + verdict(password));
                    passwordSet(event);
                }
                if (newEmail != null) {
                    changes.add("address " + newEmail);
                }
            } else {
                if (!Objects.equals(lower(newEmail), lower(email))) {
                    address = newEmail == null ? State.NONE : byUser(event) && wasClean ? State.OWN : State.FOREIGN;
                    addressHow = newEmail == null ? null
                        : (byUser(event) ? "set by the user" : "changed by " + who(event)) + " at " + event.at();
                    addressProvenAt = null;
                    addressChangedByOther = newEmail != null && !byUser(event);
                    changes.add(newEmail == null ? "address removed" : "address changed to " + newEmail);
                }
                boolean proven = MAIL_PROVEN.contains(event.process()) && newEmail != null
                    && newEmail.equalsIgnoreCase(String.valueOf(event.verifiedEmail()));
                if (proven) {
                    addressProvenAt = event.at();
                    if (address == State.UNPROVEN) {
                        address = State.OWN;
                    }
                }
                if (!Objects.equals(event.passwordHash(), hash)) {
                    if (event.passwordHash() == null) {
                        password = State.NONE;
                        changes.add("password removed");
                    } else {
                        if (SELF_PASSWORD_CHANGE.equals(event.process()) && byUser(event)) {
                            password = passwordBefore == State.OWN && wasClean ? State.OWN : State.FOREIGN;
                        } else if (MAIL_PROVEN.contains(event.process())) {
                            password = address == State.OWN ? State.OWN : State.FOREIGN;
                        } else {
                            password = State.FOREIGN;
                        }
                        changes.add("password changed, " + verdict(password));
                        passwordSet(event);
                    }
                } else if (proven) {
                    changes.add("address proven");
                }
                if (event.deleted()) {
                    changes.add("deleted");
                }
            }
            hash = event.passwordHash();
            email = newEmail;
            verified = newEmail != null && newEmail.equalsIgnoreCase(String.valueOf(event.verifiedEmail()));
            if (!changes.isEmpty()) {
                line(event, String.join(", ", changes));
            }
            // The latest operation that made the account's way in another's (the facts name it): not the user's change
            // of a password that was another's already, which only carries the one who set that.
            boolean derived = byUser(event) && passwordBefore == State.FOREIGN;
            if (password == State.FOREIGN && !Objects.equals(event.passwordHash(), hashBefore) && !derived
                || address == State.FOREIGN && !Objects.equals(lower(newEmail), lower(emailBefore))) {
                foreign(event);
            }
        }

        private void passwordSet(Event event) {
            passwordBy = who(event);
            passwordAt = event.at();
        }

        private void link(Event event) {
            if (!links.containsKey(event.key()) && !event.deleted()) {
                boolean own = event.process() != null && (PROVIDER_SIGN_UP.contains(event.process())
                    || SELF_IDENTITY.contains(event.process()) && byUser(event) && clean());
                links.put(event.key(), own);
                line(event, "linked to " + event.provider() + ", " + verdict(own ? State.OWN : State.FOREIGN));
                if (!own) {
                    foreign(event);
                }
            } else if (event.deleted() && links.remove(event.key()) != null) {
                line(event, "unlinked from " + event.provider());
            }
        }

        private void factor(Event event) {
            if (event.deleted()) {
                factorStates.remove(event.key());
                if (factors.remove(event.key()) != null) {
                    line(event, "second factor removed");
                }
                return;
            }
            boolean first = !factors.containsKey(event.key());
            // A new secret or confirmation is judged again; a version that only used up a recovery code is not. A
            // confirmation alone keeps the secret whoever began the enrolment saw: it stays not own if that was not.
            String before = factorStates.get(event.key());
            boolean changed = event.factor() != null && before != null && !event.factor().equals(before);
            boolean secretChanged = changed && !secret(event.factor()).equals(secret(before));
            factorStates.put(event.key(), event.factor());
            if (!first && !changed) {
                return;
            }
            boolean own = SELF_MFA.contains(event.process()) && byUser(event) && clean()
                && (first || secretChanged || factors.get(event.key()));
            factors.put(event.key(), own);
            line(event, (first ? "second factor enrolled, " : "second factor set again, ")
                + verdict(own ? State.OWN : State.FOREIGN));
            if (!own) {
                foreign(event);
            }
        }

        private void foreign(Event event) {
            foreignBy = who(event);
            foreignAt = event.at();
        }

        private void line(Event event, String what) {
            history.add(event.at() + " " + who(event) + " (" + event.process() + "): " + what);
        }

        Judgement judgement() {
            if (disordered) {
                history.add("versions out of the order of their operations: not judged the user's own");
            }
            boolean own = clean() && !factors.containsValue(false);
            String summary = (password == State.NONE ? "no password" : "password set by " + passwordBy + " at "
                + passwordAt) + (own ? "" : "; not the user's own");
            String described = email == null ? "no address" : email + " (" + addressHow
                + (addressProvenAt == null ? ", not proven by mail" : ", proven by mail at " + addressProvenAt) + ")";
            return new Judgement(own, own ? null : foreignBy, own ? null : foreignAt, summary, history, email,
                described, clean(), addressChangedByOther);
        }
    }

    private static String who(Event event) {
        return event.actorName() == null || event.actorName().isBlank() ? String.valueOf(event.actorId())
            : event.actorName();
    }

    /** The secret part of a second factor's {@code secret|confirmed}. */
    private static String secret(String factor) {
        int bar = factor.lastIndexOf('|');
        return bar < 0 ? factor : factor.substring(0, bar);
    }

    private static String verdict(State state) {
        return state == State.OWN ? "own" : "not own";
    }

    private static String lower(String text) {
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }
}
