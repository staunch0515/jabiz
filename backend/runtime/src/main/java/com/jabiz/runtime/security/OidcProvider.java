package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One configured OpenID Connect provider ({@code jabiz.security.oidc.providers[i]}, docs/design/10-security.md
 * section 12). Checked as a whole at startup ({@link #problems}); a provider with problems is not offered.
 *
 * <p>The provider sends the user back to the callback page of the sign-in entry the sign-in started for (decision D43
 * item 5): {@code redirect-uris.<entry>}, the frontend's {@code login/oidc}; {@code redirect-uri} is the
 * administration's. An entry without a callback is not offered the provider.
 *
 * @param redirectUri  the administration's callback ({@code admin}), as before entries had their own
 * @param mfaAmr       values of the ID token's {@code amr} claim that count as a second factor; empty trusts none
 * @param redirectUris the callbacks of other entries (or the administration's), by entry name
 */
public record OidcProvider(String id, String issuer, String clientId, String clientSecret, String redirectUri,
    List<String> scopes, Map<String, String> labels, List<String> mfaAmr, Map<String, String> redirectUris) {

    public static final List<String> DEFAULT_SCOPES = List.of("openid", "profile", "email");

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern ENTRY = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    @ConstructorBinding
    public OidcProvider {
        scopes = scopes == null || scopes.isEmpty() ? DEFAULT_SCOPES : List.copyOf(scopes);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        mfaAmr = mfaAmr == null ? List.of() : List.copyOf(mfaAmr);
        redirectUris = redirectUris == null ? Map.of() : Map.copyOf(redirectUris);
    }

    /** A provider with the administration's callback only. */
    public OidcProvider(String id, String issuer, String clientId, String clientSecret, String redirectUri,
        List<String> scopes, Map<String, String> labels, List<String> mfaAmr) {
        this(id, issuer, clientId, clientSecret, redirectUri, scopes, labels, mfaAmr, null);
    }

    /**
     * The callback of the sign-in entry {@code entry} (none: the administration's), where the provider sends the user
     * back and which the code is exchanged with; null when this provider is not offered to that entry.
     */
    public String redirectUri(String entry) {
        String name = entry == null || entry.isBlank() ? RequestContext.DEFAULT_ENTRY : entry;
        String own = redirectUris.get(name);
        if (own != null && !own.isBlank()) {
            return own;
        }
        return RequestContext.DEFAULT_ENTRY.equals(name) && !blank(redirectUri) ? redirectUri : null;
    }

    /** Whether this provider is offered to the sign-in entry (it has a callback there). */
    public boolean offers(String entry) {
        return redirectUri(entry) != null;
    }

    /** What is wrong with the settings; empty when the provider can be used. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (id == null || !ID.matcher(id).matches()) {
            problems.add("id must be lower-case letters, digits and hyphens");
        }
        requireUrl(problems, "issuer", issuer);
        if (blank(redirectUri) && redirectUris.isEmpty()) {
            problems.add("redirect-uri is required (or redirect-uris.<entry> for the entries offered the provider)");
        } else if (!blank(redirectUri)) {
            requireCallback(problems, "redirect-uri", redirectUri);
        }
        new TreeMap<>(redirectUris).forEach((entry, uri) -> {
            if (!ENTRY.matcher(entry).matches()) {
                problems.add("redirect-uris: '" + entry + "' is not the name of a sign-in entry");
            }
            requireCallback(problems, "redirect-uris." + entry, uri);
        });
        String admin = redirectUris.get(RequestContext.DEFAULT_ENTRY);
        if (!blank(redirectUri) && admin != null && !admin.equals(redirectUri)) {
            problems.add("redirect-uri and redirect-uris." + RequestContext.DEFAULT_ENTRY + " are both the "
                + "administration's callback but differ: set one");
        }
        if (blank(clientId)) {
            problems.add("client-id is required");
        }
        if (blank(clientSecret)) {
            problems.add("client-secret is required (from the environment)");
        }
        if (!scopes.contains("openid")) {
            problems.add("scopes must include openid");
        }
        return problems;
    }

    /** The label in {@code language}, else English, else the id. */
    public String label(String language) {
        String label = labels.get(language);
        if (label == null) {
            label = labels.get("en");
        }
        return label == null ? id : label;
    }

    private static void requireUrl(List<String> problems, String name, String value) {
        if (blank(value)) {
            problems.add(name + " is required");
        } else if (!secureUrl(value)) {
            problems.add(name + " must be an absolute https URL (http only for localhost)");
        }
    }

    /** A callback: a secure URL without a fragment (RFC 6749 section 3.1.2). */
    private static void requireCallback(List<String> problems, String name, String value) {
        requireUrl(problems, name, value);
        if (!blank(value) && secureUrl(value) && URI.create(value).getRawFragment() != null) {
            problems.add(name + " must not have a fragment");
        }
    }

    /**
     * Whether a URL may carry OIDC traffic: https, or http to the local machine for development and tests. Endpoints
     * from the discovery document are held to the same rule.
     */
    public static boolean secureUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || !uri.isAbsolute()) {
                return false;
            }
            return scheme.equals("https")
                || (scheme.equals("http") && (host.equals("localhost") || host.equals("127.0.0.1")));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "OidcProvider[id=" + id + ", issuer=" + issuer + ", clientId=" + clientId + ", clientSecret=***]";
    }
}
