package com.jabiz.runtime.mail;

/** Permission codes of template mail (docs/design/18-numbering-approvals-tasks.md section 5.6). */
public final class MailPermissions {

    /** Read the queued messages and their attempts through their datasets. */
    public static final String READ = "mail.read";
    /** The declared write permission of the mail datasets, which only processes write. */
    public static final String WRITE = "mail.write";
    /** Run {@code MAIL_SEND}; the event consumer runs it as the system. */
    public static final String SEND = "mail.send";
    /**
     * Declared by {@code SEC_MAIL_PREFERENCE_SET}, which runs only through the account settings and the unsubscribe
     * link, as the user concerned; granted to no role.
     */
    public static final String PREFERENCE = "auth.mail-preference";

    /** Read the mail texts, their defaults and previews (decision D72). */
    public static final String TEXT_READ = "mail.text.read";
    /**
     * Change the mail texts ({@code MAIL_TEXT_SAVE}, {@code MAIL_TEXT_RESET}) and send oneself a test mail; the texts
     * of mail with a token, sign-in link or protected text change only through a controlled change (decision D72).
     */
    public static final String TEXT_WRITE = "mail.text.write";

    private MailPermissions() {}
}
