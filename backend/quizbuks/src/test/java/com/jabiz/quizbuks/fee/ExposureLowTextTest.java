package com.jabiz.quizbuks.fee;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M8: "exposures running low" is one condition in two templates (the home's to-do count and the publications list the
 * item leads to): the conditions below are the same text, so that a change to one that forgets the other fails here.
 */
class ExposureLowTextTest {

    private static String read(String path) throws IOException {
        try (var in = ExposureLowTextTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    /** From the exposures counted to the end of the condition on the purchased number. */
    private static String condition(String text) {
        int from = text.indexOf("CROSS JOIN LATERAL (SELECT count(*) AS n FROM {{QbExposure}} e");
        int to = text.indexOf("* q.ratio)", from);
        assertThat(from).isNotNegative();
        assertThat(to).isGreaterThan(from);
        String part = text.substring(from, to + "* q.ratio)".length());
        // The to-do's rows are the cards themselves; the list's ties its card to the publication: leave that one line out.
        return part.replaceAll("WHERE (CAST\\(c\\.\\{\\{QbPublicationCard\\.publicationId\\}\\} AS text\\) = CAST\\(p\\.\\{\\{QbPublication\\.publicationId\\}\\} AS text\\) AND )?", "WHERE ");
    }

    @Test
    void theTodoCountAndTheListsFilterAreTheSameCondition() throws IOException {
        assertThat(condition(read("/queries/qb/sponsor/publications.sql")))
            .isEqualTo(condition(read("/queries/qb/sponsor/todo.sql")));
    }
}
