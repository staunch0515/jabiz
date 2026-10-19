package com.jabiz.quizbuks.content.ai;

/**
 * PDF materials sent to Claude (docs/quizbuks/plans/Q8-ai.md, Q8b, A-5): the file itself, as a document block, at most
 * {@code quizbuks.ai.pdf.max-bytes} each ({@value #DEFAULT_MAX_BYTES} bytes unless configured, at most
 * {@link AiQuestionsJob#MAX_PDF_BYTES}; checked by the request, and by the platform against the job type) and
 * {@code quizbuks.ai.pdf.max-pages} pages all together ({@value #DEFAULT_MAX_PAGES} unless configured, at most
 * {@value #MAX_PAGES}, the provider's own limit, and only as many as fit the models' context window:
 * {@link AiPdfLimitsCheck}).
 *
 * <p><b>No PDF is parsed on the server</b> (Q8b review H1, H2): a sponsor's file can be built to expand without bound
 * in a parser's memory, or to claim fewer pages than it has. The pages are not known when the request is made, so the
 * request reserves the most a request may send (the page cap at {@value ClaudeApi#TOKENS_PER_PDF_PAGE} tokens a page,
 * {@link AiLimits#estimate}); before the paid call the handler asks the provider's free token count for exactly the
 * request it is about to send ({@link ClaudeCalls#counted}). More than the reservation ends the job, nothing sent or
 * charged ({@link AiCodes#PDF_TOO_LONG}); a file the provider cannot read ends it too ({@link AiCodes#PDF_UNREADABLE}).
 * Only the byte caps are checked locally (the stored file's size).
 */
public final class AiPdfs {

    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
    public static final int DEFAULT_MAX_PAGES = 100;
    /** The provider's most pages in one request (with a context window of a million tokens). */
    public static final int MAX_PAGES = 600;

    /** The configured limits: the largest PDF a request sends, and the most pages of all of them. */
    public record Limits(long maxBytes, int maxPages) {
        public Limits {
            AiPdfs.maxBytes(maxBytes);
            AiPdfs.maxPages(maxPages);
        }
    }

    /** {@code quizbuks.ai.pdf.max-bytes}, checked. */
    public static long maxBytes(long configured) {
        if (configured < 1 || configured > AiQuestionsJob.MAX_PDF_BYTES) {
            throw new IllegalArgumentException("quizbuks.ai.pdf.max-bytes: from 1 to " + AiQuestionsJob.MAX_PDF_BYTES);
        }
        return configured;
    }

    /** {@code quizbuks.ai.pdf.max-pages}, checked. */
    public static int maxPages(int configured) {
        if (configured < 1 || configured > MAX_PAGES) {
            throw new IllegalArgumentException("quizbuks.ai.pdf.max-pages: from 1 to " + MAX_PAGES);
        }
        return configured;
    }

    private AiPdfs() {}
}
