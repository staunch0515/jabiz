package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address prefixes of Stripe's pages in test mode: whatever starts with the prefix must be on its host, so the
 * prefix is a whole origin and path ending in {@code /}, https, or http on this machine only (a local stub).
 */
class StripeUrlsTest {

    static final List<String> ACCEPTED = List.of("https://checkout.stripe.com/", "https://connect.stripe.com/",
        "https://checkout.stripe.com/c/", "https://stub.example.com:8443/stripe/pay/", "http://localhost:8093/",
        "http://localhost/", "http://127.0.0.1:8093/", "http://[::1]:8093/", "http://localhost:8093/stub/");

    static final List<String> REJECTED = List.of("https://", "https:///", "https://checkout.stripe.com",
        "https://checkout.stripe.com/c", "https://checkout.stripe.com:8443", "https://user@checkout.stripe.com/",
        "https://u:p@checkout.stripe.com/", "https://evil.example@checkout.stripe.com/",
        "https://checkout.stripe.com/?a=/", "https://checkout.stripe.com/#/", "https://checkout.stripe.com/a?/",
        "http://checkout.stripe.com/", "http://example.com:8093/", "http://localhost.evil.example/",
        "http://127.0.0.1.evil.example:8093/", "http://[::2]:8093/", "ftp://checkout.stripe.com/",
        "HTTPS://checkout.stripe.com/", "//checkout.stripe.com/", "checkout.stripe.com/",
        "https://checkout.stripe.com\\@evil.example/", "https://checkout.stripe.com/a\\b/",
        "https://check out.stripe.com/", "https://checkout.stripe.com/a b/", "https://checkout.stripe.com:/",
        "https://checkout.stripe.com:99999/", "https://checkout.stripe.com:0/", "https://checkout..stripe.com/",
        "https://-checkout.stripe.com/", "https://checkout.stripe.com/\u0000/", "javascript:alert(1)//");

    @Test
    void acceptsAnOriginWithAPathEndingInASlash() {
        for (String prefix : ACCEPTED) {
            assertThat(StripeUrls.prefixProblem(prefix)).as(prefix).isNull();
            assertThat(settings(prefix).problems()).as(prefix).isEmpty();
            assertThat(payouts(prefix).problems(false)).as(prefix).isEmpty();
        }
    }

    @Test
    void rejectsWhatWouldLetAnotherHostThrough() {
        for (String prefix : REJECTED) {
            assertThat(StripeUrls.prefixProblem(prefix)).as(prefix).isNotNull();
            assertThat(settings(prefix).problems()).as(prefix).anySatisfy(p -> assertThat(p).startsWith(
                "quizbuks.stripe.checkout-url-prefix:"));
            assertThat(payouts(prefix).problems(false)).as(prefix).anySatisfy(p -> assertThat(p).startsWith(
                "quizbuks.stripe.connect-url-prefix:"));
        }
    }

    @Test
    void anUnconfiguredPrefixIsStripes() {
        assertThat(settings(null).problems()).isEmpty();
        assertThat(settings(" ").checkoutUrlPrefix()).isEqualTo(StripeSettings.STRIPE_CHECKOUT);
        assertThat(payouts(null).problems(true)).isEmpty();
    }

    @Test
    void liveModeStillTakesStripesOwnAddressesOnly() {
        assertThat(live(StripeSettings.STRIPE_CHECKOUT).problems()).isEmpty();
        assertThat(live("https://checkout.stripe.com/c/").problems()).singleElement().asString()
            .contains("live mode");
        assertThat(live("http://localhost:8093/").problems()).singleElement().asString().contains("live mode");
        assertThat(payouts(PayoutSettings.STRIPE_CONNECT).problems(true)).isEmpty();
        assertThat(payouts("https://connect.stripe.com/x/").problems(true)).singleElement().asString()
            .contains("live mode");
    }

    @Test
    void anAddressWithWhitespaceOrControlCharactersIsNotPlain() {
        assertThat(StripeUrls.plain("https://checkout.stripe.com/c/pay/cs_test_1#abc")).isTrue();
        for (String bad : List.of("https://a/ b", "https://a/\tb", "https://a/\nb", "https://a/\rb", "https://a/\u0000",
            "https://a/\u007f", "https://a/\u001f")) {
            assertThat(StripeUrls.plain(bad)).as(bad).isFalse();
        }
    }

    static StripeSettings settings(String prefix) {
        return new StripeSettings(false, "2025-03-31.basil", Duration.ofHours(1), prefix,
            "https://q.example.com/sponsor/");
    }

    static StripeSettings live(String prefix) {
        return new StripeSettings(true, "2025-03-31.basil", Duration.ofHours(1), prefix,
            "https://q.example.com/sponsor/");
    }

    static PayoutSettings payouts(String prefix) {
        return new PayoutSettings("JP", "https://app.example.com/", prefix);
    }
}
