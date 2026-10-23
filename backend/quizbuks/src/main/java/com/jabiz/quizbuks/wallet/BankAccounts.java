package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.Violation;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The rules of the takers' bank accounts and their withdrawals (docs/quizbuks/plans/Q13-bank-transfer.md, Q13b): a
 * Japanese account as the Zengin format has it (bank 4 digits, branch 3, number 7, the holder in katakana), and when a
 * withdrawal may be asked for. Pure: the processes read the facts under the taker's wallet lock.
 */
public final class BankAccounts {

    public static final int NAME_LENGTH = 60;
    /** The holder's name as the bank keeps it. */
    public static final int HOLDER_LENGTH = 48;

    /** After NFKC (half-width kana become full-width, full-width signs ASCII): katakana, the long mark, Zengin's signs. */
    private static final Pattern HOLDER = Pattern.compile("^[\\u30A1-\\u30F6\\u30FC ().,/\\-]+$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");

    /**
     * An account as typed, normalised: codes and number in ASCII digits (the number padded to 7), names stripped, the
     * holder's width folded and spaces collapsed.
     */
    public record Account(String bankCode, String bankName, String branchCode, String branchName, String accountType,
        String accountNumber, String accountHolder) {

        @Override
        public String toString() {
            return "Account[" + bankCode + "-" + branchCode + ", " + accountType + ", ***]";
        }
    }

    public static Account normalise(String bankCode, String bankName, String branchCode, String branchName,
        String accountType, String accountNumber, String accountHolder) {
        String number = fold(accountNumber);
        if (DIGITS.matcher(number).matches() && number.length() < 7) {
            number = "0".repeat(7 - number.length()) + number;
        }
        return new Account(fold(bankCode), strip(bankName), fold(branchCode), strip(branchName),
            accountType == null ? "" : accountType.strip(), number,
            fold(accountHolder == null ? "" : accountHolder).replaceAll(" +", " "));
    }

    /** What is wrong with the account, field by field (code {@link WalletCodes#BANK_ACCOUNT_INVALID}). */
    public static List<Violation> problems(Account account) {
        List<Violation> problems = new ArrayList<>();
        digits(problems, "bankCode", account.bankCode(), 4);
        digits(problems, "branchCode", account.branchCode(), 3);
        digits(problems, "bankAccountNumber", account.accountNumber(), 7);
        name(problems, "bankName", account.bankName());
        name(problems, "branchName", account.branchName());
        if (!QbPayouts.ACCOUNT_TYPES.contains(account.accountType())) {
            problems.add(invalid("accountType", "One of " + QbPayouts.ACCOUNT_TYPES, Map.of()));
        }
        String holder = account.accountHolder();
        if (holder.isEmpty() || holder.length() > HOLDER_LENGTH || !HOLDER.matcher(holder).matches()) {
            problems.add(invalid("accountHolder", "The holder's name in katakana as the bank shows it, at most "
                + HOLDER_LENGTH + " characters", Map.of("max", HOLDER_LENGTH)));
        }
        return problems;
    }

    /** When a withdrawal to an account changed at {@code changedAt} may be asked for. */
    public static Instant usableFrom(Instant changedAt, BigDecimal cooldownHours) {
        long hours = cooldownHours == null || cooldownHours.signum() < 0 ? 0 : cooldownHours.longValue();
        return changedAt.plus(Duration.ofHours(hours));
    }

    /** Reasons a withdrawal cannot be asked for (the {@code reason} of {@link WalletCodes#WITHDRAWAL_UNAVAILABLE}). */
    public static final String OFF = "OFF";
    public static final String BANNED = "BANNED";
    public static final String HELD = "HELD";
    public static final String OPEN_TRANSFER = "OPEN_TRANSFER";
    public static final String NOT_BANK = "NOT_BANK";
    public static final String COOLDOWN = "COOLDOWN";
    public static final String NO_REVIEW = "NO_REVIEW";
    public static final String INVALID_PARAMS = "INVALID_PARAMS";

    /**
     * The facts of a request for a withdrawal, read under the taker's wallet lock.
     *
     * @param transferable what may leave now: the balance less what is held back ({@link TransferRules#transferable})
     */
    public record Facts(Boolean enabled, BigDecimal threshold, BigDecimal fee, BigDecimal cooldownHours,
        boolean banned, boolean held, boolean openTransfer, String payoutMethod, Instant changedAt,
        boolean reviewRule, BigDecimal transferable, Instant now) {}

    /** Why a withdrawal cannot be asked for now, or null: then {@link #amountProblem} decides the amount. */
    public static String unavailable(Facts facts) {
        if (!Boolean.TRUE.equals(facts.enabled())) {
            return OFF;
        }
        if (!wholeNotNegative(facts.threshold()) || !wholeNotNegative(facts.fee())
            || !wholeNotNegative(facts.cooldownHours())) {
            return INVALID_PARAMS;
        }
        if (facts.banned()) {
            return BANNED;
        }
        if (!QbPayouts.BANK.equals(facts.payoutMethod())) {
            return NOT_BANK;
        }
        if (facts.openTransfer()) {
            return OPEN_TRANSFER;
        }
        if (facts.held()) {
            return HELD;
        }
        if (facts.now().isBefore(usableFrom(facts.changedAt(), facts.cooldownHours()))) {
            return COOLDOWN;
        }
        return facts.reviewRule() ? null : NO_REVIEW;
    }

    /**
     * The amount's problem, or null: whole Kudos, at least the transfer threshold, above the fee, at most what may
     * leave now (params {@code min}, {@code max}).
     */
    public static Violation amountProblem(Facts facts, BigDecimal amount) {
        BigDecimal min = facts.threshold().max(facts.fee().add(BigDecimal.ONE));
        BigDecimal max = facts.transferable().max(BigDecimal.ZERO);
        if (amount == null || amount.stripTrailingZeros().scale() > 0 || amount.compareTo(min) < 0
            || amount.compareTo(max) > 0) {
            return new Violation("amount", WalletCodes.WITHDRAWAL_AMOUNT, "Whole Kudos from " + min.toPlainString()
                + " to " + max.toPlainString(), Map.of("min", min.toPlainString(), "max", max.toPlainString()));
        }
        return null;
    }

    private static boolean wholeNotNegative(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 0;
    }

    private static void digits(List<Violation> problems, String field, String value, int length) {
        if (value.length() != length || !DIGITS.matcher(value).matches()) {
            problems.add(invalid(field, length + " digits", Map.of("digits", length)));
        }
    }

    private static void name(List<Violation> problems, String field, String value) {
        if (value.isEmpty() || value.length() > NAME_LENGTH || value.codePoints().anyMatch(Character::isISOControl)) {
            problems.add(invalid(field, "At most " + NAME_LENGTH + " characters", Map.of("max", NAME_LENGTH)));
        }
    }

    private static Violation invalid(String field, String message, Map<String, Object> params) {
        return new Violation(field, WalletCodes.BANK_ACCOUNT_INVALID, message, params);
    }

    private static String fold(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).strip();
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private BankAccounts() {}
}
