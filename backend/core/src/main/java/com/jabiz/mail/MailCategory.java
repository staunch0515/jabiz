package com.jabiz.mail;

/** Whether recipients may turn a mail off (decision D35 item 1). */
public enum MailCategory {
    /** Mail a user needs whatever they chose: verification, password reset, security notices. Never unsubscribed. */
    TRANSACTIONAL,
    /** Mail a user may turn off, per template; its body carries a signed unsubscribe link. */
    NOTIFICATION
}
