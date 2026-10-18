package com.jabiz.quizbuks.content.ai;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The PDF page cap must be one the provider can take (Q8b re-review M-b, category {@code QUIZBUKS}, at startup and in
 * {@code platformCheck}): a request reserves {@code quizbuks.ai.pdf.max-pages} pages at
 * {@value ClaudeApi#TOKENS_PER_PDF_PAGE} tokens each, and that with the largest text a request may send and the most
 * output it asks for must fit the models' context window ({@link ClaudeService#CONTEXT_WINDOW_TOKENS}); the cap may
 * not be above the provider's pages a request ({@value AiPdfs#MAX_PAGES}). Otherwise a request within the cap could
 * still be too long for the provider, and the reservation would not bound what a job may cost.
 */
@Component
class AiPdfLimitsCheck implements PlatformCheck {

    static final String SETTING = "quizbuks.ai.pdf.max-pages";

    private final AiPdfs.Limits limits;

    AiPdfLimitsCheck(AiPdfs.Limits limits) {
        this.limits = limits;
    }

    @Override
    public List<CheckProblem> check() {
        return problems(limits.maxPages());
    }

    /** The most input one request may come to with {@code maxPages} pages, and its largest output. */
    static long largestRequest(int maxPages) {
        long text = ClaudeApi.MAX_INPUT_BYTES + AiPrompt.SYSTEM.getBytes(StandardCharsets.UTF_8).length;
        return AiLimits.reservedInput(text, maxPages) + ClaudeApi.MAX_TOKENS;
    }

    /** Why {@code maxPages} cannot be the cap; empty when it can. */
    static List<CheckProblem> problems(int maxPages) {
        List<CheckProblem> problems = new ArrayList<>();
        if (maxPages > AiPdfs.MAX_PAGES) {
            problems.add(CheckProblem.error(CountryCatalog.CATEGORY, SETTING, SETTING + " " + maxPages
                + " is above the provider's " + AiPdfs.MAX_PAGES + " PDF pages a request"));
        }
        long largest = largestRequest(maxPages);
        if (largest > ClaudeService.CONTEXT_WINDOW_TOKENS) {
            problems.add(CheckProblem.error(CountryCatalog.CATEGORY, SETTING, SETTING + " " + maxPages + " pages at "
                + ClaudeApi.TOKENS_PER_PDF_PAGE + " tokens, with the largest text and output, come to " + largest
                + " tokens, more than the models' context window of " + ClaudeService.CONTEXT_WINDOW_TOKENS
                + "; lower it"));
        }
        return List.copyOf(problems);
    }
}
