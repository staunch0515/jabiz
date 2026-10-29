package com.jabiz.runtime.security.protect;

import com.jabiz.runtime.security.protect.CredentialChain.Event;
import com.jabiz.runtime.security.protect.CredentialChain.Judgement;
import com.jabiz.runtime.security.protect.CredentialChain.Source;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chain of ownership of a user's credentials (decision D48, review M3 verification): a credential is the user's
 * own only when nobody else could have known a working one when it was made.
 */
class CredentialChainTest {

    private static final String USER = "0190a1b2-0000-7000-8000-000000000001";
    private static final String ADMIN = "0190a1b2-0000-7000-8000-0000000000aa";
    private static final String ANONYMOUS = "anonymous";

    private final List<Event> events = new ArrayList<>();
    private long seq;

    private void user(String actor, String process, String hash, String email, String verified) {
        seq++;
        events.add(new Event(seq, Source.USER, null, false, hash, email, verified, null, actor,
            ADMIN.equals(actor) ? "it-admin" : null, process, Instant.ofEpochSecond(seq)));
    }

    private void link(String key, String actor, String process, boolean deleted) {
        seq++;
        events.add(new Event(seq, Source.IDENTITY, key, deleted, null, null, null, "acme", actor, null, process,
            Instant.ofEpochSecond(seq)));
    }

    private void factor(String key, String actor, String process, boolean deleted) {
        seq++;
        events.add(new Event(seq, Source.MFA, key, deleted, null, null, null, null, actor, null, process,
            Instant.ofEpochSecond(seq)));
    }

    private Judgement judged() {
        return CredentialChain.judge(USER, events);
    }

    @Test
    void anAccountWithoutCredentialsIsNobodyElses() {
        user(ADMIN, "SEC_USER_CREATE", null, null, null);
        assertThat(judged().own()).isTrue();
        assertThat(judged().summary()).isEqualTo("no password");
        assertThat(judged().history()).singleElement().asString().contains("it-admin (SEC_USER_CREATE): created");
    }

    @Test
    void aPasswordAnAdministratorSetIsNotOwnNorAnythingTheUserDerivesFromIt() {
        user(ADMIN, "SEC_USER_CREATE", "h1", null, null);
        assertThat(judged().own()).isFalse();
        // The administrator signs in as the user and "changes their own password": still the administrator's.
        user(USER, "SEC_ACCOUNT_PASSWORD_CHANGE", "h2", null, null);
        user(USER, "SEC_ACCOUNT_PASSWORD_CHANGE", "h3", null, null);
        Judgement judged = judged();
        assertThat(judged.own()).isFalse();
        assertThat(judged.setBy()).isEqualTo("it-admin");
        assertThat(judged.summary()).contains("not the user's own");
        assertThat(judged.history()).hasSize(3).last().asString().contains("password changed, not own");
    }

    @Test
    void aRegisteredPasswordIsOwnAndSoAreChangesTheUserMakesToIt() {
        user(ANONYMOUS, "SPONSOR_SIGN_UP", "h1", "u@example.com", null);
        assertThat(judged().own()).isTrue();
        user(USER, "SEC_ACCOUNT_PASSWORD_CHANGE", "h2", "u@example.com", null);
        assertThat(judged().own()).isTrue();
        // An administrator resets it: no longer own, and the user changing it again does not make it so.
        user(ADMIN, "SEC_USER_SET_PASSWORD", "h3", "u@example.com", null);
        user(USER, "SEC_ACCOUNT_PASSWORD_CHANGE", "h4", "u@example.com", null);
        assertThat(judged().own()).isFalse();
        // A mailed reset to the address the user registered with is own again.
        user(ANONYMOUS, "SPONSOR_PASSWORD_RESET_CONFIRM", "h5", "u@example.com", "u@example.com");
        assertThat(judged().own()).isTrue();
    }

    @Test
    void aMailedResetCountsOnlyAtAnAddressNoAdministratorChanged() {
        // Entered by the administrator who created the account: proven by the mailed link, then own.
        user(ADMIN, "SEC_USER_CREATE", "h1", "u@example.com", null);
        user(ANONYMOUS, "SPONSOR_PASSWORD_RESET_CONFIRM", "h2", "u@example.com", "u@example.com");
        assertThat(judged().own()).isTrue();
        // Changed by an administrator later (how an account is taken over): a reset there is not own.
        user(ADMIN, "SEC_USER_UPDATE", "h2", "admin@example.com", "u@example.com");
        assertThat(judged().own()).as("an address someone else chose, not yet trusted").isTrue();
        user(ANONYMOUS, "SPONSOR_EMAIL_VERIFY", "h2", "admin@example.com", "admin@example.com");
        assertThat(judged().own()).as("that address now gets reset links").isFalse();
        user(ANONYMOUS, "SPONSOR_PASSWORD_RESET_CONFIRM", "h3", "admin@example.com", "admin@example.com");
        Judgement judged = judged();
        assertThat(judged.own()).isFalse();
        assertThat(judged.history()).anySatisfy(line -> assertThat(line).contains("address changed to admin@example.com"));
    }

    /** Decision D63: an invitation's link is mailed like a reset's, and counts only where a reset's would. */
    @Test
    void anAcceptedInvitationIsAMailedLinkLikeAReset() {
        user(ADMIN, "SEC_USER_CREATE", "h1", "u@example.com", null);
        user(ANONYMOUS, "SPONSOR_INVITATION_ACCEPT", "h2", "u@example.com", "u@example.com");
        Judgement judged = judged();
        assertThat(judged.own()).isTrue();
        assertThat(judged.history()).last().asString().contains("(SPONSOR_INVITATION_ACCEPT): password changed, own");
        assertThat(judged.address()).contains("proven by mail");

        events.clear();
        user(ADMIN, "SEC_USER_CREATE", null, null, null);
        user(ADMIN, "SEC_USER_UPDATE", null, "admin@example.com", null);
        assertThat(judged().addressAsCreated()).as("added after creation").isFalse();
        user(ANONYMOUS, "SPONSOR_INVITATION_ACCEPT", "h2", "admin@example.com", "admin@example.com");
        assertThat(judged().own()).as("an address added after creation is someone else's").isFalse();
    }

    /** Where an invitation may go (D63): the address entered at creation, changed by nobody since, the user included. */
    @Test
    void anAddressIsAsCreatedUntilAnyoneChangesIt() {
        user(ADMIN, "SEC_USER_CREATE", "h1", "u@example.com", null);
        assertThat(judged().addressAsCreated()).isTrue();
        user(ADMIN, "SEC_USER_UNLOCK", "h1", "U@Example.com", null);
        assertThat(judged().addressAsCreated()).as("only the case differs").isTrue();
        // The administrator who set the password signs in as the user and changes the address.
        user(USER, "SEC_USER_UPDATE", "h1", "admin@example.com", null);
        assertThat(judged().addressChangedByOther()).isFalse();
        assertThat(judged().addressAsCreated()).isFalse();
        events.clear();
        user(ADMIN, "SEC_USER_CREATE", null, null, null);
        assertThat(judged().addressAsCreated()).as("no address").isFalse();
    }

    @Test
    void aFactorEnrolledWhileTheAccountWasNotCleanIsNotOwnUntilRemoved() {
        user(ADMIN, "SEC_USER_CREATE", "h1", "u@example.com", null);
        factor("m1", USER, "SEC_MFA_ENROLL_BEGIN", false);
        factor("m1", USER, "SEC_MFA_ENROLL_CONFIRM", false);
        user(ANONYMOUS, "SPONSOR_PASSWORD_RESET_CONFIRM", "h2", "u@example.com", "u@example.com");
        assertThat(judged().own()).as("the password is own now, the factor still not").isFalse();
        factor("m1", ADMIN, "SEC_PROTECTED_CHANGE_APPLY", true);
        assertThat(judged().own()).isTrue();
        // Enrolled again while clean: own, and a recovery code used later (the platform's own version) changes nothing.
        factor("m2", USER, "SEC_MFA_ENROLL_BEGIN", false);
        factor("m2", ANONYMOUS, "SPONSOR_MFA_VERIFY", false);
        assertThat(judged().own()).isTrue();
        assertThat(judged().history()).anySatisfy(line -> assertThat(line).contains("second factor removed"));
    }

    @Test
    void linksAreOwnWhenMadeByTheUserWhileClean() {
        user(ANONYMOUS, "SPONSOR_OIDC_SIGN_UP", null, "u@example.com", "u@example.com");
        link("i1", ANONYMOUS, "SPONSOR_OIDC_SIGN_UP", false);
        assertThat(judged().own()).isTrue();
        link("i2", USER, "SEC_IDENTITY_LINK_SELF", false);
        assertThat(judged().own()).isTrue();
        // One an administrator made is not; nor one the user makes while it exists.
        link("i3", ADMIN, "SEC_USER_IDENTITY_LINK", false);
        link("i4", USER, "SEC_IDENTITY_LINK_SELF", false);
        assertThat(judged().own()).isFalse();
        link("i3", USER, "SEC_IDENTITY_UNLINK_SELF", true);
        assertThat(judged().own()).as("i4 was made while i3 let someone else in").isFalse();
        link("i4", USER, "SEC_IDENTITY_UNLINK_SELF", true);
        assertThat(judged().own()).isTrue();
    }

    @Test
    void theOrderIsThatOfTheOperations() {
        // Written out of order: judged in the order of the operations, the user's own versions first in one.
        seq = 10;
        factor("m1", USER, "SEC_MFA_ENROLL_BEGIN", false);
        seq = 0;
        user(ADMIN, "SEC_USER_CREATE", "h1", null, null);
        assertThat(judged().own()).isFalse();
        assertThat(judged().history().getFirst()).contains("created");
    }

    private void versioned(long at, int versionNo, String actor, String process, String hash) {
        events.add(new Event(at, Source.USER, null, false, hash, "u@example.com", "u@example.com", null, actor, null,
            process, Instant.ofEpochSecond(at), versionNo, null));
    }

    private void factorVersion(String key, String actor, String process, String state) {
        seq++;
        events.add(new Event(seq, Source.MFA, key, false, null, null, null, null, actor, null, process,
            Instant.ofEpochSecond(seq), (int) seq, state));
    }

    /**
     * Review L-a (probe P3): restarting an enrolment writes a new secret on the same row. The administrator who knows
     * the password signs in as the user and begins again: that secret is theirs, whatever the first version was.
     */
    @Test
    void aSecondFactorIsJudgedAtItsLatestNewSecretNotItsFirstVersion() {
        user(ANONYMOUS, "SPONSOR_SIGN_UP", "h1", "u@example.com", null);
        user(ANONYMOUS, "SPONSOR_EMAIL_VERIFY", "h1", "u@example.com", "u@example.com");
        factorVersion("m1", USER, "SEC_MFA_ENROLL_BEGIN", "s1|false");
        user(ADMIN, "SEC_USER_SET_PASSWORD", "hA", "u@example.com", "u@example.com");
        factorVersion("m1", USER, "SEC_MFA_ENROLL_BEGIN", "s2|false");
        factorVersion("m1", USER, "SEC_MFA_ENROLL_CONFIRM", "s2|true");
        user(ANONYMOUS, "SPONSOR_PASSWORD_RESET_CONFIRM", "h3", "u@example.com", "u@example.com");
        Judgement judged = judged();
        assertThat(judged.own()).isFalse();
        assertThat(judged.history()).anySatisfy(line -> assertThat(line).contains("second factor set again, not own"));
        // A confirmation alone, while clean, does not clean a secret someone else saw.
        factorVersion("m1", USER, "SEC_MFA_ENROLL_CONFIRM", "s2|false");
        factorVersion("m1", USER, "SEC_MFA_ENROLL_CONFIRM", "s2|true");
        assertThat(judged().own()).isFalse();
        // Begun again by the user while clean: own; a recovery code used later (same secret) changes nothing.
        factorVersion("m1", USER, "SEC_MFA_ENROLL_BEGIN", "s3|false");
        factorVersion("m1", USER, "SEC_MFA_ENROLL_CONFIRM", "s3|true");
        factorVersion("m1", ANONYMOUS, "SPONSOR_MFA_VERIFY", "s3|true");
        assertThat(judged().own()).isTrue();
    }

    /**
     * Review L-b (probe P4): two transactions committed out of the order of their operations - the administrator's
     * password is the current version though its operation came first. Not judged own.
     */
    @Test
    void versionsOutOfTheOrderOfTheirOperationsAreNotOwn() {
        versioned(1, 1, ANONYMOUS, "SPONSOR_SIGN_UP", "h1");
        versioned(2, 2, ANONYMOUS, "SPONSOR_EMAIL_VERIFY", "h1");
        versioned(6, 3, ANONYMOUS, "SPONSOR_PASSWORD_RESET_CONFIRM", "hB");
        versioned(5, 4, ADMIN, "SEC_USER_SET_PASSWORD", "hA");
        Judgement judged = judged();
        assertThat(judged.own()).isFalse();
        assertThat(judged.clean()).isFalse();
        assertThat(judged.history().getLast()).contains("out of the order");
        // In order, the same history is judged as it reads.
        events.clear();
        versioned(1, 1, ANONYMOUS, "SPONSOR_SIGN_UP", "h1");
        versioned(5, 2, ADMIN, "SEC_USER_SET_PASSWORD", "hA");
        versioned(6, 3, ANONYMOUS, "SPONSOR_PASSWORD_RESET_CONFIRM", "hB");
        assertThat(judged().own()).isTrue();
    }

    /** Review M-a: who entered the current address, and whether a mailed link proved it. */
    @Test
    void theAddressIsDescribedWithWhoSetItAndItsProof() {
        user(ADMIN, "SEC_USER_CREATE", "h1", "u@example.com", null);
        assertThat(judged().address()).startsWith("u@example.com (entered by it-admin at ")
            .endsWith(", not proven by mail)");
        assertThat(judged().addressChangedByOther()).isFalse();
        user(ANONYMOUS, "SPONSOR_PASSWORD_RESET_CONFIRM", "h2", "u@example.com", "u@example.com");
        assertThat(judged().address()).contains("entered by it-admin").contains("proven by mail at");
        assertThat(judged().email()).isEqualTo("u@example.com");
        // Changed by another later (review P5): noted; the proof of the old address is gone.
        user(ADMIN, "SEC_USER_UPDATE", "h2", "other@example.org", "u@example.com");
        assertThat(judged().address()).startsWith("other@example.org (changed by it-admin at ")
            .endsWith("not proven by mail)");
        assertThat(judged().addressChangedByOther()).isTrue();
        assertThat(judged().clean()).as("the password is the user's: only they can sign in").isTrue();
        events.clear();
        seq = 0;
        user(ANONYMOUS, "SPONSOR_SIGN_UP", "h1", "u@example.com", null);
        assertThat(judged().address()).contains("registered by the user");
    }

    /** Review M-a (probe P2): the creation line, with its address, is kept however long the history grows. */
    @Test
    void aLongHistoryKeepsItsCreationAndItsLatestChanges() {
        user(ADMIN, "SEC_USER_CREATE", "h1", "bob.mallory@gmail.com", null);
        for (int i = 0; i < 60; i++) {
            link("k" + i, USER, "SEC_IDENTITY_LINK_SELF", false);
            link("k" + i, USER, "SEC_IDENTITY_UNLINK_SELF", true);
        }
        List<String> history = judged().history();
        String kept = AssessProtectedChange.keepEnds(history, 2000);
        assertThat(kept.length()).isLessThan(2100);
        assertThat(kept).startsWith(history.getFirst()).contains("bob.mallory@gmail.com")
            .contains("changes left out").endsWith(history.getLast());
        assertThat(AssessProtectedChange.keepEnds(history.subList(0, 3), 2000))
            .isEqualTo(String.join("; ", history.subList(0, 3)));
    }
}
