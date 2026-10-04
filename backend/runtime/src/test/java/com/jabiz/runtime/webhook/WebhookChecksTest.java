package com.jabiz.runtime.webhook;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookChecksTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private static WebhookProperties.Subscription subscription(String name, String type, String url, String secret) {
        return new WebhookProperties.Subscription(name, type, url == null ? null : URI.create(url), secret);
    }

    private static List<String> problems(WebhookProperties.Subscription... subscriptions) {
        return new WebhookChecks(new WebhookProperties(List.of("hooks.example.com", "localhost"), null,
            List.of(subscriptions))).check().stream().map(p -> p.location() + ": " + p.message()).toList();
    }

    @Test
    void aValidSubscriptionPasses() {
        assertThat(problems(subscription("erp", "finance.invoice-posted", "https://HOOKS.example.com/in", SECRET),
            subscription("local", "finance.invoice-posted", "http://localhost:9000/in", SECRET))).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        assertThat(problems(
            subscription("erp", "finance.invoice-posted", "http://hooks.example.com/in", SECRET),
            subscription("erp", "bad type!", "https://other.example.com/in", "short"),
            subscription(null, "x", "https://user:pw@hooks.example.com/in", SECRET),
            subscription("nourl", "x", null, SECRET)))
            .containsExactly(
                "jabiz.webhooks.subscriptions[0].url: not https (http only to this machine): http://hooks.example.com",
                "jabiz.webhooks.subscriptions[1].name: used twice: erp",
                "jabiz.webhooks.subscriptions[1].event-type: missing or not a valid event type",
                "jabiz.webhooks.subscriptions[1].secret: missing or shorter than 32 characters",
                "jabiz.webhooks.subscriptions[1].url: host other.example.com is not in jabiz.webhooks.allowed-hosts",
                "jabiz.webhooks.subscriptions[2].name: missing or not a valid name: null",
                "jabiz.webhooks.subscriptions[2].url: carries credentials",
                "jabiz.webhooks.subscriptions[3].url: missing or not an absolute address");
    }

    @Test
    void nothingIsAllowedByDefault() {
        List<CheckProblem> problems = new WebhookChecks(new WebhookProperties(null, null,
            List.of(subscription("erp", "x", "https://hooks.example.com/in", SECRET)))).check();
        assertThat(problems).extracting(CheckProblem::message)
            .containsExactly("host hooks.example.com is not in jabiz.webhooks.allowed-hosts");
    }

    @Test
    void theSecretIsNotPrinted() {
        assertThat(subscription("erp", "x", "https://hooks.example.com/in", SECRET).toString())
            .doesNotContain(SECRET).contains("secret=***");
    }
}
