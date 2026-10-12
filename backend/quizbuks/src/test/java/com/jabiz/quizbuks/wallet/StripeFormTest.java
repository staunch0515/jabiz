package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeFormTest {

    @Test
    void writesNestedKeysWithBracketsInTheOrderGiven() {
        StripeForm form = StripeForm.of()
            .with("mode", "payment")
            .with("line_items[0][price_data][unit_amount]", new BigDecimal("5000"))
            .with("expand[]", "latest_charge.balance_transaction");
        assertThat(form.encode()).isEqualTo("mode=payment&line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D=5000"
            + "&expand%5B%5D=latest_charge.balance_transaction");
        assertThat(form.get("mode")).isEqualTo("payment");
        assertThat(form.get("missing")).isNull();
    }

    @Test
    void escapesValuesSoThatNoValueAddsAField() {
        StripeForm form = StripeForm.of().with("metadata[note]", "a&b=c d+é/?#");
        assertThat(form.encode()).isEqualTo("metadata%5Bnote%5D=a%26b%3Dc+d%2B%C3%A9%2F%3F%23");
        assertThat(form.encode().split("&")).hasSize(1);
    }

    @Test
    void addsNothingForANullValueAndStaysUnchanged() {
        StripeForm empty = StripeForm.of();
        StripeForm form = empty.with("a", null);
        assertThat(form.isEmpty()).isTrue();
        assertThat(empty.with("a", 1)).isNotSameAs(empty);
        assertThat(empty.isEmpty()).isTrue();
        assertThat(StripeForm.of().with("big", new BigDecimal("1E+3")).encode()).isEqualTo("big=1000");
    }

    @Test
    void printsTheKeysOnly() {
        assertThat(StripeForm.of().with("metadata[sponsorId]", "secret-ish").toString())
            .contains("metadata[sponsorId]").doesNotContain("secret-ish");
    }

    @Test
    void refusesAnEmptyKey() {
        assertThatThrownBy(() -> StripeForm.of().with("", "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
