package com.jabiz.mail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A kind of e-mail a process sends (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D35), declared
 * as a bean:
 * <pre>{@code
 * MailTemplate.define("commerce.order-shipped", t -> t.category(MailCategory.NOTIFICATION).param("orderNo"))
 * }</pre>
 * Subject and body are messages {@code mail.<name>.subject} and {@code mail.<name>.body} in every language of the
 * application; the body is Markdown. Both may use the placeholders of {@link #placeholders()}: the declared
 * parameters, the one-time tokens (drawn when the mail is sent, never stored as such), {@value #BASE_URL}, the root of
 * a sign-in entry's frontend ({@value #ENTRY_URL} for the entry the parameter {@value #ENTRY_PARAM} names,
 * {@code {entryUrl.<entry>}} for a named one; decision D43 item 4) and, for {@link MailCategory#NOTIFICATION},
 * {@value #UNSUBSCRIBE_URL}.
 * <p>
 * A notification's recipients choose whether they receive it, among the templates of the sign-in entry they are
 * signed in at ({@link #entries}; all when none is named), under its display name, the message
 * {@code mail.<name>.name} (phase 16s; decision D49).
 *
 * @param name     unique; letters, digits and {@code . _ : -} like event names, at most 100 characters
 * @param category whether recipients may unsubscribe
 * @param params   names of the values the sending process gives, in declaration order
 * @param tokens   one-time tokens by purpose (also their placeholder), with how long each is valid
 * @param entries  the sign-in entries whose users the template is for, which list it among their mail preferences;
 *                 empty: every entry (decision D49 item 1)
 */
public record MailTemplate(String name, MailCategory category, List<String> params, Map<String, Duration> tokens,
    Set<String> entries) {

    /** Names of templates: as event names. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    /** Names of parameters and token purposes: an identifier without underscores, which Markdown might take up. */
    public static final Pattern PARAM = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
    /**
     * Purposes of tokens (also their placeholders): as parameters, optionally with a namespace such as the platform's
     * {@value #PLATFORM_PREFIX} ({@code jabiz.verify}), so that a token of one template is never taken for another's.
     */
    public static final Pattern TOKEN_PURPOSE = Pattern.compile("(?:[a-z][a-z0-9]{0,19}\\.)?[a-z][A-Za-z0-9]{0,39}");
    /** Template names and token purposes of the platform's own mail; applications do not use it. */
    public static final String PLATFORM_PREFIX = "jabiz.";
    /** The address of the application ({@code jabiz.mail.base-url}). */
    public static final String BASE_URL = "baseUrl";
    /** The signed link that turns this template off for the recipient (notifications only). */
    public static final String UNSUBSCRIBE_URL = "unsubscribeUrl";
    /**
     * The root of the frontend of the sign-in entry named by the parameter {@value #ENTRY_PARAM}: {@value #BASE_URL}
     * and the entry's {@code app-path}, ending in {@code /} (decision D43 item 4). Only for templates declaring that
     * parameter.
     */
    public static final String ENTRY_URL = "entryUrl";
    /** The parameter naming the sign-in entry whose frontend {@value #ENTRY_URL} is the root of. */
    public static final String ENTRY_PARAM = "entry";
    /** {@code {entryUrl.<entry>}}: the root of a named entry's frontend; the entry must exist (startup check). */
    public static final String NAMED_ENTRY_URL_PREFIX = ENTRY_URL + ".";
    /** Names of sign-in entries, as {@code jabiz.security.entries.<name>}. */
    public static final Pattern ENTRY_NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    public MailTemplate {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Mail template name '" + name + "' must match " + NAME.pattern());
        }
        Objects.requireNonNull(category, "Mail template " + name + " needs a category");
        params = List.copyOf(params);
        tokens = Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
        entries = Collections.unmodifiableSet(new LinkedHashSet<>(entries == null ? Set.of() : entries));
        for (String entry : entries) {
            if (entry == null || !ENTRY_NAME.matcher(entry).matches()) {
                throw new IllegalArgumentException("The sign-in entry '" + entry + "' of mail template " + name
                    + " must match " + ENTRY_NAME.pattern());
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String param : params) {
            requireName(name, "parameter", param, PARAM);
            if (!seen.add(param)) {
                throw new IllegalArgumentException("Mail template " + name + " declares " + param + " twice");
            }
        }
        for (Map.Entry<String, Duration> token : tokens.entrySet()) {
            requireName(name, "token purpose", token.getKey(), TOKEN_PURPOSE);
            if (!seen.add(token.getKey())) {
                throw new IllegalArgumentException("Mail template " + name + " declares " + token.getKey()
                    + " twice");
            }
            Duration validity = token.getValue();
            if (validity == null || validity.isZero() || validity.isNegative()) {
                throw new IllegalArgumentException("Token " + token.getKey() + " of mail template " + name
                    + " needs a positive validity");
            }
        }
    }

    /** A template for the users of every sign-in entry (as before phase 16s). */
    public MailTemplate(String name, MailCategory category, List<String> params, Map<String, Duration> tokens) {
        this(name, category, params, tokens, Set.of());
    }

    private static void requireName(String template, String what, String value, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("The " + what + " '" + value + "' of mail template " + template
                + " must match " + pattern.pattern());
        }
        if (BASE_URL.equals(value) || UNSUBSCRIBE_URL.equals(value) || ENTRY_URL.equals(value)) {
            throw new IllegalArgumentException("The " + what + " '" + value + "' of mail template " + template
                + " is a placeholder of the platform");
        }
    }

    public static MailTemplate define(String name, Consumer<Builder> spec) {
        Builder builder = new Builder();
        Objects.requireNonNull(spec, "spec must not be null").accept(builder);
        return new MailTemplate(name, builder.category, builder.params, builder.tokens, builder.entries);
    }

    /** Message code of the subject. */
    public String subjectKey() {
        return "mail." + name + ".subject";
    }

    /** Message code of the Markdown body. */
    public String bodyKey() {
        return "mail." + name + ".body";
    }

    /** Message code of the name recipients know it by among their mail preferences (decision D49 item 2). */
    public String nameKey() {
        return "mail." + name + ".name";
    }

    /**
     * Whether the template is for users signed in at {@code entry} (null or blank: the administration's, as
     * everywhere entries are named): every entry when it names none.
     */
    public boolean appliesTo(String entry) {
        return entries.isEmpty() || entries.contains(entry == null || entry.isBlank()
            ? com.jabiz.context.RequestContext.DEFAULT_ENTRY : entry);
    }

    /** Whether the recipient may turn it off. */
    public boolean unsubscribable() {
        return category == MailCategory.NOTIFICATION;
    }

    /** Every placeholder subject and body may use. */
    public Set<String> placeholders() {
        Set<String> names = new LinkedHashSet<>(params);
        names.addAll(tokens.keySet());
        names.add(BASE_URL);
        if (params.contains(ENTRY_PARAM)) {
            names.add(ENTRY_URL);
        }
        if (unsubscribable()) {
            names.add(UNSUBSCRIBE_URL);
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * Placeholders whose values the platform makes and that are inserted into links as they are: the address of the
     * application, the roots of the entries' frontends (also every {@code {entryUrl.<entry>}}, see
     * {@link #namedEntryUrl}), the unsubscribe link and the tokens (URL-safe Base64). Parameters are percent-encoded
     * there.
     */
    public Set<String> urlSafePlaceholders() {
        Set<String> names = new LinkedHashSet<>(tokens.keySet());
        names.add(BASE_URL);
        names.add(ENTRY_URL);
        names.add(UNSUBSCRIBE_URL);
        return Collections.unmodifiableSet(names);
    }

    /**
     * Whether a placeholder is {@code {entryUrl.<entry>}} with a well-formed entry name. Its value is made by the
     * platform from configuration (never from a parameter, whose names have no dot), so it goes into links as it is.
     */
    public static boolean namedEntryUrl(String placeholder) {
        return placeholder != null && placeholder.startsWith(NAMED_ENTRY_URL_PREFIX)
            && ENTRY_NAME.matcher(placeholder.substring(NAMED_ENTRY_URL_PREFIX.length())).matches();
    }

    /** The entry a {@link #namedEntryUrl} placeholder names. */
    public static String entryOf(String namedEntryUrl) {
        if (!namedEntryUrl(namedEntryUrl)) {
            throw new IllegalArgumentException("Not an {entryUrl.<entry>} placeholder: " + namedEntryUrl);
        }
        return namedEntryUrl.substring(NAMED_ENTRY_URL_PREFIX.length());
    }

    /** Declaration of a template. */
    public static final class Builder {

        private MailCategory category;
        private final List<String> params = new ArrayList<>();
        private final Map<String, Duration> tokens = new LinkedHashMap<>();
        private final Set<String> entries = new LinkedHashSet<>();

        private Builder() {}

        /**
         * The sign-in entries ({@code jabiz.security.entries.<name>}) whose users the template is for: a notification
         * is listed among the mail preferences of users signed in there only. Not declared: every entry. The entries
         * must exist (startup check {@code MAIL}).
         */
        public Builder entries(String... names) {
            if (names.length == 0) {
                throw new IllegalArgumentException("entries() names no sign-in entry; leave it out for every entry");
            }
            entries.addAll(List.of(names));
            return this;
        }

        public Builder category(MailCategory category) {
            this.category = category;
            return this;
        }

        public Builder param(String... names) {
            params.addAll(List.of(names));
            return this;
        }

        /**
         * A one-time token for {@code purpose}, valid for {@code validity} from sending; its placeholder is the
         * purpose. Only its SHA-256 is stored; using it is {@code MailTokens.consume} in a process.
         */
        public Builder token(String purpose, Duration validity) {
            if (tokens.putIfAbsent(purpose, validity) != null) {
                throw new IllegalArgumentException("Token purpose " + purpose + " declared twice");
            }
            return this;
        }
    }
}
