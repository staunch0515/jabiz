package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.security.SecurityEntities;

/** Context of {@link SponsorEmailLinkSignInProcess}: the sign-in of the user a mailed link stands for. */
public class EmailLinkContext extends LoginContext {

    private final SponsorEmailLinkSignInInput input;

    public EmailLinkContext(ProcessStart start, SponsorEmailLinkSignInInput input) {
        super(start, null, null, input.source());
        this.input = input;
        setFactor(SecurityEntities.FACTOR_EMAIL_LINK);
    }

    public SponsorEmailLinkSignInInput input() {
        return input;
    }
}
