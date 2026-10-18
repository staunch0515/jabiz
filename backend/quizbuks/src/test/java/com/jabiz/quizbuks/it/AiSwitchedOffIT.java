package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.messaging.QbLegal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI as a new installation has it (docs/quizbuks/plans/Q8-ai.md, A-9, A-11): switched off ({@code qb.ai.enabled} is
 * false until an administrator turns it on) and without an AI notice in effect. Nothing is sent to the provider, and
 * the sponsor is told why; an unapproved sponsor is refused as well.
 */
class AiSwitchedOffIT extends AiItSupport {

    @Test
    void nothingIsSentWhileAiIsOffOrNoNoticeIsInEffect() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithArticle(token, "Off " + java.util.UUID.randomUUID(), "Material.");
        Map<String, Object> input = body("quizId", quiz.get(0), "materialIds", List.of(quiz.get(1)), "count", 1,
            "difficulty", "EASY", "language", "ja");
        assertThat(codes(refused(AiProcesses.REQUEST, token, input, 422)))
            .contains(AiCodes.UNAVAILABLE, AiCodes.NOTICE_MISSING);
        publishNew(QbLegal.AI_NOTICE, "first", null, true);
        input.put("aiNoticeVersionNo", noticeVersion());
        assertThat(codes(refused(AiProcesses.REQUEST, token, input, 422))).containsExactly(AiCodes.UNAVAILABLE);
        // A sponsor whose onboarding is not approved is refused too, whatever the switch.
        String stranger = sponsorFull("ai-unapproved-sponsor");
        List<String> theirs = quizWithArticle(stranger, "Theirs", "Material.");
        assertThat(codes(refused(AiProcesses.REQUEST, stranger, body("quizId", theirs.get(0), "materialIds",
            List.of(theirs.get(1)), "count", 1, "difficulty", "EASY", "language", "en", "aiNoticeVersionNo",
            noticeVersion()), 422))).contains("QB_SPONSOR_NOT_APPROVED", AiCodes.UNAVAILABLE);
        assertThat(sent()).isEmpty();
        assertThat(query("SELECT 1 FROM qb_ai_job")).isEmpty();
        // No consent is recorded by a refused request.
        assertThat(consentRows(SPONSOR_A)).noneSatisfy(row -> assertThat(row.get("kind"))
            .isEqualTo(QbLegal.AI_NOTICE));
    }
}
