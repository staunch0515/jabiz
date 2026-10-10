package com.jabiz.mail;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whom a mail goes to (decision D35 items 2 and 4): a user, whose address and language the platform looks up unless
 * given, or an address with a language. Notifications go to users only, since unsubscribing is per user.
 *
 * @param userId  the user, or null for a bare address
 * @param address where to send; null: the user's e-mail address
 * @param locale  language of the mail; null: the user's, else the platform's default
 */
public record MailRecipient(String userId, String address, Locale locale) {

    /** A plausible single address: no spaces or line breaks, which could add headers. */
    public static final Pattern ADDRESS = Pattern.compile("[^@\\s]{1,64}@[^@\\s]+[.][^@\\s]+");

    public MailRecipient {
        if (userId == null && address == null) {
            throw new IllegalArgumentException("A mail goes to a user or an address");
        }
        if (address != null && (address.length() > 320 || !ADDRESS.matcher(address).matches())) {
            throw new IllegalArgumentException("Not an e-mail address: " + address);
        }
    }

    /** The user, at their address and in their language. */
    public static MailRecipient user(Object userId) {
        return new MailRecipient(String.valueOf(java.util.Objects.requireNonNull(userId, "userId")), null, null);
    }

    /** The user at a given address or in a given language (null: the user's). */
    public static MailRecipient user(Object userId, String address, Locale locale) {
        return new MailRecipient(String.valueOf(java.util.Objects.requireNonNull(userId, "userId")), address, locale);
    }

    /** An address that need not belong to a user. */
    public static MailRecipient address(String address, Locale locale) {
        return new MailRecipient(null, java.util.Objects.requireNonNull(address, "address"), locale);
    }
}
