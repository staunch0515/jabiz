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
}
