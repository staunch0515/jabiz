package com.jabiz.quizbuks.journal;

import java.time.Duration;

/**
 * The limits an export works within (review H1, M1), from the platform's settings: a template read by a process gives
 * at most {@code maxReadRows} rows ({@code jabiz.process.max-read-rows}, per read), a generated file holds at most
 * {@code maxFileBytes} ({@code jabiz.files.generated.max-bytes}). The detail is cut into parts aiming at half of each
 * (template {@code qb.system.journal-parts}), so that a part stays within both while no single transaction is over half
 * of either. {@code maxOpenTransaction}: another database transaction open this long stops the export
 * ({@code quizbuks.journal.max-open-transaction}); it must stay well under {@link JournalPeriods#SETTLE}.
 *
 * @param maxReadRows        the platform's limit of one read
 * @param maxFileBytes       the platform's limit of one generated file
 * @param maxOpenTransaction how long another transaction may be open when an export reads
 */
public record JournalLimits(int maxReadRows, long maxFileBytes, Duration maxOpenTransaction) {

    /** Room for a file's byte order mark and header line, kept out of a part's budget. */
    static final int HEADER_BYTES = 512;

    public JournalLimits {
        if (maxReadRows < 2 || maxFileBytes < 2L * HEADER_BYTES) {
            throw new IllegalArgumentException("The platform's limits are too small for the journal export: "
                + maxReadRows + " rows, " + maxFileBytes + " bytes");
        }
        if (maxOpenTransaction.isNegative() || maxOpenTransaction.compareTo(JournalPeriods.SETTLE.dividedBy(2)) > 0) {
            throw new IllegalArgumentException("quizbuks.journal.max-open-transaction must be between 0 and half of "
                + JournalPeriods.SETTLE + ", was " + maxOpenTransaction);
        }
    }

    /** The rows a part aims at. */
    public long partRows() {
        return maxReadRows / 2;
    }

    /** The bytes a part aims at. */
    public long partBytes() {
        return (maxFileBytes - HEADER_BYTES) / 2;
    }

    /**
     * Whether an export comes near the limits (four fifths of one): the voucher's lines (one read), its file, or the
     * largest transaction's share of a part.
     */
    public boolean near(long voucherLines, long voucherBytes, long maxTransactionRows, long maxTransactionBytes) {
        return voucherLines * 5 >= maxReadRows * 4L || voucherBytes * 5 >= maxFileBytes * 4
            || maxTransactionRows * 5 >= partRows() * 4 || maxTransactionBytes * 5 >= partBytes() * 4;
    }

    /** Whether the parts would stay within the limits: no transaction over half of either. */
    public boolean fits(long maxTransactionRows, long maxTransactionBytes) {
        return maxTransactionRows <= partRows() && maxTransactionBytes <= partBytes();
    }
}
