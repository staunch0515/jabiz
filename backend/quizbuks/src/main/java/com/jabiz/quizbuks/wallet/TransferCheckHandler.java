package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks Stripe whether a transfer in doubt was made (docs/quizbuks/plans/Q6-payments.md, D-Q6-12): lists the transfer's
 * group only, never creates one. The completion process settles a found transfer, or reverses the posting of one that
 * certainly does not exist: one asked about well after the sending ended (the attempt's start,
 * {@link PayoutObjects.TransferLookup#lookedUpFrom}).
 */
@Component
public class TransferCheckHandler
    implements ExternalJobHandler<PayoutJobs.TransferInput, PayoutObjects.TransferLookup> {

    private final StripeSettings settings;
    private final JsonMapper json;

    public TransferCheckHandler(StripeSettings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    public ExternalJobResult<PayoutObjects.TransferLookup> run(PayoutJobs.TransferInput input,
        ExternalJobContext ctx) {
        // When the attempt began bounds when Stripe was asked from below: only a "none" asked well after the sending
        // ended is final (TransferProcesses.found).
        return ExternalJobResult.of(StripeTransferHandler.lookup(StripeApi.of(ctx, settings, json),
            input.transferId()).askedFrom(ctx.deadline().minus(PayoutJobs.CHECK_TIMEOUT)));
    }
}
