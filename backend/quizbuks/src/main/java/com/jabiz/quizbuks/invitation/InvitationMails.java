package com.jabiz.quizbuks.invitation;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;

import java.time.Duration;

/**
 * The invitation mail (Q25; platform decision D55): one per address, to an address that need not be a user's, with a
 * one-time sign-in link to the publication's page in the app ({@code signInLink}: the platform issues the token when
 * the mail is sent and signs the invitee in at the entry {@code app}, verifying the address) and the platform's
 * address-level unsubscribe link. Its texts are the messages {@code mail.qb.invitation.*}.
 */
public final class InvitationMails {

    /** How long the mailed link signs in: the mail may wait in an inbox for a while. */
    public static final Duration LINK_VALIDITY = Duration.ofDays(30);

    public static final MailTemplate INVITATION = MailTemplate.define("qb.invitation", t -> t
        .category(MailCategory.NOTIFICATION).entries("app")
        .param("company", "publicationName", "quizTitle", "maxReward", "endAt")
        .signInLink(LINK_VALIDITY));

    private InvitationMails() {}
}
