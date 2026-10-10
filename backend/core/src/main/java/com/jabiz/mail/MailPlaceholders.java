package com.jabiz.mail;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The placeholders of a template's texts against its declaration (decision D35 item 1): a placeholder that is not
 * declared would stay visible in the mail, a declared parameter or token that is not used is a mistake (a token drawn
 * for nothing), and a notification must offer its unsubscribe link.
 */
public final class MailPlaceholders {

    /** As {@code MessageTemplate}: {@code {name}}. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private MailPlaceholders() {}

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

    /** Problems of one language's subject and body; empty when they agree with the declaration. */
    public static List<String> problems(MailTemplate template, String subject, String body) {
        List<String> problems = new ArrayList<>();
        Set<String> allowed = template.placeholders();
        Set<String> used = new LinkedHashSet<>(of(subject));
        used.addAll(of(body));
        for (String name : used) {
            if (!allowed.contains(name)) {
                problems.add("uses {" + name + "}, which the template does not declare");
            }
        }
        List<String> declared = new ArrayList<>(template.params());
        declared.addAll(template.tokens().keySet());
        for (String name : declared) {
            if (!used.contains(name)) {
                problems.add("does not use the declared {" + name + "}");
            }
        }
        if (template.unsubscribable() && !of(body).contains(MailTemplate.UNSUBSCRIBE_URL)) {
            problems.add("is a notification but its body has no {" + MailTemplate.UNSUBSCRIBE_URL + "}");
        }
        return problems;
    }
}
