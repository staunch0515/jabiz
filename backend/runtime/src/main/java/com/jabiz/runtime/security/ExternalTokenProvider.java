package com.jabiz.runtime.security;

import com.jabiz.security.ExternalTokenRules;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A platform that embeds an application and signs tokens for the people it lets in (decision D59; a Shopify app's
 * session tokens), as configured under {@code jabiz.security.external-tokens.<name>}.
 *
 * @param secrets            the signing secrets (the application's secret at that platform; a second one while it is
 *                           replaced), each at least 32 characters, from environment variables only
 * @param audience           the {@code aud} its tokens carry (the application's key there)
 * @param tenantPattern      the form of a tenant, matched whole
 * @param issuerFormat       {@code iss} with {@code {tenant}}
 * @param destinationFormat  {@code dest} with {@code {tenant}}
 * @param skew               allowed clock difference, default 10 s, at most 60 s
 * @param autoProvision      whether a sign-in opens an account for a person nobody linked yet, at an entry that lets
 *                           people register
 * @param frameAncestors     origins besides the tenant's own that may frame the embedded pages
 *                           ({@code https://admin.shopify.com})
 * @param tenantParameter    the query parameter naming the tenant when the platform opens a page ({@code shop})
 */
public record ExternalTokenProvider(String name, List<String> secrets, String audience, String tenantPattern,
    String issuerFormat, String destinationFormat, Duration skew, boolean autoProvision, List<String> frameAncestors,
    String tenantParameter) {

    /** A provider's name: it is stored, prefixed, as the provider of its users' identities (40 characters). */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,35}");
    /** The prefix of the identities its sign-ins link, so that they never meet an OpenID Connect provider's. */
    public static final String IDENTITY_PREFIX = "ext:";
    static final int MIN_SECRET_LENGTH = 32;
    private static final Pattern ORIGIN_TEXT = Pattern.compile("https://[a-z0-9.-]+(:[0-9]{1,5})?");
    private static final Pattern PARAMETER = Pattern.compile("[a-z][a-z0-9_-]{0,39}");

    public ExternalTokenProvider {
        secrets = secrets == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(secrets));
        frameAncestors = frameAncestors == null ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(frameAncestors));
        skew = skew == null ? Duration.ofSeconds(10) : skew;
        tenantParameter = tenantParameter == null || tenantParameter.isBlank() ? "shop" : tenantParameter.trim();
    }

    /** The provider of the identities its sign-ins link ({@code ext:<name>}). */
    public String identityProvider() {
        return IDENTITY_PREFIX + name;
    }

    /** The subject of the identity a token stands for: the person within the tenant. */
    public static String identitySubject(ExternalTokenRules.Verified token) {
        return identitySubject(token.tenant(), token.subject());
    }

    /** The subject of the identity of {@code subject} in {@code tenant}: {@code <tenant>/<subject>}. */
    public static String identitySubject(String tenant, String subject) {
        return tenant + "/" + subject;
    }

    /**
     * The {@code frame-ancestors} sources of a page opened by {@code tenant}: the tenant's origin (its {@code dest}) and
     * the other configured origins; empty when that is not a plain https origin. Only for a tenant the
     * {@link #settings()} accept.
     */
    public Optional<String> frameAncestorsOf(String tenant) {
        String own = destinationFormat.replace(ExternalTokenRules.TENANT, tenant);
        // Whatever the tenant pattern lets through, only an https origin goes into the policy.
        if (!origin(own) || !ORIGIN_TEXT.matcher(own).matches()) {
            return Optional.empty();
        }
        List<String> sources = new ArrayList<>();
        sources.add(own);
        sources.addAll(frameAncestors);
        return Optional.of(String.join(" ", sources));
    }

    /** The rules of its tokens; only for a provider without {@link #problems()}. */
    public ExternalTokenRules.Settings settings() {
        return new ExternalTokenRules.Settings(audience, Pattern.compile(tenantPattern), issuerFormat,
            destinationFormat, skew);
    }

    /** The secrets as the signatures use them. */
    public List<byte[]> secretBytes() {
        return secrets.stream().map(secret -> secret.getBytes(StandardCharsets.UTF_8)).toList();
    }

    /** Everything wrong with it, at once. */
    public List<String> problems() {
        List<String> found = new ArrayList<>();
        if (name == null || !NAME.matcher(name).matches()) {
            found.add("not a valid name (lower-case letters, digits and '-', at most 36, starting with a letter)");
        }
        if (secrets.isEmpty()) {
            found.add("at least one secret is required");
        }
        for (int i = 0; i < secrets.size(); i++) {
            if (unresolved(secrets.get(i))) {
                // A placeholder whose variable is not set stays as its text, which anyone who read the configuration
                // could sign with (phase 17e).
                found.add("secrets[" + i + "]: an unresolved placeholder (is its environment variable set?)");
            } else if (secrets.get(i) == null || secrets.get(i).length() < MIN_SECRET_LENGTH) {
                found.add("secrets[" + i + "]: missing or shorter than " + MIN_SECRET_LENGTH + " characters");
            }
        }
        if (audience == null || audience.isBlank()) {
            found.add("audience is required");
        } else if (unresolved(audience)) {
            found.add("audience: an unresolved placeholder (is its environment variable set?)");
        }
        if (tenantPattern == null || tenantPattern.isBlank()) {
            found.add("tenant-pattern is required");
        } else {
            try {
                Pattern.compile(tenantPattern);
            } catch (PatternSyntaxException e) {
                found.add("tenant-pattern is not a valid pattern");
            }
        }
        for (String[] form : List.of(new String[] {"issuer-format", issuerFormat},
            new String[] {"destination-format", destinationFormat})) {
            String value = form[1];
            if (value == null || !value.startsWith("https://") || value.indexOf(ExternalTokenRules.TENANT) < 0
                || value.indexOf(ExternalTokenRules.TENANT) != value.lastIndexOf(ExternalTokenRules.TENANT)) {
                found.add(form[0] + " must be an https address with " + ExternalTokenRules.TENANT + " exactly once");
            } else if ("destination-format".equals(form[0])
                && !origin(value.replace(ExternalTokenRules.TENANT, "tenant.example"))) {
                // The destination is the tenant's origin, which an embedded page lets frame it (phase 17e).
                found.add("destination-format must be an https origin without path (https://"
                    + ExternalTokenRules.TENANT + ")");
            }
        }
        if (skew.isNegative() || skew.compareTo(ExternalTokenRules.MAX_SKEW) > 0) {
            found.add("skew must be between 0 and " + ExternalTokenRules.MAX_SKEW);
        }
        for (int i = 0; i < frameAncestors.size(); i++) {
            if (!origin(frameAncestors.get(i))) {
                found.add("frame-ancestors[" + i + "] must be an https origin without path (https://host)");
            }
        }
        if (!PARAMETER.matcher(tenantParameter).matches()) {
            found.add("tenant-parameter is not a valid parameter name");
        }
        return found;
    }

    /** A placeholder whose variable was not set stays as its text (no default given). */
    private static boolean unresolved(String value) {
        return value != null && value.contains("${");
    }

    private static boolean origin(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getRawQuery() == null
                && uri.getRawFragment() == null && value.equals(value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "ExternalTokenProvider[name=" + name + ", secrets=[" + secrets.size() + " ***], audience=" + audience
            + ", tenantPattern=" + tenantPattern + ", autoProvision=" + autoProvision + "]";
    }
}
