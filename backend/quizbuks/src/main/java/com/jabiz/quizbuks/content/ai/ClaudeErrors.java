package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobException;

import java.util.regex.Pattern;

/**
 * What an unsuccessful answer of Anthropic's API means for a job (docs/quizbuks/plans/Q8-ai.md, D-Q8-1):
 * <ul>
 *   <li>tried again (the job's three attempts, with back-off): 429 ({@code rate_limit_error}), 408, 5xx and 529
 *       ({@code overloaded_error});</li>
 *   <li>permanent: 401 and 403 (the key), every other 4xx (the request is refused as it is: 400, 404, 413).</li>
 * </ul>
 * Time-outs and refused connections never reach here: they fail the attempt, which the platform tries again. The
 * provider does not deduplicate by an idempotency key, so a retry may be billed again: the usage is recorded per
 * attempt, and the limits count it. The message names the status and the error's type only, never the provider's
 * message or a request.
 */
public final class ClaudeErrors {

    private static final Pattern TYPE = Pattern.compile("[a-z_]{1,60}");

    public record Classification(boolean retryable, String code) {}

    /**
     * @param status    the HTTP status (not 2xx)
     * @param errorType {@code error.type} of the answer, or null
     */
    public static Classification classify(int status, String errorType) {
        if (status == 429) {
            return new Classification(true, AiCodes.RATE_LIMITED);
        }
        if (status == 408 || status >= 500) {
            return new Classification(true, AiCodes.PROVIDER_UNAVAILABLE);
        }
        if (status == 401 || status == 403) {
            return new Classification(false, AiCodes.AUTH);
        }
        return new Classification(false, AiCodes.REQUEST_INVALID);
    }

    public static ExternalJobException failure(int status, String errorType) {
        Classification c = classify(status, errorType);
        String type = errorType != null && TYPE.matcher(errorType).matches() ? errorType : null;
        String message = "Claude answered " + status + (type == null ? "" : " (" + type + ")");
        return c.retryable() ? ExternalJobException.retryable(c.code(), message)
            : ExternalJobException.permanent(c.code(), message);
    }

    /** A successful status whose answer cannot be used; tried again, since a later answer may be whole. */
    public static ExternalJobException badResponse(String what) {
        return ExternalJobException.retryable(AiCodes.BAD_RESPONSE, "Claude's answer " + what);
    }

    private ClaudeErrors() {}
}
