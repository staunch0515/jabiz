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
 * <p>A provider may open accounts at the first sign-in ({@code auto-provision}, decision D39): only at entries that
 * let people register, for a verified address nobody uses ({@link IdTokenValidator.Identity#trustedEmail}).
 *
 * @param redirectUri   the administration's callback ({@code admin}), as before entries had their own
 * @param mfaAmr        values of the ID token's {@code amr} claim that count as a second factor; empty trusts none
 * @param redirectUris  the callbacks of other entries (or the administration's), by entry name
 * @param kind          {@value #GENERIC} (the default), {@value #GOOGLE}, {@value #FACEBOOK}, {@value #LINE} or
 *                      {@value #APPLE}: each
 *                      known kind has its issuer as the default; Google's addresses count as verified only where
 *                      Google vouches for them (gmail, or a Workspace domain); Facebook and LINE take the client's
 *                      credentials in the token request's form, and LINE's web sign-in signs its ID tokens with the
 *                      client secret (HS256, decision D61)
 * @param autoProvision whether an unknown subject with a verified, unused address gets an account (default false)
 * @param signUpUnverifiedEmail with {@code autoProvision}: whether an address the provider does not vouch for opens
 *                      an account too, unverified and pre-claimable, with a verification mail (default false; decision
 *                      D61 item 3)
 * @param apple         the settings of a {@value #APPLE} provider (decision D67): its client secret is a short-lived
 *                      token this server signs with the developer's key, and Apple posts its answer to this server
 *                      ({@link Apple#formPostUri}), which sends the browser on to the entry's callback
 */
public record OidcProvider(String id, String issuer, String clientId, String clientSecret, String redirectUri,
    List<String> scopes, Map<String, String> labels, List<String> mfaAmr, Map<String, String> redirectUris,
    String kind, boolean autoProvision, boolean signUpUnverifiedEmail, Apple apple) {

    /**
     * What Sign in with Apple needs besides the client id (its Services ID; decision D67): the developer team, the key
     * that signs the client secret (a {@code .p8} file's PKCS #8 EC P-256 key, PEM or its Base64 body, from the
     * environment) and this server's address Apple posts its answer to: {@code https://<host>/api/auth/oidc/<id>/form-post},
     * registered at Apple as the return URL.
     */
    public record Apple(String teamId, String keyId, String privateKey, String formPostUri) {

        @Override
        public String toString() {
            return "Apple[teamId=" + teamId + ", keyId=" + keyId + ", privateKey=***, formPostUri=" + formPostUri + "]";
        }
    }

    public static final List<String> DEFAULT_SCOPES = List.of("openid", "profile", "email");

    /** A provider following OpenID Connect as is. */
    public static final String GENERIC = "generic";
    /** Google (standard OpenID Connect, its own rule for trusting addresses). */
    public static final String GOOGLE = "google";
    /** Google's issuer, the default of a {@value #GOOGLE} provider. */
    public static final String GOOGLE_ISSUER = "https://accounts.google.com";
    /** Facebook (Meta's OpenID Connect web flow, decision D61). */
    public static final String FACEBOOK = "facebook";
    /** Facebook's issuer, the default of a {@value #FACEBOOK} provider. */
    public static final String FACEBOOK_ISSUER = "https://www.facebook.com";
    /** LINE Login (OpenID Connect; HS256 ID tokens at web sign-in, decision D61). */
    public static final String LINE = "line";
    /** LINE's issuer, the default of a {@value #LINE} provider. */
    public static final String LINE_ISSUER = "https://access.line.me";
    /** Sign in with Apple (decision D67). */
    public static final String APPLE = "apple";
    /** Apple's issuer, the default of an {@value #APPLE} provider. */
    public static final String APPLE_ISSUER = "https://appleid.apple.com";
    /** Apple knows no {@code profile}: the name comes with the first sign-in's answer (decision D67 item 3). */
    public static final List<String> APPLE_SCOPES = List.of("openid", "name", "email");
    /** Where Apple posts its answer for the provider {@code id}, on this server (decision D67 item 1). */
    public static String formPostPath(String id) {
        return "/api/auth/oidc/" + id + "/form-post";
    }

    /** Apple's team and key ids: ten upper-case letters or digits. */
    private static final Pattern APPLE_ID = Pattern.compile("[A-Z0-9]{10}");
    /** The shortest client secret an HS256 key may be (RFC 7518 section 3.2: the hash's size, 256 bits). */
    static final int HS256_MIN_SECRET_BYTES = 32;
    private static final List<String> KINDS = List.of(GENERIC, GOOGLE, FACEBOOK, LINE, APPLE);

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern ENTRY = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    @ConstructorBinding
    public OidcProvider {
        kind = kind == null || kind.isBlank() ? GENERIC : kind.strip().toLowerCase(Locale.ROOT);
        scopes = scopes == null || scopes.isEmpty() ? (APPLE.equals(kind) ? APPLE_SCOPES : DEFAULT_SCOPES)
            : List.copyOf(scopes);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        mfaAmr = mfaAmr == null ? List.of() : List.copyOf(mfaAmr);
        redirectUris = redirectUris == null ? Map.of() : Map.copyOf(redirectUris);
        if (issuer == null || issuer.isBlank()) {
            issuer = switch (kind) {
                case GOOGLE -> GOOGLE_ISSUER;
                case FACEBOOK -> FACEBOOK_ISSUER;
                case LINE -> LINE_ISSUER;
                case APPLE -> APPLE_ISSUER;
                default -> issuer;
            };
        }
    }

    /** A provider of any kind but {@value #APPLE}. */
    public OidcProvider(String id, String issuer, String clientId, String clientSecret, String redirectUri,
        List<String> scopes, Map<String, String> labels, List<String> mfaAmr, Map<String, String> redirectUris,
        String kind, boolean autoProvision, boolean signUpUnverifiedEmail) {
        this(id, issuer, clientId, clientSecret, redirectUri, scopes, labels, mfaAmr, redirectUris, kind,
            autoProvision, signUpUnverifiedEmail, null);
    }

    /** A provider of a kind that opens accounts for verified addresses only (as before decision D61). */
    public OidcProvider(String id, String issuer, String clientId, String clientSecret, String redirectUri,
        List<String> scopes, Map<String, String> labels, List<String> mfaAmr, Map<String, String> redirectUris,
        String kind, boolean autoProvision) {
        this(id, issuer, clientId, clientSecret, redirectUri, scopes, labels, mfaAmr, redirectUris, kind,
            autoProvision, false);
    }

    /** A generic provider that opens no accounts. */
    public OidcProvider(String id, String issuer, String clientId, String clientSecret, String redirectUri,
        List<String> scopes, Map<String, String> labels, List<String> mfaAmr, Map<String, String> redirectUris) {
        this(id, issuer, clientId, clientSecret, redirectUri, scopes, labels, mfaAmr, redirectUris, null, false, false);
    }

    /** Whether this is Google ({@value #GOOGLE}). */
    public boolean google() {
        return GOOGLE.equals(kind);
    }

    /**
     * Whether Google's rule for trusting addresses applies ({@link IdTokenValidator.Identity#trustedEmail}): for a
     * {@value #GOOGLE} provider, and for any provider whose issuer is Google's, whatever its kind says (a provider
     * configured before kinds existed must not trust every address Google once verified).
     */
    public boolean googleRules() {
        return google() || GOOGLE_ISSUER.equals(issuer);
    }

    /**
     * Whether the token request carries the client's credentials in its form ({@code client_secret_post}) rather than
     * Basic authentication: Facebook and LINE take them only so (decision D61 item 1).
     */
    public boolean clientSecretPost() {
        return FACEBOOK.equals(kind) || LINE.equals(kind) || APPLE.equals(kind);
    }

    /** Whether this is Sign in with Apple ({@value #APPLE}, decision D67). */
    public boolean isApple() {
        return APPLE.equals(kind);
    }

    /**
     * Whether the round trip carries a PKCE challenge (RFC 7636). Apple documents none, so it is left out there; the
     * nonce in the ID token, the state with its browser binder and the signed client secret protect the code
     * (decision D67 item 2).
     */
    public boolean pkce() {
        return !isApple();
    }

    /**
     * Where the provider sends its answer for the sign-in entry {@code entry}, as the authorization and the token
     * request name it: the entry's callback ({@link #redirectUri(String)}), or for Apple, which posts it as a form,
     * this server's {@link Apple#formPostUri} (decision D67 item 1); null when the provider is not offered to the entry.
     */
    public String providerRedirectUri(String entry) {
        String callback = redirectUri(entry);
        return callback == null || !isApple() ? callback : apple.formPostUri();
    }

    /**
     * Whether an HS256 ID token keyed with the client secret is accepted: LINE only, whose web sign-in signs so
     * (decision D61 item 2). Every other provider's tokens must be RS256 or ES256 by a key of its JWKS.
     */
    public boolean acceptsHs256() {
        return LINE.equals(kind);
    }

    /** Where the discovery document is: Facebook serves it at the path with a trailing slash (decision D61 item 1). */
    public String discoveryUrl() {
        String base = issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";
        return FACEBOOK.equals(kind) ? base + "/" : base;
    }

    /** The token endpoint when the discovery document names none: Facebook's Graph API (decision D61 item 1). */
    public String defaultTokenEndpoint() {
        return FACEBOOK.equals(kind) ? "https://graph.facebook.com/oauth/access_token" : null;
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
        if (isApple()) {
            appleProblems(problems);
        } else if (blank(clientSecret)) {
            problems.add("client-secret is required (from the environment)");
        }
        if (!isApple() && apple != null) {
            problems.add("apple.* is for kind apple only");
        }
        if (!scopes.contains("openid")) {
            problems.add("scopes must include openid");
        }
        if (!KINDS.contains(kind)) {
            problems.add("kind must be one of " + String.join(", ", KINDS));
        }
        if (autoProvision && !scopes.contains("email")) {
            // Without the address there is nothing to open an account with (decision D39 item 1).
            problems.add("auto-provision needs the scope email");
        }
        if (signUpUnverifiedEmail && !autoProvision) {
            problems.add("sign-up-unverified-email needs auto-provision");
        }
        if (acceptsHs256() && !blank(clientSecret)
            && clientSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < HS256_MIN_SECRET_BYTES) {
            // The secret is the HS256 key of LINE's ID tokens: a shorter one cannot verify them (RFC 7518 3.2).
            problems.add("client-secret of a line provider must be at least " + HS256_MIN_SECRET_BYTES + " bytes");
        }
        return problems;
    }

    /** What is wrong with the settings of Sign in with Apple (decision D67). */
    private void appleProblems(List<String> problems) {
        if (!blank(clientSecret)) {
            problems.add("client-secret is not used by kind apple: the secret is signed with apple.private-key");
        }
        if (apple == null) {
            problems.add("apple.team-id, apple.key-id, apple.private-key and apple.form-post-uri are required");
            return;
        }
        if (blank(apple.teamId()) || !APPLE_ID.matcher(apple.teamId()).matches()) {
            problems.add("apple.team-id must be the team's ten-character id");
        }
        if (blank(apple.keyId()) || !APPLE_ID.matcher(apple.keyId()).matches()) {
            problems.add("apple.key-id must be the key's ten-character id");
        }
        if (blank(apple.privateKey())) {
            problems.add("apple.private-key is required (from the environment)");
        } else {
            try {
                AppleClientSecret.privateKey(apple.privateKey());
            } catch (IllegalArgumentException e) {
                problems.add("apple.private-key: " + e.getMessage());
            }
        }
        requireCallback(problems, "apple.form-post-uri", apple.formPostUri());
        if (secureUrl(apple.formPostUri()) && id != null
            && !URI.create(apple.formPostUri()).getPath().endsWith(formPostPath(id))) {
            problems.add("apple.form-post-uri must be this server's " + formPostPath(id));
        }
        if (!scopes.stream().allMatch(APPLE_SCOPES::contains)) {
            problems.add("scopes of kind apple are openid, name and email only");
        }
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
        return "OidcProvider[id=" + id + ", kind=" + kind + ", issuer=" + issuer + ", clientId=" + clientId
            + ", clientSecret=***, autoProvision=" + autoProvision + ", signUpUnverifiedEmail=" + signUpUnverifiedEmail
            + (apple == null ? "" : ", apple=" + apple) + "]";
    }
}
