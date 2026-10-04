package com.jabiz.runtime.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Webhooks ({@code jabiz.webhooks.*}; docs/design/11-ledger-events-jobs.md section 2.4, decision D33): the outbox's
 * events of one type POSTed, signed, to a receiver of another system. Subscriptions are configuration, not data:
 * their secrets come from the environment like the platform's other keys.
 *
 * @param allowedHosts  the only hosts a subscription may send to (exact names, case ignored); none by default, so
 *                      nothing is sent anywhere unless named here
 * @param timeout       how long a receiver has to answer, the whole exchange included, default 5 s
 * @param subscriptions the subscriptions
 */
@ConfigurationProperties("jabiz.webhooks")
public record WebhookProperties(List<String> allowedHosts, Duration timeout, List<Subscription> subscriptions) {

    public WebhookProperties {
        allowedHosts = allowedHosts == null ? List.of()
            : allowedHosts.stream().map(host -> host.trim().toLowerCase(Locale.ROOT)).toList();
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
    }

    /**
     * Only the scheme, host and port of an address, for messages and logs: receivers' paths often carry a token.
     */
    public static String origin(URI url) {
        if (url == null || url.getHost() == null) {
            return url == null ? "null" : "(not an absolute address)";
        }
        return url.getScheme() + "://" + url.getHost() + (url.getPort() < 0 ? "" : ":" + url.getPort());
    }

    public Optional<Subscription> find(String name) {
        return subscriptions.stream().filter(s -> name.equals(s.name())).findFirst();
    }

    /**
     * One receiver of one event type. Its outbox consumer is {@code jabiz.webhook.<name>}: renaming it sends every
     * past event of the type again.
     *
     * @param name      the subscription's name (letters, digits, {@code . _ : -})
     * @param eventType the outbox event type sent, e.g. {@code finance.invoice-posted}
     * @param url       the receiver: https, or http to this machine only (localhost, 127.0.0.1, ::1)
     * @param secret    the signing key (at least 32 characters), from the environment through a placeholder
     *                  ({@code secret: ${ERP_WEBHOOK_SECRET}}); never written anywhere
     */
    public record Subscription(String name, String eventType, URI url, String secret) {

        public String consumer() {
            return "jabiz.webhook." + name;
        }

        @Override
        public String toString() {
            return "Subscription[name=" + name + ", eventType=" + eventType + ", url=" + origin(url) + ", secret=***]";
        }
    }
}
