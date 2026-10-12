package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalJobException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * What an unsuccessful answer of Stripe means for a job (docs/quizbuks/plans/Q6-payments.md, D-Q6-1): whether trying
 * again with the same idempotency key may succeed, and the code the job's failure carries.
 * <ul>
 *   <li>retried: 5xx, 429 and the rate limit ({@code rate_limit}), a request still in progress under the same key
 *       (409) or a lock Stripe could not take ({@code lock_timeout}), and an insufficient balance
 *       ({@code balance_insufficient}: transfers wait for the balance to become available, Q6b);</li>
 *   <li>permanent: every other 4xx (the request is refused as it is: {@code STRIPE_DECLINED}, with Stripe's own error
 *       code in the message), the key refused (401, 403) or the object not found (404).</li>
 * </ul>
 * Timeouts and refused connections never reach here: they fail the attempt, which the platform retries. The message
 * names the status and Stripe's error code only, never a request or an answer.
 */
public final class StripeErrors {

    /** Stripe's error codes as they may be shown: lower case, digits and underscores. */
    private static final Pattern CODE = Pattern.compile("[a-z0-9_]{1,60}");
    private static final Set<String> RETRIED_CODES = Set.of("rate_limit", "lock_timeout", "balance_insufficient");

    public static final String RATE_LIMITED = "STRIPE_RATE_LIMITED";
    public static final String UNAVAILABLE = "STRIPE_UNAVAILABLE";
    public static final String CONFLICT = "STRIPE_CONFLICT";
    public static final String BALANCE_INSUFFICIENT = "STRIPE_BALANCE_INSUFFICIENT";
    public static final String AUTH = "STRIPE_AUTH";
    public static final String NOT_FOUND = "STRIPE_NOT_FOUND";
    public static final String DECLINED = "STRIPE_DECLINED";
    /** A successful status whose answer is not what the call expects. */
    public static final String BAD_RESPONSE = "STRIPE_BAD_RESPONSE";

    /**
     * @param retryable whether another attempt may succeed
     * @param code      the job's error code
     */
    public record Classification(boolean retryable, String code) {}

    /**
     * @param status    the HTTP status (not 2xx)
     * @param errorCode {@code error.code} of Stripe's answer, or null
     */
    public static Classification classify(int status, String errorCode) {
        String code = shown(errorCode);
        if ("balance_insufficient".equals(code)) {
            return new Classification(true, BALANCE_INSUFFICIENT);
        }
        if (status == 429 || "rate_limit".equals(code)) {
            return new Classification(true, RATE_LIMITED);
        }
        if (status >= 500) {
            return new Classification(true, UNAVAILABLE);
        }
        if (status == 409 || (code != null && RETRIED_CODES.contains(code))) {
            return new Classification(true, CONFLICT);
        }
        if (status == 401 || status == 403) {
            return new Classification(false, AUTH);
        }
        if (status == 404) {
            return new Classification(false, NOT_FOUND);
        }
        return new Classification(false, DECLINED);
    }

    /** The exception that fails the job's attempt for an answer of {@code status}. */
    public static ExternalJobException failure(int status, String errorCode) {
        Classification c = classify(status, errorCode);
        String code = shown(errorCode);
        String message = "Stripe answered " + status + (code == null ? "" : " (" + code + ")");
        return c.retryable() ? ExternalJobException.retryable(c.code(), message)
            : ExternalJobException.permanent(c.code(), message);
    }

    /** A successful answer the call cannot use; tried again, since a later answer may be complete. */
    public static ExternalJobException badResponse(String what) {
        return ExternalJobException.retryable(BAD_RESPONSE, "Stripe's answer " + what);
    }

    /** Stripe's error code when it looks like one; null otherwise (it is shown in failures). */
    static String shown(String errorCode) {
        return errorCode != null && CODE.matcher(errorCode).matches() ? errorCode : null;
    }

    private StripeErrors() {}
}
