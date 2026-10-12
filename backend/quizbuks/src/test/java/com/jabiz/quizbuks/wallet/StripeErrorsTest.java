package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalJobException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StripeErrorsTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
        "429, null, true, STRIPE_RATE_LIMITED",
        "400, rate_limit, true, STRIPE_RATE_LIMITED",
        "500, null, true, STRIPE_UNAVAILABLE",
        "503, api_error, true, STRIPE_UNAVAILABLE",
        "409, null, true, STRIPE_CONFLICT",
        "400, lock_timeout, true, STRIPE_CONFLICT",
        "400, balance_insufficient, true, STRIPE_BALANCE_INSUFFICIENT",
        "401, null, false, STRIPE_AUTH",
        "403, null, false, STRIPE_AUTH",
        "404, resource_missing, false, STRIPE_NOT_FOUND",
        "400, parameter_invalid_integer, false, STRIPE_DECLINED",
        "400, null, false, STRIPE_DECLINED",
        "402, card_declined, false, STRIPE_DECLINED",
        "400, idempotency_key_in_use, false, STRIPE_DECLINED"})
    void classifiesTheAnswers(int status, String code, boolean retryable, String errorCode) {
        StripeErrors.Classification c = StripeErrors.classify(status, code);
        assertThat(c.retryable()).isEqualTo(retryable);
        assertThat(c.code()).isEqualTo(errorCode);
        ExternalJobException failure = StripeErrors.failure(status, code);
        assertThat(failure.isPermanent()).isEqualTo(!retryable);
        assertThat(failure.code()).isEqualTo(errorCode);
    }

    @Test
    void theMessageNamesTheStatusAndStripesCodeOnly() {
        assertThat(StripeErrors.failure(400, "parameter_missing").getMessage())
            .isEqualTo("Stripe answered 400 (parameter_missing)");
        // Not a code: not repeated (it could be anything the other side sent).
        assertThat(StripeErrors.failure(400, "Bearer sk_test_123 <script>").getMessage())
            .isEqualTo("Stripe answered 400");
        assertThat(StripeErrors.badResponse("has no session").isPermanent()).isFalse();
    }
}
