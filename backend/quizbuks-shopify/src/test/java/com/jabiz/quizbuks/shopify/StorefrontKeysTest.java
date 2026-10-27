package com.jabiz.quizbuks.shopify;

import com.jabiz.quizbuks.channel.Channel;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keys the storefront gives the engine and the codes it makes (docs/quizbuks/shopify/S2-storefront.md, S2-4):
 * a visitor's key is a digest of the shop and the browser's key, never the key, and fits the engine's taker pattern;
 * a code is the pool's form and shows only its last characters.
 */
class StorefrontKeysTest {

    @Test
    void aVisitorIsADigestOfTheShopAndTheirKey() {
        String visitor = "0b1f6a3e-2c4d-4e5f-8a9b-1c2d3e4f5a6b";
        String taker = QbsStorefront.visitorTaker("demo.myshopify.com", visitor);
        assertThat(taker).startsWith("qbs:v:").doesNotContain(visitor).matches(Channel.TAKER_PATTERN);
        assertThat(QbsStorefront.visitorTaker("demo.myshopify.com", visitor)).isEqualTo(taker);
        // The same key in another shop is another person.
        assertThat(QbsStorefront.visitorTaker("other.myshopify.com", visitor)).isNotEqualTo(taker);
        assertThat(QbsStorefront.customerTaker("0190a7c2-0000-7000-8000-000000000001"))
            .matches(Channel.TAKER_PATTERN);
    }

    @Test
    void codesAreOfThePoolsFormAndShowTheirLastCharacters() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String code = DiscountPool.CallShopify.code();
            assertThat(code).matches("QB-[2-9A-HJ-NP-Z]{10}");
            codes.add(code);
        }
        assertThat(codes).hasSize(1000);
        assertThat(DiscountPool.hint("QB-ABCDEFGHJK")).isEqualTo("…GHJK").hasSizeLessThanOrEqualTo(
            QbsStorefront.HINT_LENGTH);
    }
}
