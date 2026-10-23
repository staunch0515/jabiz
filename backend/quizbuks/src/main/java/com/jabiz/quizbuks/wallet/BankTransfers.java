package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.Violation;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * The rules of bank transfers (docs/quizbuks/plans/Q13-bank-transfer.md): the sizes of what the sponsor and finance
 * enter, how long a request waits for money, and the dates. Dates are days in Japan, where the bank is.
 */
public final class BankTransfers {

    /** The payer's name as the bank shows it (Zengin allows 48 characters). */
    public static final int PAYER_NAME_LENGTH = 48;
    /** The bank statement's reference of a deposit. */
    public static final int REFERENCE_LENGTH = 64;
    /** Bank top-ups waiting for money (asked for, reported, under review) a sponsor may have. */
    public static final int MAX_OPEN = 3;
    /** How long a bank top-up waits for money before it expires (finance may still record money against it). */
    public static final Duration EXPIRES_AFTER = Duration.ofDays(30);
    /** Requests the daily expiry handles per run. */
    public static final int EXPIRE_BATCH = 500;
    public static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    /**
     * A statement's reference as kept: width and case folded and spaces removed, so that one entry typed twice ("st 0001",
     * "ＳＴ-0001") is the same entry for the check that it is recorded once.
     */
    public static String reference(String typed) {
        String folded = java.text.Normalizer.normalize(typed == null ? "" : typed, java.text.Normalizer.Form.NFKC);
        return folded.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
    }

    /** Today in Japan at {@code now}. */
    public static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, ZONE);
    }

    /** A name of printable characters only, at most {@link #PAYER_NAME_LENGTH}, or a problem. */
    static Violation payerNameProblem(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty() || stripped.length() > PAYER_NAME_LENGTH
            || stripped.codePoints().anyMatch(Character::isISOControl)) {
            return new Violation("payerName", WalletCodes.TOPUP_BANK_PAYER_NAME, "The payer's name as the bank shows "
                + "it, at most " + PAYER_NAME_LENGTH + " characters", Map.of("max", PAYER_NAME_LENGTH));
        }
        return null;
    }

    /** A day not after today in Japan, or a problem on {@code field}. */
    static Violation futureProblem(String field, LocalDate day, Instant now) {
        return day.isAfter(today(now)) ? new Violation(field, WalletCodes.TOPUP_BANK_DATE_FUTURE,
            "The day is in the future", Map.of()) : null;
    }

    private BankTransfers() {}
}
