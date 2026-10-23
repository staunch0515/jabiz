package com.jabiz.runtime.process.sponsor;

/**
 * A user whose mailbox a sign-in link proved (docs/design/10-security.md section 15.11; decision D54): the platform
 * used the link and found or opened the account before this sign-in runs.
 *
 * @param userId the user the link stands for
 * @param source the link's entry and where the request comes from
 */
public record SponsorEmailLinkSignInInput(String userId, SignInSource source) {

    public SponsorEmailLinkSignInInput {
        source = source == null ? SignInSource.NONE : source;
    }
}
