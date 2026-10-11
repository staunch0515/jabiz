package com.jabiz.quizbuks.identity;

import java.net.URI;

/**
 * The addresses QuizBuks's mail links to (D-Q2-7). The platform's {@code {baseUrl}} is the administration's
 * ({@code jabiz.mail.base-url}); the sponsor console is {@code quizbuks.links.sponsor}, or {@code /sponsor/} on the
 * host of {@code jabiz.mail.base-url} when that is not set.
 *
 * @param sponsor the sponsor console's address; empty when neither setting gives one (mail is then off)
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
