package com.jabiz.quizbuks.identity;

import java.net.URI;

/**
 * The sponsor console's absolute address (D-Q2-7), where Stripe sends a sponsor back from Checkout (Q6a):
 * {@code quizbuks.links.sponsor}, or {@code /sponsor/} on the host of {@code jabiz.mail.base-url} when that is not set.
 * Mail no longer uses it: the sponsor mails link to {@code {entryUrl.sponsor}} (docs/quizbuks/plans/QP-pages.md, A-2).
 *
 * @param sponsor the sponsor console's address; empty when neither setting gives one (top-ups are then refused)
 */
public record AccountLinks(String sponsor) {

    public AccountLinks {
        sponsor = sponsor == null ? "" : sponsor.strip();
    }

    /** The links from the two settings (either may be blank). */
    public static AccountLinks of(String sponsorLink, String mailBaseUrl) {
        if (sponsorLink != null && !sponsorLink.isBlank()) {
            return new AccountLinks(sponsorLink);
        }
        if (mailBaseUrl == null || mailBaseUrl.isBlank()) {
            return new AccountLinks("");
        }
        try {
            URI base = URI.create(mailBaseUrl.strip());
            if (base.getScheme() == null || base.getRawAuthority() == null) {
                return new AccountLinks("");
            }
            return new AccountLinks(base.getScheme() + "://" + base.getRawAuthority() + "/sponsor/");
        } catch (IllegalArgumentException e) {
            return new AccountLinks("");
        }
    }
}
