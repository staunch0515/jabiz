package com.jabiz.push;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A kind of push notification a process sends (decision D69), declared as a bean:
 * <pre>{@code
 * PushTemplate.define("commerce.order-shipped", t -> t.param("orderNo").entries("portal"))
 * }</pre>
 * Title and body are the messages {@code push.<name>.title} and {@code push.<name>.body} in every language of the
 * application, plain text with the parameters as {@code {name}}; the display name among the user's preferences is
 * {@code push.<name>.name}. A user turns each template off for push separately, among the templates of the sign-in
 * entry they are signed in at ({@link #entries}; all when none is named). Notifications go to users only, to every
 * device they registered, in their language.
 *
 * @param name    unique; letters, digits and {@code . _ : -} like event names, at most 100 characters
 * @param params  names of the values the sending process gives, in declaration order
 * @param entries the sign-in entries whose users the template is for; empty: every entry
 */
public record PushTemplate(String name, List<String> params, Set<String> entries) {

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    public static final Pattern PARAM = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
    private static final Pattern ENTRY = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    public PushTemplate {
        Objects.requireNonNull(name, "name must not be null");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a push template name: " + name);
        }
        params = List.copyOf(params == null ? List.of() : params);
        entries = Set.copyOf(entries == null ? Set.of() : entries);
        for (String param : params) {
            if (!PARAM.matcher(param).matches()) {
                throw new IllegalArgumentException("Not a parameter name of push " + name + ": " + param);
            }
        }
        if (new LinkedHashSet<>(params).size() != params.size()) {
            throw new IllegalArgumentException("Push " + name + " declares a parameter twice");
        }
        for (String entry : entries) {
            if (!ENTRY.matcher(entry).matches()) {
                throw new IllegalArgumentException("Not a sign-in entry of push " + name + ": " + entry);
            }
        }
    }

    public static PushTemplate define(String name, Consumer<Builder> spec) {
        Builder builder = new Builder();
        spec.accept(builder);
        return new PushTemplate(name, builder.params, new LinkedHashSet<>(builder.entries));
    }

    /** Whether the users of the sign-in entry receive it and see it among their preferences. */
    public boolean appliesTo(String entry) {
        return entries.isEmpty() || entries.contains(entry);
    }

    /** The message keys of the template. */
    public String titleKey() {
        return "push." + name + ".title";
    }

    public String bodyKey() {
        return "push." + name + ".body";
    }

    public String nameKey() {
        return "push." + name + ".name";
    }

    public static final class Builder {
        private final List<String> params = new ArrayList<>();
        private final List<String> entries = new ArrayList<>();

        public Builder param(String name) {
            params.add(name);
            return this;
        }

        /** The sign-in entries whose users it is for (none: every entry). */
        public Builder entries(String... names) {
            entries.addAll(List.of(names));
            return this;
        }
    }
}
