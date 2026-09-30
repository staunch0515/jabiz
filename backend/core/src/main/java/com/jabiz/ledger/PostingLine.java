package com.jabiz.ledger;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * One entry of a transaction to post: an amount on one side of one account.
 *
 * @param accountCode code of the account ({@code LedgerAccount.accountCode})
 * @param amount      positive amount in the ledger currency
 * @param memo        optional note of the line
 * @param dimensions  values of analysis dimensions by name ({@link LedgerDimension}); empty for none
 * @param foreign     the transaction-currency amount and rate of an entry in a foreign currency; null for an entry in
 *                    the ledger currency. {@code amount} is then its converted value
 */
public record PostingLine(String accountCode, Direction direction, BigDecimal amount, String memo,
    Map<String, String> dimensions, ForeignAmount foreign) {

    /** Longest memo of a line. */
    public static final int MAX_MEMO = 500;

    public PostingLine {
        Objects.requireNonNull(accountCode, "accountCode must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
    }

    public PostingLine(String accountCode, Direction direction, BigDecimal amount) {
        this(accountCode, direction, amount, null, Map.of(), null);
    }

    public PostingLine(String accountCode, Direction direction, BigDecimal amount, String memo,
        Map<String, String> dimensions) {
        this(accountCode, direction, amount, memo, dimensions, null);
    }

    /** The same amounts on the other side of the same account, with the same memo, dimensions and rate. */
    public PostingLine reversed() {
        return new PostingLine(accountCode, direction.opposite(), amount, memo, dimensions, foreign);
    }
}
