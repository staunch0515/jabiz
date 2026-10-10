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

    private MailPermissions() {}
}
