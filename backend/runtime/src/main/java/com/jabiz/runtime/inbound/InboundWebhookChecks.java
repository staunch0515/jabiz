package com.jabiz.runtime.inbound;

import com.jabiz.event.EventSubscription;
import com.jabiz.inbound.InboundSchemes;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.webhook.WebhookSignature;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Startup self-check of the inbound webhook providers (decision D37 item 1), every problem at once: a valid name, a
 * known scheme, secrets of at least {@value WebhookSignature#MIN_SECRET_LENGTH} characters, at least one event type,
 * each of which makes a valid platform event type ({@code jabiz.inbound.<provider>.<type>}), and the tolerance, body
 * size and rate limit within their bounds. A provider with a problem is never served ({@link #served}), even where the
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
        for (int i = 0; i < provider.eventTypes().size(); i++) {
            String listed = provider.eventTypes().get(i);
            if (listed == null || listed.isBlank()) {
                found.add(CheckProblem.error(CATEGORY, at + ".event-types[" + i + "]", "missing or blank"));
                continue;
            }
            String type = InboundEvents.type(name, listed);
            if (!EventSubscription.NAME.matcher(type).matches()) {
                found.add(CheckProblem.error(CATEGORY, at + ".event-types[" + i + "]", type + " is not a valid event "
                    + "type (letters, digits, '.', '_', ':', '-'; at most 100 characters)"));
            }
        }
        Duration tolerance = provider.tolerance();
        if (tolerance.isNegative() || tolerance.isZero()
            || tolerance.compareTo(InboundWebhookProperties.MAX_TOLERANCE) > 0) {
            found.add(CheckProblem.error(CATEGORY, at + ".tolerance", "must be more than 0 and at most "
                + InboundWebhookProperties.MAX_TOLERANCE));
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
}
