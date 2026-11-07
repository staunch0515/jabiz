package com.jabiz.mail;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The placeholders of a template's texts (decision D35 item 1). Whether they agree with the template's declaration is
 * one of the {@link MailTextRules}, which the startup check and the texts administrators write share (decision D72).
 */
public final class MailPlaceholders {

    /**
     * As {@code MessageTemplate}: {@code {name}}; token placeholders may carry a namespace ({@code {jabiz.verify}}), and
     * {@code {entryUrl.<entry>}} names an entry, whose names may have hyphens.
     */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*)?)}");

    private MailPlaceholders() {}

    /**
     * Fills the placeholders of a plain text in one pass: a value is not searched for placeholders again, and a
     * placeholder without a value stays as it is.
     */
    public static String fill(String text, Map<String, String> values) {
        return MailRenderer.fill(text, values == null ? Map.of() : values, false, Set.of());
    }

    /** The placeholder names of a text, in order of first use. */
    public static Set<String> of(String text) {
        Set<String> names = new LinkedHashSet<>();
        if (text != null) {
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }
}
