package com.jabiz.finance.calc;

/**
 * The numbers of a US bank account as a payment file carries them (FIN-AP-003, FIN-AP-013; NACHA Operating Rules):
 * the nine-digit ABA routing number with its check digit, and the account number of up to 17 characters. Pure.
 */
public final class BankNumbers {

    private static final int[] WEIGHTS = {3, 7, 1, 3, 7, 1, 3, 7, 1};

    private BankNumbers() {}

    /** Nine digits whose weighted sum (3, 7, 1 repeated) is a multiple of ten. */
    public static boolean validRouting(String routing) {
        if (routing == null || !routing.matches("[0-9]{9}")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += WEIGHTS[i] * (routing.charAt(i) - '0');
        }
        return sum % 10 == 0;
    }

    /** Digits, 4 to 17 of them: the DFI account number field of an ACH entry is 17 characters. */
    public static boolean validAccount(String account) {
        return account != null && account.matches("[0-9]{4,17}");
    }

    /** An account number as entered, without the spaces and hyphens people group it with. */
    public static String compact(String value) {
        return value == null ? null : value.replace(" ", "").replace("-", "");
    }
}
