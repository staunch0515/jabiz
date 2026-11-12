package com.jabiz.quizbuks.sharedvideo;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;

/**
 * The mail of the shared videos (F-S12): subjects, bodies and names are the messages {@code mail.<name>.*} in the three
 * languages. Both are transactional (a takedown is not opted out of).
 */
public final class SharedVideoMails {

    /** To the provider: their shared video was taken down, with the reason; the publications using it end. */
    public static final MailTemplate TAKEN_DOWN = MailTemplate.define("qb.shared-video.taken-down", t -> t
        .category(MailCategory.TRANSACTIONAL).param("title", "reason")
            .sample("title", "Sample video")
            .sample("reason", "The video is no longer available."));

    /**
     * To a sponsor using it: their publication ended because the shared video it used was taken down (not their
     * breach). Sent by the publication's notice ({@code QB_MAIL_PUBLICATION}).
     */
    public static final MailTemplate PUBLICATION_ENDED = MailTemplate.define("qb.publication.shared-video-ended",
        t -> t.category(MailCategory.TRANSACTIONAL).param("name")
            .sample("name", "Spring quiz"));

    private SharedVideoMails() {
    }
}
