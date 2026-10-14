package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The addresses Stripe's pages must start with come from the environment in every profile (application.yml), with
 * Stripe's own as the default: a deployment against the local stub (the end-to-end job) sets
 * {@code QB_STRIPE_CHECKOUT_URL_PREFIX} and {@code QB_STRIPE_CONNECT_URL_PREFIX} as development does
 * (application-dev.yml), and a production deployment that sets neither accepts Stripe's pages only.
 * {@link StripeSettings#problems()} and {@link PayoutSettings#problems(boolean)} still refuse anything but Stripe's in
 * live mode, and anything but https or a local address in test mode.
 */
class StripeAddressesConfigTest {

    private static StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("application.yml",
            new ClassPathResource("application.yml"));
        yaml.forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }

    @Test
    void stripesOwnPagesByDefault() throws IOException {
        StandardEnvironment environment = environment(Map.of());
        assertThat(environment.getProperty("quizbuks.stripe.checkout-url-prefix"))
            .isEqualTo(StripeSettings.STRIPE_CHECKOUT);
        assertThat(environment.getProperty("quizbuks.stripe.connect-url-prefix"))
            .isEqualTo(PayoutSettings.STRIPE_CONNECT);
    }

    @Test
    void theEnvironmentPointsBothAtAStub() throws IOException {
        StandardEnvironment environment = environment(Map.of(
            "QB_STRIPE_CHECKOUT_URL_PREFIX", "http://localhost:8093/",
            "QB_STRIPE_CONNECT_URL_PREFIX", "http://localhost:8093/"));
        assertThat(environment.getProperty("quizbuks.stripe.checkout-url-prefix")).isEqualTo("http://localhost:8093/");
        assertThat(environment.getProperty("quizbuks.stripe.connect-url-prefix")).isEqualTo("http://localhost:8093/");
    }
}
