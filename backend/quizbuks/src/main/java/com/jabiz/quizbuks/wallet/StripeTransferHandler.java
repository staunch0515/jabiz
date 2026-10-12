package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Sends a transfer to a taker's Stripe account (docs/quizbuks/plans/Q6-payments.md, D-Q6-12), so that it is made at
 * most once whatever happens to the job:
 * <ol>
 *   <li>it first lists the transfer's group ({@code GET /v1/transfers?transfer_group=qb-<transfer>}): a transfer made
 *       by an earlier attempt (whose answer was lost, or whose lease expired) is the result, nothing is sent again;</li>
 *   <li>otherwise {@code POST /v1/transfers} of the record's net amount in yen to the record's account, in the group,
 *       with the transfer's own idempotency key (not the job's).</li>
 * </ol>
 * A failure of the lookup is never certain ({@value #LOOKUP_FAILED}): only Stripe's refusal of the creation itself
 * tells that no transfer exists ({@link TransferRules#certainFailure}). An answer Stripe keeps under the key ends the
 * job at once ({@link #kept}).
 */
@Component
public class StripeTransferHandler
    implements ExternalJobHandler<PayoutJobs.TransferInput, PayoutObjects.TransferLookup> {

    /** The lookup before the creation failed: whether a transfer exists is not known. */
    public static final String LOOKUP_FAILED = "STRIPE_LOOKUP_FAILED";

    private final StripeSettings settings;
    private final JsonMapper json;

    public StripeTransferHandler(StripeSettings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    public ExternalJobResult<PayoutObjects.TransferLookup> run(PayoutJobs.TransferInput input,
        ExternalJobContext ctx) {
        StripeApi api = StripeApi.of(ctx, settings, json);
        PayoutObjects.TransferLookup existing = lookup(api, input.transferId());
        if (existing.found()) {
            return ExternalJobResult.of(existing);
        }
        Map<String, Object> created;
        try {
            created = api.post("/v1/transfers", StripeForm.of()
                .with("amount", input.net().toBigIntegerExact())
                .with("currency", StripeService.CURRENCY)
                .with("destination", input.destination())
                .with("transfer_group", PayoutChecks.transferGroup(input.transferId()))
                .with("metadata[transferId]", input.transferId()), transferKey(input.transferId()));
        } catch (ExternalJobException e) {
            throw kept(e);
        }
        PayoutObjects.Transfer transfer = PayoutObjects.transfer(created);
        if (transfer.id() == null) {
            // The creation answered without a transfer: whether one exists is not known.
            throw ExternalJobException.retryable(StripeErrors.BAD_RESPONSE, "Stripe's answer has no transfer id");
        }
        return ExternalJobResult.of(PayoutObjects.TransferLookup.of(transfer));
    }

    /**
     * Stripe keeps the answer of a request that ran under an idempotency key, errors included, and gives it again:
     * retrying an insufficient balance or a 500 under the transfer's key can never pass. Both end the job at once: the
     * balance as a certain refusal (reversed; the next day's transfer has a key of its own), the 500 in doubt (asked
     * about later, {@link TransferRules#certainFailure}). Answers given before the request ran (429, 409, a lock) are
     * not kept and are tried again.
     */
    static ExternalJobException kept(ExternalJobException e) {
        if (!e.isPermanent()
            && (StripeErrors.BALANCE_INSUFFICIENT.equals(e.code()) || StripeErrors.UNAVAILABLE.equals(e.code()))) {
            return ExternalJobException.permanent(e.code(), e.getMessage());
        }
        return e;
    }

    /** The transfer of the group, or none; any failure of the lookup leaves it in doubt. */
    static PayoutObjects.TransferLookup lookup(StripeApi api, String transferId) {
        PayoutObjects.TransferLookup found;
        try {
            found = PayoutObjects.firstOfList(api.get("/v1/transfers", StripeForm.of()
                .with("transfer_group", PayoutChecks.transferGroup(transferId)).with("limit", 1)));
        } catch (ExternalJobException e) {
            throw ExternalJobException.retryable(LOOKUP_FAILED, "Listing the transfer's group failed: "
                + e.getMessage());
        }
        if (found == null) {
            throw ExternalJobException.retryable(LOOKUP_FAILED, "Stripe's list of the transfer's group is not a list");
        }
        return found;
    }

    /** The key of the transfer's creation: one transfer per record, whichever job or attempt asks. */
    static String transferKey(String transferId) {
        return "qb-transfer-" + transferId;
    }
}
