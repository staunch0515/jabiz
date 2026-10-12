package com.jabiz.quizbuks.wallet;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.wallet.StripeObjects.bool;
import static com.jabiz.quizbuks.wallet.StripeObjects.decimal;
import static com.jabiz.quizbuks.wallet.StripeObjects.idOf;
import static com.jabiz.quizbuks.wallet.StripeObjects.map;
import static com.jabiz.quizbuks.wallet.StripeObjects.text;

/**
 * The parts of Stripe's Connect and transfer objects QuizBuks reads (docs/quizbuks/plans/Q6-payments.md, Q6b), as
 * {@link StripeObjects} reads the checkout's: nothing is trusted, a missing or mistyped value is null, and the checks
 * ({@link TransferRules}, {@link PayoutChecks}) decide what a value means.
 */
public final class PayoutObjects {

    /**
     * A connected (Express) account.
     *
     * @param transfersCapability {@code capabilities.transfers}: {@code active}, {@code pending} or {@code inactive}
     * @param requirementsDue     {@code requirements.currently_due}, the names only
     * @param metadataUserId      {@code metadata.userId}, as QuizBuks set it on creation
     */
    public record Account(String id, String country, Boolean payoutsEnabled, Boolean detailsSubmitted,
        String transfersCapability, List<String> requirementsDue, String metadataUserId, Boolean livemode) {

        public Account {
            requirementsDue = requirementsDue == null ? List.of() : List.copyOf(requirementsDue);
        }
    }

    /**
     * What the onboarding job returns: the account (created, or the user's own) and the link to Stripe's onboarding.
     * The link is the user's alone: it is kept out of logs and summaries.
     *
     * @param expiresAt epoch seconds
     */
    public record Onboarding(String accountId, String accountCountry, Boolean livemode, String url,
        Long expiresAt) {

        @Override
        public String toString() {
            return "Onboarding[accountId=" + accountId + ", url=***]";
        }
    }

    /**
     * A transfer to a connected account.
     *
     * @param amountReversed the running total reversed
     * @param transferGroup  QuizBuks's {@code qb-<transferId>}
     */
    public record Transfer(String id, BigDecimal amount, BigDecimal amountReversed, String currency,
        String destination, String transferGroup, String metadataTransferId, Boolean livemode) {}

    /**
     * What a transfer job learned: the transfer QuizBuks's group names, or none.
     *
     * @param found whether Stripe has a transfer of the group
     */
    public record TransferLookup(boolean found, Transfer transfer) {

        public static TransferLookup none() {
            return new TransferLookup(false, null);
        }

        public static TransferLookup of(Transfer transfer) {
            return new TransferLookup(true, transfer);
        }
    }

    /** A payout of the platform's Stripe balance to its bank account. */
    public record Payout(String id, BigDecimal amount, String currency, String status, Boolean livemode) {}

    public static Account account(Object value) {
        Map<String, Object> o = map(value);
        Map<String, Object> capabilities = map(o.get("capabilities"));
        Map<String, Object> requirements = map(o.get("requirements"));
        List<String> due = new ArrayList<>();
        if (requirements.get("currently_due") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String name) {
                    due.add(name);
                }
            }
        }
        return new Account(text(o, "id"), text(o, "country"), bool(o.get("payouts_enabled")),
            bool(o.get("details_submitted")), text(capabilities, "transfers"), due,
            text(map(o.get("metadata")), "userId"), bool(o.get("livemode")));
    }

    public static Transfer transfer(Object value) {
        Map<String, Object> o = map(value);
        return new Transfer(text(o, "id"), decimal(o.get("amount")), decimal(o.get("amount_reversed")),
            text(o, "currency"), idOf(o.get("destination")), text(o, "transfer_group"),
            text(map(o.get("metadata")), "transferId"), bool(o.get("livemode")));
    }

    /** The first transfer of a list answer ({@code {"object": "list", "data": [...]}}); none for an empty list. */
    public static TransferLookup firstOfList(Map<String, Object> answer) {
        Object data = answer.get("data");
        if (!(data instanceof List<?> list) || !"list".equals(answer.get("object"))) {
            return null;
        }
        return list.isEmpty() ? TransferLookup.none() : TransferLookup.of(transfer(list.getFirst()));
    }

    public static Payout payout(Object value) {
        Map<String, Object> o = map(value);
        return new Payout(text(o, "id"), decimal(o.get("amount")), text(o, "currency"), text(o, "status"),
            bool(o.get("livemode")));
    }

    private PayoutObjects() {}
}
