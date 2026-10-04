package com.jabiz.runtime.webhook;

import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

import java.util.UUID;

/**
 * Webhook delivery (docs/design/11-ledger-events-jobs.md section 2.4, decision D33). Every subscription of
 * {@code jabiz.webhooks.subscriptions} is an outbox consumer of its own ({@code jabiz.webhook.<name>}) that runs
 * {@value #DELIVER} for each event of its type: delivered at least once and consumed once per subscription, retried
 * with the outbox's backoff and every failed attempt recorded ({@code sys_outbox_attempt}), whatever the other
 * subscriptions do. Receivers deduplicate by {@code X-Jabiz-Event-Id}.
 */
@Configuration
public class WebhookProcesses {

    public static final String DELIVER = "WEBHOOK_DELIVER";
    /** Held by the system actor that delivers events; no person needs it. */
    public static final String PERMISSION = "webhook.deliver";
    private static final String INPUT = "input";

    /**
     * Which event to send to which subscription; the step reads the event itself from the outbox, so neither its
     * type nor its payload comes from the caller (nor is copied into the operation record).
     *
     * @param subscription the subscription's name
     */
    public record DeliverInput(@NotBlank String subscription, @NotNull UUID eventId) {}

    public record DeliverOutput(String subscription) {}

    static final ProcessDefinition<DeliverInput, DeliverOutput, ProcessContext> DEFINITION =
        ProcessDefinition.define(DELIVER, 1, DeliverInput.class, DeliverOutput.class, ProcessContext.class, pb -> pb
            .description("Sends one event to one webhook subscription's receiver.")
            .permissions(PERMISSION)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new DeliverOutput(ctx.get(INPUT, DeliverInput.class).subscription()))
            .step("Send the event", SendWebhook.of(INPUT)));

    @Bean
    ProcessDefinition<DeliverInput, DeliverOutput, ProcessContext> webhookDeliverProcess() {
        return DEFINITION;
    }

    static EventSubscription<DeliverInput> subscription(WebhookProperties.Subscription s) {
        return EventSubscription.of(s.consumer(), s.eventType(), DEFINITION, event -> input(s.name(), event));
    }

    static DeliverInput input(String subscription, DomainEvent event) {
        return new DeliverInput(subscription, event.eventId());
    }

    /**
     * Registers one {@link EventSubscription} bean per configured subscription, before the outbox deliverer collects
     * them. Invalid ones are skipped here and reported by {@link WebhookChecks}.
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor webhookSubscriptions() {
        return new Registrar();
    }

    static final class Registrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            WebhookProperties properties = Binder.get(environment).bind("jabiz.webhooks", WebhookProperties.class)
                .orElseGet(() -> new WebhookProperties(null, null, null));
            for (WebhookProperties.Subscription s : properties.subscriptions()) {
                if (s.name() == null || s.eventType() == null
                    || !EventSubscription.NAME.matcher(s.consumer()).matches()
                    || !EventSubscription.NAME.matcher(s.eventType()).matches()) {
                    continue;
                }
                String bean = "webhookSubscription:" + s.name();
                if (registry.containsBeanDefinition(bean)) {
                    continue;
                }
                RootBeanDefinition definition = new RootBeanDefinition(EventSubscription.class);
                definition.setTargetType(ResolvableType.forClassWithGenerics(EventSubscription.class,
                    DeliverInput.class));
                definition.setInstanceSupplier(() -> subscription(s));
                registry.registerBeanDefinition(bean, definition);
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        }
    }
}
