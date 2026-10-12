package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QuizbuksApp;
import com.jabiz.runtime.test.PlatformCheckLauncher;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup checks of Stripe's wiring (docs/quizbuks/plans/Q6-payments.md, D-Q6-1, D-Q6-14), as {@code platformCheck}
 * runs them: a missing webhook secret, a consumed event type the provider does not accept and a key of the other
 * mode each fail the startup, every problem reported at once.
 */
class StripeStartupChecksIT {

    private record Outcome(int exitCode, List<String> lines) {}

    private static Outcome check(String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = PlatformCheckLauncher.run(QuizbuksApp.class, List.of(args),
            new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String output = buffer.toString(StandardCharsets.UTF_8);
        System.out.print(output); // kept in the test report
        return new Outcome(code, output.lines().toList());
    }

    @Test
    void stripesWiringIsCheckedAtStartup() {
        Outcome outcome = check(
            // No webhook secret (the environment variable is missing).
            "--jabiz.inbound-webhooks.providers.stripe.secrets[0]=",
            // Nor the Connect endpoint's (Q6b).
            "--jabiz.inbound-webhooks.providers.stripe-connect.secrets[0]=",
            // The provider accepts the completions only, while refunds and disputes are consumed too.
            "--jabiz.inbound-webhooks.providers.stripe.event-types[0]=checkout.session.completed",
            // A live key in test mode.
            "--STRIPE_API_KEY=sk_live_not_a_real_key");
        assertThat(outcome.exitCode()).isNotZero();
        assertThat(outcome.lines())
            .anySatisfy(line -> assertThat(line).startsWith("INBOUND | jabiz.inbound-webhooks.providers.stripe"
                + ".secrets[0]"))
            .anySatisfy(line -> assertThat(line).startsWith("INBOUND | EventSubscription qb.topup-refund")
                .contains("charge.refunded"))
            .anySatisfy(line -> assertThat(line).startsWith("INBOUND | EventSubscription qb.topup-expire"))
            .anySatisfy(line -> assertThat(line).startsWith("INBOUND | jabiz.inbound-webhooks.providers.stripe-connect"
                + ".secrets[0]"))
            .anySatisfy(line -> assertThat(line).startsWith("QUIZBUKS | STRIPE_API_KEY").contains("test key"))
            // The development credit (on in the tests) next to a live key.
            .anySatisfy(line -> assertThat(line).startsWith("QUIZBUKS | quizbuks.wallet.dev-credit.enabled"));
    }

    @Test
    void liveModeRefusesTheDevelopmentCreditAndATestKey() {
        Outcome outcome = check("--quizbuks.stripe.livemode=true");
        assertThat(outcome.exitCode()).isNotZero();
        assertThat(outcome.lines())
            .anySatisfy(line -> assertThat(line).startsWith("QUIZBUKS | STRIPE_API_KEY").contains("live key"))
            .anySatisfy(line -> assertThat(line).startsWith("QUIZBUKS | quizbuks.wallet.dev-credit.enabled"));
    }
}
