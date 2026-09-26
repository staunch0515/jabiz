package com.jabiz.runtime.process.sponsor;

import com.jabiz.security.Sensitive;

/** Credentials submitted to the sign-in process. */
public record SponsorSignInInput(String userName, @Sensitive String password) {

    /** The password is never rendered, so the input can be logged safely. */
    @Override
    public String toString() {
        return "SponsorSignInInput[userName=" + userName + ", password=***]";
    }
}
