package com.jabiz.runtime.security;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One configured OpenID Connect provider ({@code jabiz.security.oidc.providers[i]}, docs/design/10-security.md
 * section 12). Checked as a whole at startup ({@link #problems}); a provider with problems is not offered.
 *
 * @param mfaAmr values of the ID token's {@code amr} claim that count as a second factor; empty trusts none
 */
public record OidcProvider(String id, String issuer, String clientId, String clientSecret, String redirectUri,
    List<String> scopes, Map<String, String> labels, List<String> mfaAmr) {

    public static final List<String> DEFAULT_SCOPES = List.of("openid", "profile", "email");

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    public OidcProvider {
        scopes = scopes == null || scopes.isEmpty() ? DEFAULT_SCOPES : List.copyOf(scopes);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        mfaAmr = mfaAmr == null ? List.of() : List.copyOf(mfaAmr);
    }

    /** What is wrong with the settings; empty when the provider can be used. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (id == null || !ID.matcher(id).matches()) {
            problems.add("id must be lower-case letters, digits and hyphens");
        }
        requireUrl(problems, "issuer", issuer);
        requireUrl(problems, "redirect-uri", redirectUri);
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
