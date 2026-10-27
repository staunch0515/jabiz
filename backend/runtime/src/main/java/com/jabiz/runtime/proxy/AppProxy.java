package com.jabiz.runtime.proxy;

import com.jabiz.security.AppProxyCaller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One app proxy as configured, {@code jabiz.app-proxies.<name>} (decision D60; docs/design/10-security.md section
 * 15.13): another platform forwards its visitors' requests to {@code /api/proxy/<name>/<process>} with its signature
 * in the query ({@link com.jabiz.security.AppProxySignature}). Configuration, not data: the secrets come from the
 * environment through placeholders and never show.
 *
 * @param name              the proxy's name, the path segment
 * @param secrets           the signing secrets (the other platform's app secret), at least 32 characters each; two
 *                          while one is being replaced
 * @param tenantParameter   the signed query parameter naming the tenant (default {@code shop}); exactly once
 * @param tenantPattern     what a tenant must be in full (such as {@code [a-z0-9][a-z0-9-]*\.myshopify\.com})
 * @param customerParameter the signed query parameter naming the tenant's signed-in customer, empty for a visitor
 *                          (default {@code logged_in_customer_id})
 * @param visitorParameter  the query parameter carrying the visitor's own key, for the per-visitor limits only
 *                          (default {@code visitor})
 * @param maxAge            how far the signed time may be from now either way (default 90 seconds, as the other
 *                          platform's own library allows; at most 10 minutes)
 */
public record AppProxy(String name, List<String> secrets, String tenantParameter, String tenantPattern,
    String customerParameter, String visitorParameter, Duration maxAge) {

    public static final int MIN_SECRET_LENGTH = 32;
    public static final Duration DEFAULT_MAX_AGE = Duration.ofSeconds(90);
    public static final Duration MAX_MAX_AGE = Duration.ofMinutes(10);
    static final Pattern PARAMETER = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,39}");

    public AppProxy {
        // Null entries are kept for the check to report, rather than failing the binding.
        secrets = secrets == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(secrets));
        tenantParameter = tenantParameter == null ? "shop" : tenantParameter;
        customerParameter = customerParameter == null ? "logged_in_customer_id" : customerParameter;
        visitorParameter = visitorParameter == null ? "visitor" : visitorParameter;
        maxAge = maxAge == null ? DEFAULT_MAX_AGE : maxAge;
    }

    /** Everything wrong with it, at once. */
    public List<String> problems() {
        List<String> found = new ArrayList<>();
        if (name == null || !AppProxyCaller.NAME.matcher(name).matches()) {
            found.add("not a valid name (lower-case letters, digits and '-', at most 30, starting with a letter)");
        }
        if (secrets.isEmpty()) {
            found.add("at least one secret is required");
        }
        for (int i = 0; i < secrets.size(); i++) {
            String secret = secrets.get(i);
            if (secret != null && secret.contains("${")) {
                found.add("secrets[" + i + "]: an unresolved placeholder (is its environment variable set?)");
            } else if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
                found.add("secrets[" + i + "]: missing or shorter than " + MIN_SECRET_LENGTH + " characters");
            }
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
        for (String[] parameter : List.of(new String[] {"tenant-parameter", tenantParameter},
            new String[] {"customer-parameter", customerParameter}, new String[] {"visitor-parameter",
                visitorParameter})) {
            if (!PARAMETER.matcher(parameter[1]).matches()) {
                found.add(parameter[0] + " is not a valid parameter name");
            }
        }
        if (maxAge.isNegative() || maxAge.isZero() || maxAge.compareTo(MAX_MAX_AGE) > 0) {
            found.add("max-age must be positive and at most " + MAX_MAX_AGE);
        }
        return found;
    }

    @Override
    public String toString() {
        return "AppProxy[name=" + name + ", secrets=" + secrets.size() + " ***, tenantPattern=" + tenantPattern
            + ", maxAge=" + maxAge + "]";
    }
}
