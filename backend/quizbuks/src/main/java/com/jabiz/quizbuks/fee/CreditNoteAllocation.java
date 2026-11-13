package com.jabiz.quizbuks.fee;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * F-M7 (docs/quizbuks/plans/impl/F-M.md §10.3-4, N1, C1, C2): where a credit note's total goes, one rule for the notes
 * made by the refund job and by hand, prepaid and postpaid, pure Java.
 * <ol>
 *   <li>What the fee's own invoice still owes, while it is unpaid ({@code QbFees.UNPAID}, OVERDUE included).</li>
 *   <li>Only for a prepaid fee: back to where it was paid from, at most what was paid less what came back, the parts in
 *       the reverse of the order they were paid in (the last paid first; within a payment, the cash before the fee
 *       credit). A part paid from a prepayment goes back through it; a part paid from the account straight back to
 *       it. A postpaid fee, paid early or not, is never given cash.</li>
 *   <li>The rest is credit: the sponsor's other unpaid invoices, the earliest issued first (then by number); what is
 *       left after them stays for the next invoice.</li>
 * </ol>
 */
public final class CreditNoteAllocation {

    private CreditNoteAllocation() {}

    /**
     * One part of what paid the fee, as written ({@code QbFeePayment}): positive when paid, negative when given back.
     *
     * @param subAccount   {@code FEE_CREDIT} or {@code CASH}
     * @param lotId        the grant or the cash lot
     * @param prepaymentId the prepayment it was paid from; none when paid from the account
     * @param amount       the part's amount (negative: given back)
     * @param paidAt       when it was written
     * @param order        its order among the parts written at the same time (the payment's order)
     */
    public record Part(String subAccount, String lotId, String prepaymentId, BigDecimal amount, Instant paidAt,
        int order) {

        public Part {
            Objects.requireNonNull(subAccount, "subAccount");
            Objects.requireNonNull(lotId, "lotId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(paidAt, "paidAt");
        }
    }

    /** An unpaid invoice of the sponsor's: what it owes, when and as what it was issued. */
    public record Invoice(String invoiceId, String invoiceNo, Instant issuedAt, BigDecimal owed) {}

    /** What goes back to one lot ({@code prepaymentId}: through that prepayment). */
    public record Back(String subAccount, String lotId, String prepaymentId, BigDecimal amount) {}

    /** What is applied to one invoice. */
    public record Applied(String invoiceId, BigDecimal amount) {}

    /**
     * @param ownInvoice applied to the fee's own invoice (step 1)
     * @param back       given back, lot by lot (step 2)
     * @param others     applied to the sponsor's other unpaid invoices (step 3)
     * @param unused     left as credit for the next invoice
     */
    public record Result(BigDecimal ownInvoice, List<Back> back, List<Applied> others, BigDecimal unused) {

        public BigDecimal backTotal() {
            return back.stream().map(Back::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** Of what goes back, to the fee credit (by the prepayment or not). */
        public BigDecimal backCredit() {
            return back.stream().filter(b -> QbFeeAccount.FEE_CREDIT.equals(b.subAccount())).map(Back::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal appliedTotal() {
            return ownInvoice.add(others.stream().map(Applied::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
    }

    /**
     * Allocates a note's {@code total}.
     *
     * @param prepaid    the fee was charged prepaid (its {@code billingMode})
     * @param ownOwed    what the fee's own invoice owes now (0 when it is paid, void, or there is none yet)
     * @param parts      every part of what paid the fee (and was given back)
     * @param others     the sponsor's other unpaid invoices
     */
    public static Result allocate(BigDecimal total, boolean prepaid, BigDecimal ownOwed, List<Part> parts,
        List<Invoice> others) {
        BigDecimal left = total.max(BigDecimal.ZERO);
        BigDecimal own = left.min(ownOwed == null ? BigDecimal.ZERO : ownOwed.max(BigDecimal.ZERO));
        left = left.subtract(own);
        List<Back> back = new ArrayList<>();
        if (prepaid && left.signum() > 0) {
            back = giveBack(parts, left);
            left = left.subtract(back.stream().map(Back::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        List<Applied> applied = new ArrayList<>();
        List<Invoice> oldest = others.stream().filter(i -> i.owed().signum() > 0)
            .sorted(Comparator.comparing(Invoice::issuedAt).thenComparing(Invoice::invoiceNo)).toList();
        for (Invoice invoice : oldest) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal take = invoice.owed().min(left);
            applied.add(new Applied(invoice.invoiceId(), take));
            left = left.subtract(take);
        }
        return new Result(own, List.copyOf(back), List.copyOf(applied), left);
    }

    /**
     * What may go back to each lot (its parts' sum, never below 0), taken in the reverse of the order the parts were
     * paid in, at most {@code amount} in all.
     */
    static List<Back> giveBack(List<Part> parts, BigDecimal amount) {
        record Key(String subAccount, String lotId, String prepaymentId) {}
        Map<Key, BigDecimal> net = new LinkedHashMap<>();
        Map<Key, Part> last = new LinkedHashMap<>();
        for (Part part : parts) {
            Key key = new Key(part.subAccount(), part.lotId(), part.prepaymentId());
            net.merge(key, part.amount(), BigDecimal::add);
            if (part.amount().signum() > 0) {
                Part seen = last.get(key);
                if (seen == null || later(part, seen)) {
                    last.put(key, part);
                }
            }
        }
        List<Key> order = new ArrayList<>(last.keySet());
        order.sort((a, b) -> later(last.get(a), last.get(b)) ? -1 : later(last.get(b), last.get(a)) ? 1 : 0);
        BigDecimal left = amount;
        List<Back> back = new ArrayList<>();
        for (Key key : order) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal open = net.getOrDefault(key, BigDecimal.ZERO);
            if (open.signum() <= 0) {
                continue;
            }
            BigDecimal take = open.min(left);
            back.add(new Back(key.subAccount(), key.lotId(), key.prepaymentId(), take));
            left = left.subtract(take);
        }
        return back;
    }

    /** Whether {@code a} was paid after {@code b}: later, or written later in the same payment. */
    private static boolean later(Part a, Part b) {
        int byTime = a.paidAt().compareTo(b.paidAt());
        return byTime != 0 ? byTime > 0 : a.order() > b.order();
    }
}
