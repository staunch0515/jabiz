package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** AI does not write a survey's questions (docs/quizbuks/plans/Q12-survey.md, D-Q12-9): the request is refused. */
class AiSurveyIT extends AiItSupport {

    @Test
    void aSurveysQuestionsAreNotAskedFor() {
        String token = sponsorFull(SPONSOR_A);
        String title = "Survey " + java.util.UUID.randomUUID();
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", title, "kind", "SURVEY", "intro",
            "About tea")).get("quizId");
        String materialId = (String) run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "ARTICLE",
            "title", "Tea", "body", "Tea is a drink.")).get("itemId");
        Map<String, Object> input = body("quizId", quizId, "materialIds", List.of(materialId), "count", 1,
            "difficulty", "EASY", "language", "en");
        assertThat(codes(refused(AiProcesses.REQUEST, token, input, 422))).contains(AiCodes.SURVEY_UNSUPPORTED);
    }
}
