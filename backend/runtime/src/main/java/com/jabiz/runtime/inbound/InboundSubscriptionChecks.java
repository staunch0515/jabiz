package com.jabiz.runtime.inbound;

import com.jabiz.event.EventSubscription;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Startup check that the inbound webhook providers and the event subscriptions meet (decision D37), category
 * {@code INBOUND}. For each configured provider:
 * <ul>
 *   <li>a subscription to {@code jabiz.inbound.<provider>.<type>} whose type the provider does not list is an error:
 *       those events would be answered 200 and dropped, and the consumer never run;</li>
 *   <li>a listed type that no subscription consumes is a warning: its events are stored for nothing.</li>
 * </ul>
 * Subscriptions to providers not configured (in this environment) are not compared.
 */
@Component
public class InboundSubscriptionChecks implements PlatformCheck {

    private final InboundWebhookProperties properties;
    private final Supplier<Collection<EventSubscription<?>>> subscriptions;

    @Autowired
    public InboundSubscriptionChecks(InboundWebhookProperties properties,
        ObjectProvider<EventSubscription<?>> subscriptions) {
        this.properties = properties;
        this.subscriptions = () -> subscriptions.orderedStream().toList();
    }

    InboundSubscriptionChecks(InboundWebhookProperties properties, Collection<EventSubscription<?>> subscriptions) {
        this.properties = properties;
        this.subscriptions = () -> subscriptions;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> consumed = new HashSet<>();
        for (EventSubscription<?> subscription : subscriptions.get()) {
            String type = subscription.eventType();
            if (!type.startsWith(InboundEvents.PREFIX)) {
                continue;
            }
            String rest = type.substring(InboundEvents.PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            String provider = rest.substring(0, dot);
            String external = rest.substring(dot + 1);
            InboundWebhookProperties.Provider configured = properties.providers().get(provider);
            if (configured == null) {
                continue;
            }
            consumed.add(type);
            // Compared as event types: a listed orders/paid is consumed as ...orders.paid (decision D57).
            if (configured.eventTypes().stream()
                    .noneMatch(listed -> listed != null && InboundEvents.type(provider, listed).equals(type))) {
                problems.add(CheckProblem.error(InboundWebhookChecks.CATEGORY, "EventSubscription "
                    + subscription.consumer(), "subscribes to " + type + " but provider " + provider
                    + " does not list " + external + ": such events are answered and dropped"));
            }
        }
        for (Map.Entry<String, InboundWebhookProperties.Provider> entry
            : new TreeMap<>(properties.providers()).entrySet()) {
            for (String listed : entry.getValue().eventTypes()) {
                if (listed == null || listed.isBlank()) {
                    continue; // reported by InboundWebhookChecks
                }
                String type = InboundEvents.type(entry.getKey(), listed);
                if (!consumed.contains(type)) {
                    problems.add(CheckProblem.warning(InboundWebhookChecks.CATEGORY,
                        "jabiz.inbound-webhooks.providers." + entry.getKey() + ".event-types", listed
                            + " is accepted and stored but no subscription consumes " + type));
                }
            }
        }
        return problems;
    }
}
