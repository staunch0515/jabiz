package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Creates a top-up's Checkout Session ({@code POST /v1/checkout/sessions}) from the form {@link CheckoutRequest} makes
 * of the server's record. The idempotency key is the top-up's own, not the job's: however often and by whichever job
 * it is asked, Stripe creates one checkout per top-up (within the day it keeps keys). The answer is checked by the
 * completion process, not here.
 */
@Component
public class CheckoutCreateHandler
    implements ExternalJobHandler<CheckoutRequest.CheckoutInput, StripeObjects.CheckoutSession> {

    private final StripeSettings settings;
    private final JsonMapper json;

    public CheckoutCreateHandler(StripeSettings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    public ExternalJobResult<StripeObjects.CheckoutSession> run(CheckoutRequest.CheckoutInput input,
        ExternalJobContext ctx) {
        Map<String, Object> answer = StripeApi.of(ctx, settings, json).post("/v1/checkout/sessions",
            CheckoutRequest.form(input), CheckoutRequest.idempotencyKey(input.topUpId()));
        StripeObjects.CheckoutSession session = StripeObjects.session(answer);
        if (session.id() == null) {
            throw StripeErrors.badResponse("has no session id");
        }
        return ExternalJobResult.of(session);
    }
}
