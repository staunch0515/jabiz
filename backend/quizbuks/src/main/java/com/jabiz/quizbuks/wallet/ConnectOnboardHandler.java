package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Opens a taker's payout account (docs/quizbuks/plans/Q6-payments.md, D-Q6-10): creates their Express account when
 * they have none ({@code POST /v1/accounts}, an individual of the deployment's country that receives transfers; the key
 * is the user's own, so however often it is asked Stripe creates one account per user within the day it keeps keys),
 * then a link to Stripe's onboarding ({@code POST /v1/account_links}, used once, short-lived). QuizBuks keeps no bank
 * details: Stripe collects them on its pages. The answer is checked by the completion process, not here.
 */
@Component
public class ConnectOnboardHandler implements ExternalJobHandler<PayoutJobs.OnboardInput, PayoutObjects.Onboarding> {

    private final StripeSettings settings;
    private final JsonMapper json;

    public ConnectOnboardHandler(StripeSettings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    public ExternalJobResult<PayoutObjects.Onboarding> run(PayoutJobs.OnboardInput input, ExternalJobContext ctx) {
        StripeApi api = StripeApi.of(ctx, settings, json);
        String accountId = input.accountId();
        String country = input.country();
        Boolean livemode;
        if (accountId == null) {
            Map<String, Object> created = api.post("/v1/accounts", StripeForm.of()
                .with("type", "express")
                .with("country", input.country())
                .with("business_type", "individual")
                .with("capabilities[transfers][requested]", "true")
                .with("metadata[userId]", input.userId()), accountKey(input.userId()));
            PayoutObjects.Account account = PayoutObjects.account(created);
            if (!StripeIds.is(StripeIds.ACCOUNT, account.id())) {
                throw StripeErrors.badResponse("has no account id");
            }
            accountId = account.id();
            country = account.country();
            // A v1 Account carries no livemode: the mode is that of the key this deployment called with, which
            // StripeModeCheck holds to quizbuks.stripe.livemode. Only an answer that names another mode is refused.
            livemode = account.livemode() == null ? settings.livemode() : account.livemode();
        } else {
            livemode = settings.livemode();
        }
        Map<String, Object> link = api.post("/v1/account_links", StripeForm.of()
            .with("account", accountId)
            .with("type", "account_onboarding")
            .with("return_url", input.returnUrl())
            .with("refresh_url", input.refreshUrl()), ctx.idempotencyKey());
        BigDecimal expires = StripeObjects.decimal(link.get("expires_at"));
        Long expiresAt = null;
        if (expires != null && expires.signum() > 0 && expires.stripTrailingZeros().scale() <= 0
            && expires.compareTo(BigDecimal.valueOf(TopUpChecks.LAST_EXPIRY)) <= 0) {
            expiresAt = expires.longValueExact();
        }
        Object url = link.get("url");
        return ExternalJobResult.of(new PayoutObjects.Onboarding(accountId, country, livemode,
            url instanceof String text ? text : null, expiresAt));
    }

    /** The key of the account's creation: one account per user. */
    static String accountKey(String userId) {
        return "qb-connect-" + userId;
    }
}
