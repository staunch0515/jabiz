package com.jabiz.finance.gl;

/**
 * How the account types of finance (FIN-GL-001) map onto the ledger's five (docs/finance/00-design.md section 6.1):
 * other income or expense follows its normal balance, income tax is an expense. Reports classify by the finance type
 * and statement line, never by the ledger type.
 */
public final class AccountTypes {

    private AccountTypes() {}

    /**
     * @param financialType one of {@link GlEntities#FINANCIAL_TYPE_VALUES}
     * @param normalBalance {@code DEBIT} or {@code CREDIT}
     * @throws IllegalArgumentException for an unknown type
     */
    public static String ledgerType(String financialType, String normalBalance) {
        return switch (financialType) {
            case "ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE" -> financialType;
            case "TAX" -> "EXPENSE";
            case "OTHER" -> "CREDIT".equals(normalBalance) ? "REVENUE" : "EXPENSE";
            default -> throw new IllegalArgumentException("unknown account type " + financialType);
        };
    }

    /** The type as the sample company's chart writes it ({@code Asset}, {@code Other}, {@code Tax}). */
    public static String fromChart(String type) {
        return type == null ? null : type.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** {@code D} / {@code C} of the sample company's chart as {@code DEBIT} / {@code CREDIT}. */
    public static String normalBalanceFromChart(String side) {
        return switch (side == null ? "" : side.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "D", "DEBIT" -> "DEBIT";
            case "C", "CREDIT" -> "CREDIT";
            default -> throw new IllegalArgumentException("normal balance must be D or C, not " + side);
        };
    }
}
