package com.jabiz.runtime.mail;

import com.jabiz.mail.MailTemplate;
import com.jabiz.runtime.security.SignInEntries;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The declared mail templates ({@link MailTemplate} beans) by name, and the settings of template mail
 * (docs/design/18-numbering-approvals-tasks.md section 5.6): whether mail is sent at all ({@code jabiz.mail.enabled},
 * off by default: messages are recorded and skipped), the address of the application the links point to
 * ({@code jabiz.mail.base-url}), the roots of the sign-in entries' frontends ({@link #entryUrl}) and the page of the
 * unsubscribe link: {@code jabiz.mail.unsubscribe-path} of the application, or, for a template declared for sign-in
 * entries, {@code jabiz.mail.unsubscribe-paths.<entry>} under that entry's frontend (decision D52 item 3).
 */
@Component
public class MailTemplates {

    private final ObjectProvider<MailTemplate> declared;
    private final boolean enabled;
    private final String baseUrl;
    private final String unsubscribePath;
    private final SignInEntries entries;
    private final Map<String, String> unsubscribePaths;
    private volatile Map<String, MailTemplate> byName;

    /** The configuration of the unsubscribe pages of entries: {@code jabiz.mail.unsubscribe-paths.<entry>}. */
    public static final String UNSUBSCRIBE_PATHS = "jabiz.mail.unsubscribe-paths";

    /** A path relative to an entry's {@code app-path}: segments of unreserved URL characters, no {@code ..}. */
    private static final Pattern RELATIVE_PATH = Pattern.compile("([A-Za-z0-9_~-][A-Za-z0-9._~-]*/?)+");

    /** Templates as before decision D52: every unsubscribe link leads to {@code jabiz.mail.unsubscribe-path}. */
    public MailTemplates(ObjectProvider<MailTemplate> declared, boolean enabled, String baseUrl,
        String unsubscribePath, SignInEntries entries) {
        this(declared, enabled, baseUrl, unsubscribePath, entries, Map.of());
    }

    @Autowired
    public MailTemplates(ObjectProvider<MailTemplate> declared, @Value("${jabiz.mail.enabled:false}") boolean enabled,
        @Value("${jabiz.mail.base-url:}") String baseUrl,
        @Value("${jabiz.mail.unsubscribe-path:/mail/unsubscribe}") String unsubscribePath, SignInEntries entries,
        Environment environment) {
        this(declared, enabled, baseUrl, unsubscribePath, entries, Binder.get(environment)
            .bind(UNSUBSCRIBE_PATHS, Bindable.mapOf(String.class, String.class)).orElse(Map.of()));
    }

    /**
     * @param unsubscribePaths the page of the unsubscribe link of each sign-in entry that has one, relative to the
     *                         entry's {@code app-path} (decision D52 item 3); checked at startup ({@link MailChecks})
     */
    public MailTemplates(ObjectProvider<MailTemplate> declared, boolean enabled, String baseUrl,
        String unsubscribePath, SignInEntries entries, Map<String, String> unsubscribePaths) {
        this.declared = declared;
        this.entries = entries;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.unsubscribePath = unsubscribePath;
        Map<String, String> paths = new TreeMap<>();
        (unsubscribePaths == null ? Map.<String, String>of() : unsubscribePaths).forEach((entry, path) ->
            paths.put(entry, path == null ? "" : path.strip()));
        this.unsubscribePaths = Map.copyOf(paths);
    }

    /** Every declared template, duplicates included (the startup check reports them). */
    public List<MailTemplate> all() {
        return declared.orderedStream().toList();
    }

    /** The template of this name; the first one if declared twice. */
    public Optional<MailTemplate> find(String name) {
        Map<String, MailTemplate> known = byName;
        if (known == null) {
            Map<String, MailTemplate> index = new LinkedHashMap<>();
            all().forEach(template -> index.putIfAbsent(template.name(), template));
            byName = known = Map.copyOf(index);
        }
        return Optional.ofNullable(known.get(name));
    }

    public boolean enabled() {
        return enabled;
    }

    /** {@code jabiz.mail.base-url} without a trailing slash; empty when not configured. */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * The root of the frontend of the sign-in entry {@code entry} (decision D43 item 4): {@link #baseUrl} and the
     * entry's {@code app-path}, always ending in {@code /}. The path is configuration (checked as a path at startup),
     * never the entry's name or anything else from a request; an unknown entry gets the root of the application, whose
     * pages accept the links of every entry.
     */
    public String entryUrl(String entry) {
        String path = entries.resolve(entry).appPath();
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            path = "/";
        }
        return baseUrl + (path.endsWith("/") ? path : path + "/");
    }

    /** The unsubscribe link carrying {@code token}: the page of the application that posts it to the API. */
    public String unsubscribeUrl(String token) {
        return baseUrl + unsubscribePath + "?token=" + token;
    }

    /**
     * The unsubscribe link of a mail of {@code template} carrying {@code token} (decision D52 item 3): a template
     * declared for sign-in entries links to the unsubscribe page of the first of them that has one
     * ({@code jabiz.mail.unsubscribe-paths.<entry>}, under the entry's {@link #entryUrl}); any other to the
     * application's ({@link #unsubscribeUrl(String)}). Both pages post the same token to the same endpoint, which does
     * not depend on the entry (decision D49 item 1). Only configuration makes the link: never a request's value.
     */
    public String unsubscribeUrl(String token, MailTemplate template) {
        return unsubscribePage(template).map(page -> page + "?token=" + token).orElseGet(() -> unsubscribeUrl(token));
    }

    /** The entry page {@link #unsubscribeUrl(String, MailTemplate)} links to, without the token; empty for none. */
    Optional<String> unsubscribePage(MailTemplate template) {
        if (template == null) {
            return Optional.empty();
        }
        for (String entry : template.entries()) {
            String path = unsubscribePaths.get(entry);
            if (path != null && validRelativePath(path)) {
                return Optional.of(entryUrl(entry) + relative(path));
            }
        }
        return Optional.empty();
    }

    /** {@code jabiz.mail.unsubscribe-paths}, by entry, as configured (blanks stripped). */
    public Map<String, String> unsubscribePaths() {
        return unsubscribePaths;
    }

    /** Whether {@code path} is a path relative to an entry's {@code app-path}, a leading slash allowed. */
    static boolean validRelativePath(String path) {
        String relative = relative(path);
        return !relative.isEmpty() && RELATIVE_PATH.matcher(relative).matches()
            && java.util.Arrays.stream(relative.split("/")).noneMatch(segment -> segment.equals(".")
                || segment.equals(".."));
    }

    /** The path without its one leading slash, if any: it continues the entry's root, which ends in one. */
    static String relative(String path) {
        String stripped = path == null ? "" : path.strip();
        return stripped.startsWith("/") ? stripped.substring(1) : stripped;
    }
}
