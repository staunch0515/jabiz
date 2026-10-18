package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.List;

/**
 * PDF materials sent to Claude (docs/quizbuks/plans/Q8-ai.md, Q8b, A-5): the file itself, as a document block, at most
 * {@code quizbuks.ai.pdf.max-bytes} each ({@value #DEFAULT_MAX_BYTES} bytes unless configured, at most
 * {@link AiQuestionsJob#MAX_PDF_BYTES}; checked by the request, and by the platform against the job type) and
 * {@code quizbuks.ai.pdf.max-pages} pages all together ({@value #DEFAULT_MAX_PAGES} unless configured, at most
 * {@value #MAX_PAGES}, the provider's own limit).
 *
 * <p>The pages are not known when the request is made (a process does not read files): the request reserves the most
 * pages a request may have ({@link AiLimits#estimate}), and the handler counts them before anything is sent. Too many,
 * a password or a file that is no readable PDF end the job at once, nothing sent and nothing charged.
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

    /** The pages of a PDF; the job's permanent failure when it cannot be read or has a password. */
    static int pages(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (document.isEncrypted()) {
                throw ExternalJobException.permanent(AiCodes.PDF_UNREADABLE, "A PDF material is encrypted");
            }
            int pages = document.getNumberOfPages();
            if (pages < 1) {
                throw ExternalJobException.permanent(AiCodes.PDF_UNREADABLE, "A PDF material has no pages");
            }
            return pages;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ExternalJobException failure) {
                throw failure;
            }
            throw ExternalJobException.permanent(AiCodes.PDF_UNREADABLE, "A PDF material cannot be read");
        }
    }

    /** Refuses, before sending, documents of more pages than a request may have. */
    static void requireWithin(List<ClaudeApi.Document> documents, int maxPages) {
        int pages = documents.stream().mapToInt(ClaudeApi.Document::pages).sum();
        if (pages > maxPages) {
            throw ExternalJobException.permanent(AiCodes.PDF_TOO_LONG, "The PDF materials have " + pages
                + " pages, more than " + maxPages);
        }
    }

    private AiPdfs() {}
}
