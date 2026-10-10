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
 * parameters, the one-time tokens (drawn when the mail is sent, never stored as such), {@value #BASE_URL} and, for
 * {@link MailCategory#NOTIFICATION}, {@value #UNSUBSCRIBE_URL}.
 *
 * @param name     unique; letters, digits and {@code . _ : -} like event names, at most 100 characters
 * @param category whether recipients may unsubscribe
 * @param params   names of the values the sending process gives, in declaration order
 * @param tokens   one-time tokens by purpose (also their placeholder), with how long each is valid
 */
public record MailTemplate(String name, MailCategory category, List<String> params, Map<String, Duration> tokens) {

    /** Names of templates: as event names. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    /** Names of parameters and token purposes: an identifier without underscores, which Markdown might take up. */
    public static final Pattern PARAM = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
    /** The address of the application ({@code jabiz.mail.base-url}). */
    public static final String BASE_URL = "baseUrl";
    /** The signed link that turns this template off for the recipient (notifications only). */
    public static final String UNSUBSCRIBE_URL = "unsubscribeUrl";

    public MailTemplate {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Mail template name '" + name + "' must match " + NAME.pattern());
        }
        Objects.requireNonNull(category, "Mail template " + name + " needs a category");
        params = List.copyOf(params);
        tokens = Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
        Set<String> seen = new LinkedHashSet<>();
        for (String param : params) {
            requireName(name, "parameter", param);
            if (!seen.add(param)) {
                throw new IllegalArgumentException("Mail template " + name + " declares " + param + " twice");
            }
        }
        for (Map.Entry<String, Duration> token : tokens.entrySet()) {
            requireName(name, "token purpose", token.getKey());
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

    private static void requireName(String template, String what, String value) {
        if (value == null || !PARAM.matcher(value).matches()) {
            throw new IllegalArgumentException("The " + what + " '" + value + "' of mail template " + template
                + " must match " + PARAM.pattern());
        }
        if (BASE_URL.equals(value) || UNSUBSCRIBE_URL.equals(value)) {
            throw new IllegalArgumentException("The " + what + " '" + value + "' of mail template " + template
                + " is a placeholder of the platform");
        }
    }

    public static MailTemplate define(String name, Consumer<Builder> spec) {
        Builder builder = new Builder();
        Objects.requireNonNull(spec, "spec must not be null").accept(builder);
        return new MailTemplate(name, builder.category, builder.params, builder.tokens);
    }

    /** Message code of the subject. */
    public String subjectKey() {
        return "mail." + name + ".subject";
    }

    /** Message code of the Markdown body. */
    public String bodyKey() {
        return "mail." + name + ".body";
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
        if (unsubscribable()) {
            names.add(UNSUBSCRIBE_URL);
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * Placeholders whose values the platform makes and that are inserted into links as they are: the address of the
     * application, the unsubscribe link and the tokens (URL-safe Base64). Parameters are percent-encoded there.
     */
    public Set<String> urlSafePlaceholders() {
        Set<String> names = new LinkedHashSet<>(tokens.keySet());
        names.add(BASE_URL);
        names.add(UNSUBSCRIBE_URL);
        return Collections.unmodifiableSet(names);
    }

    /** Declaration of a template. */
    public static final class Builder {

        private MailCategory category;
        private final List<String> params = new ArrayList<>();
        private final Map<String, Duration> tokens = new LinkedHashMap<>();

        private Builder() {}

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
