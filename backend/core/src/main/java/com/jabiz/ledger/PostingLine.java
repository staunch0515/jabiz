package com.jabiz.ledger;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One entry of a transaction to post: an amount on one side of one account.
 *
 * @param accountCode code of the account ({@code LedgerAccount.accountCode})
 * @param amount      positive amount in the ledger currency
 */
public record PostingLine(String accountCode, Direction direction, BigDecimal amount) {

    public PostingLine {
        Objects.requireNonNull(accountCode, "accountCode must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }

    /** The same amount on the other side of the same account. */
    public PostingLine reversed() {
        return new PostingLine(accountCode, direction.opposite(), amount);
    }
}
