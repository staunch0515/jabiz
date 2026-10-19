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
        return failure(status, errorType, null, false);
    }

    /**
     * The provider's words for a request too long for it (Q8b re-review M-b): the context window ("prompt is too
     * long: … tokens > … maximum") or the pages of PDF documents in one request ("A maximum of 600 PDF pages may be
     * provided", "too many pages"). The API documents the limits but not these texts: they are matched loosely, and
     * anything else stays {@link AiCodes#REQUEST_INVALID}.
     */
    private static final Pattern TOO_LONG = Pattern.compile(
        "(?i)prompt is too long|maximum of \\d+ (pdf )?pages|too many (pdf )?pages|(pdf )?pages? limit");
    /**
     * The provider's words for a PDF it cannot read: "The PDF specified was not valid", "… is password protected",
     * "could not process the PDF/document", encrypted, corrupt or damaged. Only a message about the PDF itself counts.
     */
    private static final Pattern UNREADABLE = Pattern.compile("(?i)\\bpdf\\b[^.]{0,80}?\\b(not valid|invalid|"
        + "password|encrypted|corrupt(ed)?|damaged|malformed|could not be (read|processed|parsed)|unreadable)"
        + "|(could not|unable to|failed to) (process|read|parse|load) (the )?(pdf|document)");
    /**
     * A validation error of a field of our own request ({@code messages.0.content.0.document.source.data: …}): our
     * mistake, never the sponsor's file, so it stays {@link AiCodes#REQUEST_INVALID} and visible to operations.
     */
    private static final Pattern FIELD_PATH = Pattern.compile("^\\s*[a-z_]+(\\.[a-z0-9_]+)+\\s*:");

    /**
     * As {@link #failure(int, String)}, for a request with PDF documents also: a 400 saying the request is too long for
     * the provider is {@link AiCodes#PDF_TOO_LONG}, one saying a PDF cannot be read {@link AiCodes#PDF_UNREADABLE}
     * (both permanent; a 400 is not billed). Any other 400, a field's validation error above all, stays
     * {@link AiCodes#REQUEST_INVALID}. The provider's message is only matched, never kept.
     */
    public static ExternalJobException failure(int status, String errorType, String providerMessage,
        boolean documents) {
        if (documents && status == 400 && providerMessage != null) {
            if (TOO_LONG.matcher(providerMessage).find()) {
                return ExternalJobException.permanent(AiCodes.PDF_TOO_LONG,
                    "Claude refused the request as too long for it (400)");
            }
            if (!FIELD_PATH.matcher(providerMessage).find() && UNREADABLE.matcher(providerMessage).find()) {
                return ExternalJobException.permanent(AiCodes.PDF_UNREADABLE,
                    "Claude could not read a PDF material (400)");
            }
        }
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
