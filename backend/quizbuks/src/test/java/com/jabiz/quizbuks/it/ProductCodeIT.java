package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.page.ProfilePackage;
import com.jabiz.quizbuks.page.ProfileTransferProcesses;
import com.jabiz.quizbuks.page.SponsorPageCodes;
import com.jabiz.quizbuks.page.SponsorProductProcesses;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Products' own codes (docs/quizbuks/plans/Q100-profile-package.md, Q100e): optional and once per sponsor; a profile
 * package finds a product by its code before its id, so that a re-import keeps the quizzes it is shown on, and warns
 * of the quizzes a deleted product leaves; a quiz file names the quiz's products by code (or id) and an import links
 * the importer's own.
 */
class ProductCodeIT extends PlayItSupport {

    private String save(String token, String name, String code) {
        return (String) run(SponsorProductProcesses.SAVE, token, body("name", name, "price", "100", "currency", "JPY",
            "shopUrl", "https://shop.example/" + UUID.randomUUID(), "listed", true, "code", code)).get("productId");
    }

    private static Map<String, Object> packaged(String name, String code) {
        return body("code", code, "name", body("ja", name), "price", "100", "currency", "JPY",
            "shopUrl", "https://shop.example/" + UUID.randomUUID());
    }

    private static Map<String, Object> pkg(List<Map<String, Object>> products) {
        return body("format", ProfilePackage.FORMAT, "schema", ProfilePackage.SCHEMA, "languages", List.of("ja"),
            "products", products);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> plan(Map<String, Object> output) {
        return (Map<String, Object>) output.get("plan");
    }

    private static List<Map<String, Object>> links(String quizId) {
        return query("SELECT question_no, product_id FROM qb_quiz_product WHERE quiz_id = ? ORDER BY question_no, seq",
            quizId);
    }

    @Test
    void aCodeIsTheSponsorsOwnOnceAndOptional() {
        String token = sponsorToken(newSponsor(unique("s-q100e-codes")));
        save(token, "Green tea", "TEA-1");
        save(token, "No code", null);
        assertThat(codes(refused(SponsorProductProcesses.SAVE, token, body("name", "Again", "price", "1",
            "currency", "JPY", "shopUrl", "https://shop.example/x", "listed", true, "code", "TEA-1"), 422)))
            .containsExactly(SponsorPageCodes.PRODUCT_CODE_TAKEN);
        assertThat(codes(refused(SponsorProductProcesses.SAVE, token, body("name", "Bad", "price", "1",
            "currency", "JPY", "shopUrl", "https://shop.example/x", "listed", true, "code", "tea 1"), 422)))
            .containsExactly(SponsorPageCodes.PRODUCT_CODE_FORMAT);
        // Another sponsor may use the same code.
        save(sponsorToken(newSponsor(unique("s-q100e-other"))), "Their tea", "TEA-1");
    }

    @Test
    void aPackageFindsProductsByCodeKeepsTheirQuizzesAndWarnsOfThoseLeft() {
        String token = sponsorToken(newSponsor(unique("s-q100e-package")));
        String green = save(token, "Green tea", "TEA-1");
        String black = save(token, "Black tea", null);
        String quizId = newQuiz(token, "Tea quiz");
        question(token, quizId, "Which is green?");
        run(SponsorProductProcesses.QUIZ_PRODUCTS_SET, token, body("quizId", quizId, "quizProducts",
            List.of(green, black)));

        // A package written elsewhere: no ids, the green tea by its code; the black tea is not in it.
        Map<String, Object> document = pkg(List.of(packaged("新しい緑茶", "TEA-1"), packaged("ほうじ茶", "TEA-3")));
        Map<String, Object> plan = plan(run(ProfileTransferProcesses.CHECK, token, body("document", document)));
        assertThat(plan).containsEntry("changed", List.of("新しい緑茶")).containsEntry("added", List.of("ほうじ茶"))
            .containsEntry("deleted", List.of("Black tea"))
            .containsEntry("linked", List.of(Map.of("product", "Black tea", "quizzes", List.of("Tea quiz"))));
        run(ProfileTransferProcesses.IMPORT, token, body("document", document));

        // The green tea is the same product, still on the quiz; the black tea left it.
        assertThat(query("SELECT name FROM qb_sponsor_product WHERE product_id = ?", green)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("name", "新しい緑茶"));
        assertThat(links(quizId)).extracting(row -> String.valueOf(row.get("product_id"))).containsExactly(green);
    }

    @Test
    void aQuizFileNamesItsProductsByCodeOrIdAndAnImportLinksTheImportersOwn() {
        String token = sponsorToken(newSponsor(unique("s-q100e-quiz")));
        String green = save(token, "Green tea", "TEA-1");
        String black = save(token, "Black tea", null);
        Map<String, Object> document = body("format", "quizbuks.quiz", "formatVersion", 3,
            "quiz", body("kind", "QUIZ", "title", "From a file", "timeLimitSec", 300),
            "questions", List.of(body("stem", "One?", "points", 1, "options", List.of(
                body("text", "Yes", "correct", true), body("text", "No"))),
                body("stem", "Two?", "points", 1, "options", List.of(
                    body("text", "Yes", "correct", true), body("text", "No")))),
            "products", body("quiz", List.of(body("code", "TEA-1"), body("code", "NOPE")),
                "questions", List.of(body("no", 2, "product", body("productId", black)))));
        Map<String, Object> checked = run("QB_QUIZ_IMPORT_CHECK", token, body("document", document));
        assertThat(checked).containsEntry("products", 2).containsEntry("missingProducts", List.of("NOPE"));

        String quizId = (String) run("QB_QUIZ_IMPORT", token, body("document", document)).get("quizId");
        assertThat(links(quizId)).extracting(row -> row.get("question_no") + "=" + row.get("product_id"))
            .containsExactlyInAnyOrder("0=" + green, "2=" + black);

        // Another sponsor's import of the same file links nothing of the first sponsor's.
        String other = sponsorToken(newSponsor(unique("s-q100e-quiz-other")));
        String theirs = (String) run("QB_QUIZ_IMPORT", other, body("document", document)).get("quizId");
        assertThat(links(theirs)).isEmpty();
    }
}
