package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a top-up's Checkout Session ({@code GET /v1/checkout/sessions/{id}}) when Stripe's events about it seem lost
 * (docs/quizbuks/plans/Q6-payments.md, D-Q6-4). Only a session id of Stripe's shape goes into the path.
 */
@Component
public class SessionCheckHandler
    implements ExternalJobHandler<TopUpJobs.SessionCheckInput, StripeObjects.CheckoutSession> {

    private final StripeSettings settings;
    private final JsonMapper json;

    public SessionCheckHandler(StripeSettings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    public ExternalJobResult<StripeObjects.CheckoutSession> run(TopUpJobs.SessionCheckInput input,
        ExternalJobContext ctx) {
        if (!StripeIds.is(StripeIds.CHECKOUT_SESSION, input.sessionId())) {
            throw ExternalJobException.permanent("QB_BAD_SESSION_ID", "Not a Checkout Session id");
        }
        StripeObjects.CheckoutSession session = StripeObjects.session(StripeApi.of(ctx, settings, json)
            .get("/v1/checkout/sessions/" + input.sessionId(), StripeForm.of()));
        if (session.id() == null || session.status() == null) {
            throw StripeErrors.badResponse("has no session");
        }
        return ExternalJobResult.of(session);
    }
}
