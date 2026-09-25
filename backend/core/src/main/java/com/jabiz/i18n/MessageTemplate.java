package com.jabiz.i18n;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills named placeholders such as {@code {min}} in a message. Unlike {@link java.text.MessageFormat}
 * the placeholders are names, matching rule parameters ({@code RuleSpec.params}), and apostrophes need no
 * escaping. Placeholders without a value are left as they are, so a missing parameter stays visible.
 */
public final class MessageTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private MessageTemplate() {}

    public static String format(String template, Map<String, ?> params) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = params.containsKey(name) ? String.valueOf(params.get(name)) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
