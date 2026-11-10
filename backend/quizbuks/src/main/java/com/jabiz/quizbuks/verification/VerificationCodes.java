package com.jabiz.quizbuks.verification;

import java.util.List;

/** The error codes of the company verification (F-M3); each has a message in every language. */
public final class VerificationCodes {

    /** Only an approved Sponsor verifies their company. */
    public static final String NOT_APPROVED = "QB_VERIFICATION_NOT_APPROVED";
    /** The domain is not a host name of at least two labels ({@code domain}). */
    public static final String DOMAIN_FORMAT = "QB_VERIFICATION_DOMAIN_FORMAT";
    /** A public mail service's domain proves nothing about a company ({@code domain}). */
    public static final String PUBLIC_DOMAIN = "QB_VERIFICATION_PUBLIC_DOMAIN";
    /** The domain is verified for another Sponsor (who, it does not say). */
    public static final String DOMAIN_TAKEN = "QB_VERIFICATION_DOMAIN_TAKEN";
    /** The account's address is not on the domain, and no address on it was given for the link. */
    public static final String ADDRESS_REQUIRED = "QB_VERIFICATION_ADDRESS_REQUIRED";
    /** The address given for the link is not on the domain ({@code domain}). */
    public static final String ADDRESS_DOMAIN = "QB_VERIFICATION_ADDRESS_DOMAIN";
    /** The link is not good: used, expired, replaced by a later one, another account's, or the submission moved on. */
    public static final String LINK_INVALID = "QB_VERIFICATION_LINK_INVALID";
    /** The submission is not in a state that allows this ({@code status}). */
    public static final String STATE = "QB_VERIFICATION_STATE";
    /** The Sponsor has no address to mail the link from: the platform's mail is off. */
    public static final String MAIL_OFF = "QB_VERIFICATION_MAIL_OFF";

    public static final List<String> ALL = List.of(NOT_APPROVED, DOMAIN_FORMAT, PUBLIC_DOMAIN, DOMAIN_TAKEN,
        ADDRESS_REQUIRED, ADDRESS_DOMAIN, LINK_INVALID, STATE, MAIL_OFF);

    private VerificationCodes() {
    }
}
