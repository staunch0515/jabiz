package com.jabiz.runtime.webhook;

import com.jabiz.event.EventSubscription;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.webhook.WebhookSignature;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Startup self-check of webhooks (decision D33), every problem at once: each subscription has a valid unique name and
 * event type, a secret of at least {@value WebhookSignature#MIN_SECRET_LENGTH} characters, and an https address (http
 * only to this machine) on a host of {@code jabiz.webhooks.allowed-hosts}.
 */
@Component
public class WebhookChecks implements PlatformCheck {

    public static final String CATEGORY = "WEBHOOK";
    static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final WebhookProperties properties;

    public WebhookChecks(WebhookProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<WebhookProperties.Subscription> subscriptions = properties.subscriptions();
        for (int i = 0; i < subscriptions.size(); i++) {
            WebhookProperties.Subscription s = subscriptions.get(i);
            String at = "jabiz.webhooks.subscriptions[" + i + "]";
            if (s.name() == null || !EventSubscription.NAME.matcher("jabiz.webhook." + s.name()).matches()) {
                problems.add(CheckProblem.error(CATEGORY, at + ".name", "missing or not a valid name: " + s.name()));
            } else if (!names.add(s.name())) {
                problems.add(CheckProblem.error(CATEGORY, at + ".name", "used twice: " + s.name()));
            }
            if (s.eventType() == null || !EventSubscription.NAME.matcher(s.eventType()).matches()) {
                problems.add(CheckProblem.error(CATEGORY, at + ".event-type", "missing or not a valid event type"));
            }
            if (s.secret() == null || s.secret().length() < WebhookSignature.MIN_SECRET_LENGTH) {
                problems.add(CheckProblem.error(CATEGORY, at + ".secret", "missing or shorter than "
                    + WebhookSignature.MIN_SECRET_LENGTH + " characters"));
            }
            String refused = refusal(s.url());
            if (refused != null) {
                problems.add(CheckProblem.error(CATEGORY, at + ".url", refused));
            }
        }
        return problems;
    }

    /** Why the platform will not send to {@code url}, or null. */
    String refusal(URI url) {
        if (url == null || url.getHost() == null) {
            return "missing or not an absolute address";
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && LOOPBACK.contains(host))) {
            return "not https (http only to this machine): " + WebhookProperties.origin(url);
        }
        if (url.getUserInfo() != null) {
            return "carries credentials";
        }
        if (!properties.allowedHosts().contains(host)) {
            return "host " + host + " is not in jabiz.webhooks.allowed-hosts";
        }
        return null;
    }
}
