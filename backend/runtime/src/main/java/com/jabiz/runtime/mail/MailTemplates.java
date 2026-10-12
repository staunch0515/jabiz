package com.jabiz.runtime.mail;

import com.jabiz.mail.MailTemplate;
import com.jabiz.runtime.security.SignInEntries;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The declared mail templates ({@link MailTemplate} beans) by name, and the settings of template mail
 * (docs/design/18-numbering-approvals-tasks.md section 5.6): whether mail is sent at all ({@code jabiz.mail.enabled},
 * off by default: messages are recorded and skipped), the address of the application the links point to
 * ({@code jabiz.mail.base-url}), the roots of the sign-in entries' frontends ({@link #entryUrl}) and the page of the
 * unsubscribe link ({@code jabiz.mail.unsubscribe-path}).
 */
@Component
public class MailTemplates {

    private final ObjectProvider<MailTemplate> declared;
    private final boolean enabled;
    private final String baseUrl;
    private final String unsubscribePath;
    private final SignInEntries entries;
    private volatile Map<String, MailTemplate> byName;

    public MailTemplates(ObjectProvider<MailTemplate> declared, @Value("${jabiz.mail.enabled:false}") boolean enabled,
        @Value("${jabiz.mail.base-url:}") String baseUrl,
        @Value("${jabiz.mail.unsubscribe-path:/mail/unsubscribe}") String unsubscribePath, SignInEntries entries) {
        this.declared = declared;
        this.entries = entries;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.unsubscribePath = unsubscribePath;
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
}
