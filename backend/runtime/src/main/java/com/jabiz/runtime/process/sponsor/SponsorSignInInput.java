package com.jabiz.runtime.process.sponsor;

import com.jabiz.security.Sensitive;

/**
 * Credentials submitted to the sign-in process.
 *
 * @param userName the user name, or a verified e-mail address (decision D36 item 4)
 * @param source   the entry the sign-in is for and where it comes from (decision D36)
 */
public record SponsorSignInInput(String userName, @Sensitive String password, SignInSource source) {

    public SponsorSignInInput {
        source = source == null ? SignInSource.NONE : source;
    }

    /** A sign-in to the administration, from nowhere in particular. */
    public SponsorSignInInput(String userName, String password) {
        this(userName, password, SignInSource.NONE);
    }

    /** The password is never rendered, so the input can be logged safely. */
    @Override
    public String toString() {
        return "SponsorSignInInput[userName=" + userName + ", password=***, entry=" + source.entryOrDefault() + "]";
    }
}
