package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Reads Stripe's fee of a top-up's payment (F-04; docs/quizbuks/plans/Q6-payments.md, D-Q6-5): the payment with its
 * latest charge and that charge's balance transaction expanded
 * ({@code GET /v1/payment_intents/{id}?expand[]=latest_charge.balance_transaction}). A charge whose balance transaction
 * is not there yet is asked again later.
 */
@Component
public class PaymentFeeHandler implements ExternalJobHandler<TopUpJobs.FeeInput, TopUpChecks.Fee> {

    private final StripeSettings settings;
    private final JsonMapper json;

    public PaymentFeeHandler(StripeSettings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    public ExternalJobResult<TopUpChecks.Fee> run(TopUpJobs.FeeInput input, ExternalJobContext ctx) {
        if (!StripeIds.is(StripeIds.PAYMENT_INTENT, input.paymentIntentId())) {
            throw ExternalJobException.permanent("QB_BAD_PAYMENT_ID", "Not a PaymentIntent id");
        }
        Map<String, Object> payment = StripeApi.of(ctx, settings, json).get("/v1/payment_intents/"
            + input.paymentIntentId(), StripeForm.of().with("expand[]", "latest_charge.balance_transaction"));
        Map<String, Object> charge = StripeObjects.map(payment.get("latest_charge"));
        Map<String, Object> balance = StripeObjects.map(charge.get("balance_transaction"));
        if (balance.isEmpty()) {
            throw StripeErrors.badResponse("has no balance transaction yet");
        }
        return ExternalJobResult.of(new TopUpChecks.Fee(StripeObjects.text(charge, "id"),
            StripeObjects.text(balance, "id"), StripeObjects.decimal(balance.get("amount")),
            StripeObjects.decimal(balance.get("fee")), StripeObjects.decimal(balance.get("net")),
            StripeObjects.text(balance, "currency")));
    }
}
