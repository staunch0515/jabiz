package com.jabiz.ledger;

/** Side of a ledger entry. */
public enum Direction {
    DEBIT,
    CREDIT;

    /** The other side; a reversal books every entry on it. */
    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
