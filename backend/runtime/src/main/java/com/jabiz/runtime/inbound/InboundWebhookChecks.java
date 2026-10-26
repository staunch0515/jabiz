package com.jabiz.runtime.inbound;

import com.jabiz.event.EventSubscription;
import com.jabiz.inbound.BodyHmacSignature;
import com.jabiz.inbound.InboundSchemes;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.webhook.WebhookSignature;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Startup self-check of the inbound webhook providers (decision D37 item 1), every problem at once: a valid name, a
 * known scheme, secrets of at least {@value WebhookSignature#MIN_SECRET_LENGTH} characters, at least one event type,
 * each of which makes a valid platform event type ({@code jabiz.inbound.<provider>.<type>}), and the tolerance, body
 * size and rate limit within their bounds; for the body-only scheme (decision D57) no tolerance and valid, distinct
 * headers; metadata headers that are valid, at most eight and never a signature or credential; a rejection status of
 * 400 or 401. A provider with a problem is never served ({@link #served}), even where the
 * problems do not stop the application.
 */
@Component
public class InboundWebhookChecks implements PlatformCheck {

    public static final String CATEGORY = "INBOUND";
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final String PREFIX = "jabiz.inbound-webhooks.providers.";

    private final List<CheckProblem> problems = new ArrayList<>();
    private final Map<String, InboundWebhookProperties.Provider> valid = new TreeMap<>();

    public InboundWebhookChecks(InboundWebhookProperties properties) {
        new TreeMap<>(properties.providers()).forEach((name, provider) -> {
            List<CheckProblem> found = problems(name, provider);
            if (found.isEmpty()) {
                valid.put(name, provider);
            }
            problems.addAll(found);
        });
    }

    @Override
    public List<CheckProblem> check() {
        return List.copyOf(problems);
    }

    /** The provider of this name if it is configured and has no problem; requests to anything else are not found. */
    public Optional<InboundWebhookProperties.Provider> served(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(valid.get(name));
    }

    private static List<CheckProblem> problems(String name, InboundWebhookProperties.Provider provider) {
        List<CheckProblem> found = new ArrayList<>();
        String at = PREFIX + name;
        if (!NAME.matcher(name).matches()) {
            found.add(CheckProblem.error(CATEGORY, at, "not a valid provider name (lower-case letters, digits and "
                + "'-', at most 40, starting with a letter)"));
        }
        if (InboundSchemes.of(provider.scheme()).isEmpty()) {
            found.add(CheckProblem.error(CATEGORY, at + ".scheme", "unknown signature scheme '" + provider.scheme()
                + "' (known: " + InboundSchemes.names() + ")"));
        }
        if (provider.secrets().isEmpty()) {
            found.add(CheckProblem.error(CATEGORY, at + ".secrets", "at least one secret is required"));
        }
        for (int i = 0; i < provider.secrets().size(); i++) {
            String secret = provider.secrets().get(i);
            if (secret == null || secret.isBlank() || secret.length() < WebhookSignature.MIN_SECRET_LENGTH) {
                found.add(CheckProblem.error(CATEGORY, at + ".secrets[" + i + "]", "missing or shorter than "
                    + WebhookSignature.MIN_SECRET_LENGTH + " characters"));
            }
        }
        if (provider.eventTypes().isEmpty()) {
            found.add(CheckProblem.error(CATEGORY, at + ".event-types",
                "no event types: every request would be ignored"));
        }
        Map<String, String> platformTypes = new HashMap<>();
        for (int i = 0; i < provider.eventTypes().size(); i++) {
            String listed = provider.eventTypes().get(i);
            if (listed == null || listed.isBlank()) {
                found.add(CheckProblem.error(CATEGORY, at + ".event-types[" + i + "]", "missing or blank"));
                continue;
            }
            String type = InboundEvents.type(name, listed);
            if (!EventSubscription.NAME.matcher(type).matches()) {
                found.add(CheckProblem.error(CATEGORY, at + ".event-types[" + i + "]", type + " is not a valid event "
                    + "type (letters, digits, '.', '_', ':', '-', and '/' for '.'; at most 100 characters)"));
                continue;
            }
            String before = platformTypes.putIfAbsent(type, listed);
            if (before != null) {
                found.add(CheckProblem.error(CATEGORY, at + ".event-types[" + i + "]", listed + " and " + before
                    + " are both the event type " + type));
            }
        }
        if (provider.bodyOnly()) {
            found.addAll(bodyOnlyProblems(at, provider));
        } else {
            Duration tolerance = provider.toleranceOrDefault();
            if (tolerance.isNegative() || tolerance.isZero()
                || tolerance.compareTo(InboundWebhookProperties.MAX_TOLERANCE) > 0) {
                found.add(CheckProblem.error(CATEGORY, at + ".tolerance", "must be more than 0 and at most "
                    + InboundWebhookProperties.MAX_TOLERANCE));
            }
            if (provider.signedBody() != null) {
                found.add(CheckProblem.error(CATEGORY, at + ".signed-body", "only for the scheme "
                    + BodyHmacSignature.NAME));
            }
        }
        found.addAll(metadataProblems(at, provider));
        if (provider.rejectStatus() != 400 && provider.rejectStatus() != 401) {
            found.add(CheckProblem.error(CATEGORY, at + ".reject-status", "must be 400 or 401"));
        }
        long size = provider.maxBodySize().toBytes();
        if (size <= 0 || size > InboundWebhookProperties.MAX_BODY_SIZE.toBytes()) {
            found.add(CheckProblem.error(CATEGORY, at + ".max-body-size", "must be more than 0 and at most "
                + InboundWebhookProperties.MAX_BODY_SIZE.toMegabytes() + "MB"));
        }
        if (provider.rateLimit().perMinute() <= 0) {
            found.add(CheckProblem.error(CATEGORY, at + ".rate-limit.per-minute", "must be positive"));
        }
        if (provider.rateLimit().maxClients() <= 0) {
            found.add(CheckProblem.error(CATEGORY, at + ".rate-limit.max-clients", "must be positive"));
        }
        return found;
    }

    /**
     * Decision D57: a body-only scheme signs no time, so a tolerance would promise what it cannot keep; its headers
     * must be valid names, three different ones.
     */
    private static List<CheckProblem> bodyOnlyProblems(String at, InboundWebhookProperties.Provider provider) {
        List<CheckProblem> found = new ArrayList<>();
        if (provider.tolerance() != null) {
            found.add(CheckProblem.error(CATEGORY, at + ".tolerance", "the scheme " + BodyHmacSignature.NAME
                + " signs no time: a tolerance cannot apply (remove it)"));
        }
        InboundWebhookProperties.SignedBody settings = provider.signedBodyOrDefault();
        if (BodyHmacSignature.Encoding.of(settings.encoding()) == null) {
            found.add(CheckProblem.error(CATEGORY, at + ".signed-body.encoding", "must be base64 or hex"));
        }
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("signature-header", settings.signatureHeader());
        headers.put("event-id-header", settings.eventIdHeader());
        headers.put("event-type-header", settings.eventTypeHeader());
        headers.forEach((key, header) -> {
            if (!BodyHmacSignature.HEADER.matcher(header).matches()) {
                found.add(CheckProblem.error(CATEGORY, at + ".signed-body." + key, "not a header name (letters, "
                    + "digits and '-', at most 64)"));
            } else if (!names.add(header)) {
                found.add(CheckProblem.error(CATEGORY, at + ".signed-body." + key, header + " is named twice"));
            }
        });
        return found;
    }

    /** Headers that are never kept, whatever the configuration says: signatures and credentials. */
    private static final Pattern SECRET_HEADER = Pattern.compile(
        "(?i).*(hmac|signature|authorization|cookie|token|secret|password|key).*");

    private static List<CheckProblem> metadataProblems(String at, InboundWebhookProperties.Provider provider) {
        List<CheckProblem> found = new ArrayList<>();
        List<String> headers = provider.metadataHeaders();
        if (headers.size() > InboundWebhookProperties.Provider.MAX_METADATA_HEADERS) {
            found.add(CheckProblem.error(CATEGORY, at + ".metadata-headers", "at most "
                + InboundWebhookProperties.Provider.MAX_METADATA_HEADERS + " headers"));
        }
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            String where = at + ".metadata-headers[" + i + "]";
            if (header == null || !BodyHmacSignature.HEADER.matcher(header).matches()) {
                found.add(CheckProblem.error(CATEGORY, where, "not a header name (letters, digits and '-', at most "
                    + "64)"));
            } else if (SECRET_HEADER.matcher(header).matches() || (provider.bodyOnly()
                && header.equalsIgnoreCase(provider.signedBodyOrDefault().signatureHeader()))) {
                found.add(CheckProblem.error(CATEGORY, where, header + " may hold a signature or a credential: "
                    + "never kept"));
            } else if (!seen.add(header)) {
                found.add(CheckProblem.error(CATEGORY, where, header + " is named twice"));
            }
        }
        return found;
    }
}
