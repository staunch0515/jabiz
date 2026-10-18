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
 * section 16.5): a chain of ownership over the history of the account, in the order of the operations.
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
 *       mailbox chose what follows - the approvers see the address, as they would that of a self-registered account).
 *       An address an administrator changed later is not own: that is how an account is taken over.</li>
 *   <li>A <b>link to an identity provider</b> is own when made by self-provisioning, or by the user while clean; a
 *       <b>second factor</b> is own when the user enrolled it while clean (judged by its first version: later versions
 *       are the platform's, as when a recovery code is used).</li>
 * </ul>
 * The account is clean while its password is not foreign, every link is own, and no foreign address is trusted (a
 * verified address someone else chose gets the reset link); the user's credentials are own when the account is clean
 * and every second factor is own. Nothing can tell who reads a mailbox or holds a device: an administrator who
 * controls the user's mailbox or phone can still act as the user (section 16.5, detective controls).
 */
final class CredentialChain {

    private CredentialChain() {}

    /** Processes that create a user the person registered themselves. */
    static final Set<String> SELF_REGISTRATION = Set.of("SPONSOR_SIGN_UP", "SPONSOR_OIDC_SIGN_UP");
    /** Processes in which a mailed one-time link to the user's address is used (they prove the address). */
    static final Set<String> MAIL_PROVEN = Set.of("SPONSOR_PASSWORD_RESET_CONFIRM", "SPONSOR_EMAIL_VERIFY",
        "SPONSOR_SIGN_UP_JOIN");
    /** The process in which a signed-in user changes their own password. */
    static final String SELF_PASSWORD_CHANGE = "SEC_ACCOUNT_PASSWORD_CHANGE";
    /** Processes in which a user links their own account at a provider. */
    static final Set<String> SELF_IDENTITY = Set.of("SEC_IDENTITY_LINK_SELF", "SPONSOR_OIDC_SIGN_UP");
    /** Processes in which a user enrols their own second factor. */
    static final Set<String> SELF_MFA = Set.of("SEC_MFA_ENROLL_BEGIN", "SEC_MFA_ENROLL_CONFIRM");

    enum Source { USER, IDENTITY, MFA }

    /**
     * One version of the user, of a link or of a second factor, with the operation that wrote it.
     *
     * @param key          the link's or second factor's id (null for the user)
     * @param passwordHash the user's (USER only); compared, never shown
     * @param provider     the link's provider (IDENTITY only)
     */
    record Event(long seq, Source source, String key, boolean deleted, String passwordHash, String email,
        String verifiedEmail, String provider, String actorId, String actorName, String process, Instant at) {
        Event {
            Objects.requireNonNull(source, "source must not be null");
        }
    }

    /**
     * @param own     whether the user's credentials are theirs alone
     * @param setBy   who made the last credential that is not (null when own)
     * @param setAt   when
     * @param summary for the approvers: who last set the password, and when
     * @param history for the approvers: every change of the user's credentials, oldest first
     */
    record Judgement(boolean own, String setBy, Instant setAt, String summary, List<String> history) {
        Judgement {
            history = List.copyOf(history);
        }
    }

    private enum State { NONE, OWN, UNPROVEN, FOREIGN }

    /** Judges {@code user}'s credentials from the versions of the account, its links and second factors. */
    static Judgement judge(String user, List<Event> events) {
        List<Event> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparingLong(Event::seq).thenComparing(Event::source));
        Walk walk = new Walk(user);
        ordered.forEach(walk::accept);
        return walk.judgement();
    }

    private static final class Walk {

        private final String user;
        private State password = State.NONE;
        private State address = State.NONE;
        private boolean created;
        private String hash;
        private String email;
        private boolean verified;
        private final Map<String, Boolean> links = new LinkedHashMap<>();
        private final Map<String, Boolean> factors = new LinkedHashMap<>();
        private final List<String> history = new ArrayList<>();
        private String passwordBy;
        private Instant passwordAt;
        private String foreignBy;
        private Instant foreignAt;

        Walk(String user) {
            this.user = user;
        }

        boolean clean() {
            return password != State.FOREIGN && !links.containsValue(false)
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
                    changes.add(newEmail == null ? "address removed" : "address changed to " + newEmail);
                }
                boolean proven = MAIL_PROVEN.contains(event.process()) && newEmail != null
                    && newEmail.equalsIgnoreCase(String.valueOf(event.verifiedEmail()));
                if (proven && address == State.UNPROVEN) {
                    address = State.OWN;
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
                boolean own = event.process() != null && (event.process().equals("SPONSOR_OIDC_SIGN_UP")
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
            if (!factors.containsKey(event.key()) && !event.deleted()) {
                boolean own = SELF_MFA.contains(event.process()) && byUser(event) && clean();
                factors.put(event.key(), own);
                line(event, "second factor enrolled, " + verdict(own ? State.OWN : State.FOREIGN));
                if (!own) {
                    foreign(event);
                }
            } else if (event.deleted() && factors.remove(event.key()) != null) {
                line(event, "second factor removed");
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
            boolean own = clean() && !factors.containsValue(false);
            String summary = (password == State.NONE ? "no password" : "password set by " + passwordBy + " at "
                + passwordAt) + (own ? "" : "; not the user's own");
            return new Judgement(own, own ? null : foreignBy, own ? null : foreignAt, summary, history);
        }
    }

    private static String who(Event event) {
        return event.actorName() == null || event.actorName().isBlank() ? String.valueOf(event.actorId())
            : event.actorName();
    }

    private static String verdict(State state) {
        return state == State.OWN ? "own" : "not own";
    }

    private static String lower(String text) {
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }
}
