package com.jabiz.quizbuks.play;

import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.TransferRules;
import com.jabiz.quizbuks.wallet.TransferStatus;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A quiz's entry fee as the start charges it (Q88b, docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md,
 * D-Q88-9 to D-Q88-12): what a card charges, whether the taker can pay it, and its posting. Pure: the start reads
 * (under the taker's wallet lock, before the publication's start lock) and asks here.
 */
public final class EntryFees {

    /** The posting's description (the monthly report's class {@code ENTRY_FEE} is by source and accounts). */
    public static final String DESCRIPTION = "Entry fee";

    private EntryFees() {}

    /** The fee a card charges: a sponsor's publication's {@code entryFee} above 0; nothing for a channel's. */
    public static BigDecimal fee(EntityInstance card) {
        if (card == null || !PublisherKind.sponsor(card.get("publisherKind"))) {
            return BigDecimal.ZERO;
        }
        BigDecimal fee = PlaySteps.money(card.get("entryFee"));
        return fee.signum() > 0 ? fee : BigDecimal.ZERO;
    }

    public static boolean charges(EntityInstance card) {
        return fee(card).signum() > 0;
    }

    /**
     * The hold period the transferable balance is computed with, from the parameter's row as the dataset gives it
     * ({@code qb.transfer.hold-days}). Empty when it is missing or unusable: the start then refuses the fee, as the
     * transfers refuse to send (nothing can be told free of the hold).
     */
    public static Optional<BigDecimal> holdDays(List<EntityInstance> paramRows) {
        for (EntityInstance row : paramRows) {
            Object value = row.get("value");
            try {
                BigDecimal days = value == null ? null : new BigDecimal(value.toString().strip());
                if (TransferRules.wholeAndNotNegative(days)) {
                    return Optional.of(days);
                }
            } catch (NumberFormatException ignored) {
                // Unusable: refused.
            }
        }
        return Optional.empty();
    }

    /**
     * What the taker's transfers in review set aside (nothing is posted until they are approved, and the approval
     * sends them only if the transferable balance still covers them): a fee cannot spend it.
     */
    public static BigDecimal inReview(List<EntityInstance> transfers) {
        BigDecimal total = BigDecimal.ZERO;
        for (EntityInstance transfer : transfers) {
            if (TransferStatus.REVIEW.equals(transfer.get("status"))) {
                total = total.add(PlaySteps.money(transfer.get("amount")));
            }
        }
        return total;
    }

    /**
     * Why the taker cannot pay {@code fee} now (D-Q88-10): their transferable Kudos (the 2120 balance less what the
     * transfers hold back and less what a transfer in review sets aside) must cover it, and so must the wallet's
     * cached balance (the cache follows the ledger by events and is reduced at the version read under the lock;
     * {@code qb_wallet.balance >= 0} backs it). Empty when they can.
     */
    public static Optional<Violation> shortfall(BigDecimal available, BigDecimal held, BigDecimal inReview,
        EntityInstance wallet, BigDecimal fee) {
        BigDecimal transferable = nonNull(available).subtract(nonNull(held)).subtract(nonNull(inReview))
            .max(BigDecimal.ZERO);
        BigDecimal cached = QbWallets.balance(wallet);
        BigDecimal usable = transferable.min(cached);
        if (usable.compareTo(fee) >= 0) {
            return Optional.empty();
        }
        BigDecimal missing = fee.subtract(usable);
        return Optional.of(new Violation("publicationId", PlayCodes.ENTRY_FEE_SHORT, "The entry fee of " + plain(fee)
            + " is not covered by the transferable Kudos (" + plain(usable) + ")", Map.of("fee", plain(fee),
            "available", plain(usable), "short", plain(missing))));
    }

    /**
     * The posting of an entry fee (D-Q88-11): the taker's wallet pays it (2120, debit), the sponsor is owed it until
     * the daily settlement moves it to their cash account (2170, credit). Both lines carry the publication; the
     * wallet lines the party. Its source is the attempt.
     */
    public static LedgerProcesses.PostInput posting(Instant bookingTime, String attemptId, String userId,
        String sponsorId, String publicationId, BigDecimal fee) {
        List<LedgerProcesses.Line> lines = List.of(
            new LedgerProcesses.Line(QbLedger.REWARDS_PAYABLE, Direction.DEBIT, fee, null,
                Map.of(QbLedger.PARTY, userId, QbWallets.PUBLICATION, publicationId)),
            new LedgerProcesses.Line(QbLedger.ENTRY_FEES_UNSETTLED, Direction.CREDIT, fee, null,
                Map.of(QbLedger.PARTY, sponsorId, QbWallets.PUBLICATION, publicationId)));
        return new LedgerProcesses.PostInput(bookingTime, DESCRIPTION, attemptId, lines, QbPlay.ATTEMPT, attemptId);
    }

    /** An amount in Kudos as the texts show it (the ledger's carry four decimals, always 0 in JPY). */
    private static String plain(BigDecimal value) {
        return value.setScale(0, java.math.RoundingMode.DOWN).toPlainString();
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
