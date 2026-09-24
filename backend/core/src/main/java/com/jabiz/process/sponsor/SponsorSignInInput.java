package com.jabiz.process.sponsor;

/** Credentials submitted to the sponsor sign-in process. */
public record SponsorSignInInput(String usernameOrEmail, String password) {

    /** The password is never rendered, so the input can be logged safely. */
    @Override
    public String toString() {
        return "SponsorSignInInput[usernameOrEmail=" + usernameOrEmail + ", password=***]";
    }
}
