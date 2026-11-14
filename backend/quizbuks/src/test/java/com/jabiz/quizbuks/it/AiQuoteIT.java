package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.SiteProfileProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9b (coordinator 2026-11-14): the quotes of the four charged AI requests. A quote's "most it may cost" is what the
 * same request then reserves (one computation, {@code AiProcesses.feeQuote}), it records nothing (no job, no notice
 * accepted), a short credit is its answer rather than a refusal, and it reads only what its request may (another
 * Sponsor's quiz is not found).
 */
class AiQuoteIT extends AiCreditItSupport {

    @BeforeEach
    void on() {
        aiOn();
        feeOn();
        advance(Duration.ofDays(1));
    }

    /** The quote, then the request of the same input: its reservation is the quote's most. */
    private BigDecimal quoteThenRequest(String sponsor, String token, String quote, String request,
        Map<String, Object> input) {
        int jobs = query("SELECT 1 FROM qb_ai_job WHERE sponsor_id = ?", sponsor).size();
        Map<String, Object> answer = run(quote, token, input);
        assertThat(answer).as(quote).containsEntry("charged", true).containsEntry("enough", true);
        BigDecimal most = money(answer.get("maxYen"));
        assertThat(most).as(quote).isPositive();
        assertThat(money(answer.get("shortYen"))).isZero();
        assertThat(query("SELECT 1 FROM qb_ai_job WHERE sponsor_id = ?", sponsor)).as("a quote records no job")
            .hasSize(jobs);
        Object aiJobId = run(request, token, input).get("aiJobId");
        assertThat(money(job(aiJobId).get("reserved_yen"))).as(request).isEqualByComparingTo(most);
        return most;
    }

    @Test
    void aQuestionsQuoteIsWhatTheRequestReserves() {
        String sponsor = funded(newSponsor("ai-quote-q-" + UUID.randomUUID()));
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for a quote.");
        quoteThenRequest(sponsor, token, AiProcesses.QUOTE, AiProcesses.REQUEST,
            requestInput(quiz.get(0), List.of(quiz.get(1)), 3));
    }

    @Test
    void aTranslationQuoteIsWhatTheRequestReserves() {
        String sponsor = funded(newSponsor("ai-quote-t-" + UUID.randomUUID()));
        String token = sponsorFull(sponsor);
        List<String> quiz = bilingualVersion(token);
        quoteThenRequest(sponsor, token, AiProcesses.TRANSLATE_QUOTE, AiProcesses.TRANSLATE_REQUEST,
            body("quizId", quiz.get(0), "language", "zh", "all", true, "aiNoticeVersionNo", noticeVersion()));
    }

    @Test
    void aPublicationTranslationQuoteIsWhatTheRequestReserves() {
        String sponsor = funded(newSponsor("ai-quote-p-" + UUID.randomUUID()));
        String token = sponsorFull(sponsor);
        List<String> quiz = bilingualVersion(token);
        Instant now = clock.instant();
        Map<String, Object> save = complete(quiz.get(1), "World capitals", now, now.plus(Duration.ofDays(7)));
        save.put("languages", List.of("en", "zh"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        quoteThenRequest(sponsor, token, AiProcesses.PUBLICATION_TRANSLATE_QUOTE,
            AiProcesses.PUBLICATION_TRANSLATE_REQUEST, body("quizId", quiz.get(0), "publicationId", publicationId,
                "language", "zh", "aiNoticeVersionNo", noticeVersion()));
    }

    @Test
    void aWebsiteDraftsQuoteIsWhatTheRequestReserves() {
        param(QbParams.SITE_PROFILE_ENABLED, "true");
        String sponsor = funded(newSponsor("ai-quote-s-" + UUID.randomUUID()));
        quoteThenRequest(sponsor, sponsorFull(sponsor), SiteProfileProcesses.QUOTE, SiteProfileProcesses.REQUEST,
            body("siteAddress", "https://www.aini.example/", "language", "ja", "otherLanguages", List.of("en"),
                "rightsDeclared", true, "aiNoticeVersionNo", noticeVersion()));
    }

    /**
     * Before the notice is accepted a quote answers (and records no acceptance); a short credit is the answer, with
     * what is missing; with the fee off the request is free; another Sponsor's quiz is not found.
     */
    @Test
    void aQuoteAnswersBeforeTheNoticeSaysWhatIsShortAndReadsOnlyTheCallersOwn() {
        String sponsor = newSponsor("ai-quote-short-" + UUID.randomUUID());
        cash(sponsor, 1_000);
        buy(sponsor, 1);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for a short quote.");
        Map<String, Object> input = new HashMap<>(requestInput(quiz.get(0), List.of(quiz.get(1)), 3));
        input.remove("aiNoticeVersionNo");
        int consents = query("SELECT 1 FROM qb_consent_version WHERE user_id = ?", sponsor).size();

        Map<String, Object> answer = run(AiProcesses.QUOTE, token, input);
        assertThat(answer).containsEntry("charged", true).containsEntry("enough", false);
        BigDecimal most = money(answer.get("maxYen"));
        BigDecimal available = money(answer.get("availableYen"));
        assertThat(available).isEqualByComparingTo("1");
        assertThat(money(answer.get("shortYen"))).isEqualByComparingTo(most.subtract(available));
        assertThat(query("SELECT 1 FROM qb_consent_version WHERE user_id = ?", sponsor)).as("no notice recorded")
            .hasSize(consents);
        // The request itself is refused for the same amount.
        assertThat(String.valueOf(refused(AiProcesses.REQUEST, token, requestInput(quiz.get(0), List.of(quiz.get(1)),
            3), 422))).contains(AiCodes.CREDIT_SHORT).contains("may cost up to " + most.toPlainString());

        // Another Sponsor cannot price this quiz.
        String other = sponsorFull(newSponsor("ai-quote-other-" + UUID.randomUUID()));
        refused(AiProcesses.QUOTE, other, input, 404);

        // The fee off: free.
        param(QbParams.AI_FEE_ENABLED, "false");
        try {
            assertThat(run(AiProcesses.QUOTE, token, input)).containsEntry("charged", false)
                .containsEntry("enough", true);
        } finally {
            param(QbParams.AI_FEE_ENABLED, "true");
        }
    }

    /** A quiz in English with a complete Chinese translation, and its version: {quizId, versionId}. */
    @SuppressWarnings("unchecked")
    private List<String> bilingualVersion(String token) {
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", "Capitals", "language", "en",
            "intro", "Two capitals.", "timeLimitSec", 300)).get("quizId");
        Map<String, Object> q1 = run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?",
            "points", 5, "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        List<String> options = (List<String>) q1.get("partIds");
        run("QB_QUIZ_LANGUAGES_SET", token, body("quizId", quizId, "languages", List.of("zh")));
        run("QB_TRANSLATION_SAVE", token, body("quizId", quizId, "language", "zh", "entries", List.of(
            body("key", ContentLanguages.TITLE, "text", "首都"),
            body("key", ContentLanguages.INTRO, "text", "两个首都。"),
            body("key", ContentLanguages.stem(q1.get("itemId")), "text", "法国的首都？"),
            body("key", ContentLanguages.optionText(options.get(0)), "text", "巴黎"),
            body("key", ContentLanguages.optionText(options.get(1)), "text", "里昂"))));
        return List.of(quizId, (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId))
            .get("versionId"));
    }
}
