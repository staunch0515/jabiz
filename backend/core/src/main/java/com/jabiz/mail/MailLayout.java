package com.jabiz.mail;

import java.util.Objects;

/**
 * The letterhead every mail of a language is sent on (docs/design/18-numbering-approvals-tasks.md section 5.6;
 * decision D72 item 1): a header with the sender's display name and an optional logo, and a Markdown footer (company,
 * address, support). All parts may be empty; a layout with nothing in it leaves the mail as it is. Checked by
 * {@link MailLayoutRules}, put around a rendered mail by {@link MailRenderer#wrap}.
 *
 * @param displayName the name in the header, plain text on one line
 * @param logoUrl     the logo's address: {@code {baseUrl}/…} or a path of the application ({@code /api/public/files/…});
 *                    empty for none
 * @param logoAlt     the logo's alternative text, plain text on one line; required with a logo
 * @param footer      Markdown under the mail; may use {@code {baseUrl}} and {@code {entryUrl.<entry>}}
 */
public record MailLayout(String displayName, String logoUrl, String logoAlt, String footer) {

    /** The layout of nothing: mail as it was before layouts. */
    public static final MailLayout EMPTY = new MailLayout("", "", "", "");

    public MailLayout {
        displayName = Objects.requireNonNullElse(displayName, "");
        logoUrl = Objects.requireNonNullElse(logoUrl, "");
        logoAlt = Objects.requireNonNullElse(logoAlt, "");
        footer = Objects.requireNonNullElse(footer, "");
    }

    /** Whether nothing would be added to a mail. */
    public boolean isEmpty() {
        return displayName.isBlank() && logoUrl.isBlank() && footer.isBlank();
    }

    /** SHA-256 (hex) of the four parts, as recorded with an override: the default it was written against. */
    public String hash() {
        return MailTextRules.hash(displayName + "\n" + logoUrl + "\n" + logoAlt, footer);
    }
}
