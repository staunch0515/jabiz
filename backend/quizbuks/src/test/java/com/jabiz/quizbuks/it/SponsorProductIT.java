package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.page.QbSponsorPages;
import com.jabiz.quizbuks.page.QbSponsorProducts;
import com.jabiz.quizbuks.page.SponsorPageCodes;
import com.jabiz.quizbuks.page.SponsorPageProcesses;
import com.jabiz.quizbuks.page.SponsorProductProcesses;
import com.jabiz.quizbuks.page.SponsorStatsConfig;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.publicread.PublicCounters;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sponsors' products (docs/quizbuks/plans/Q60-sponsor-page.md, Q60b): shown on a published page and with the
 * sponsor's quizzes, without a review of their own; headquarters or the sponsor's operator takes one down and puts it
 * back; the prices keep their currency's decimals.
 */
class SponsorProductIT extends PlayItSupport {

    private static final String PAGE_PRODUCTS = "qb.public.sponsor-page-products";
    private static final String PUBLICATION_PRODUCTS = "qb.public.publication-products";

    @Autowired
    PublicCounters counters;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> publicItems(String template, String param, String value) {
        Map<String, Object> page = client.get().uri(b -> b.path("/api/public/queries/" + template)
                .queryParam("p." + param, "{value}").build(value))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    private Map<String, Object> product(String name, String price, String currency, boolean listed) {
        return body("name", name, "description", "Good " + name, "price", price, "currency", currency,
            "shopUrl", "https://shop.example/" + name.toLowerCase().replace(' ', '-'), "listed", listed);
    }

    private String save(String token, Map<String, Object> product) {
        return (String) run(SponsorProductProcesses.SAVE, token, product).get("productId");
    }

    /** Publishes the sponsor's page through a review; returns its handle. */
    private String publishedPage(String sponsor) {
        String token = sponsorToken(sponsor);
        String handle = "q60b-" + UUID.randomUUID().toString().substring(0, 8);
        run(SponsorPageProcesses.SAVE, token, body("handle", handle, "language", "en", "headline", "Our shop"));
        String requestId = (String) run(SponsorPageProcesses.SUBMIT, token, body()).get("approvalRequestId");
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", requestId, "decision", "APPROVE"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_sponsor_page WHERE user_id = ?", sponsor).getFirst())
            .containsEntry("status", QbSponsorPages.PUBLISHED);
        return handle;
    }

    private static String quizOf(String publicationId) {
        return String.valueOf(query("SELECT quiz_id FROM qb_publication_version WHERE publication_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", publicationId).getFirst().get("quiz_id"));
    }

    private static String productStatus(String productId) {
        return (String) query("SELECT status FROM qb_sponsor_product WHERE product_id = ?", productId).getFirst()
            .get("status");
    }

    private String contentAdmin2() {
        return TestTokens.bearer(tokens, "q60b-content-admin", QbPermissions.ADMIN_CONTENT_WRITE);
    }

    @Test
    void productsShowOnAPublishedPageOnlyWithTheirCurrencysDecimals() {
        String sponsor = newSponsor(unique("s-q60b-ann"));
        String token = sponsorToken(sponsor);
        String bread = save(token, product("Bread", "1200", "JPY", true));
        String cake = save(token, product("Cake", "9.99", "USD", true));
        save(token, product("Secret", "1", "EUR", false));
        // The yen has no decimals; other currencies two.
        assertThat(codes(refused(SponsorProductProcesses.SAVE, token, product("Odd", "1200.5", "JPY", true), 422)))
            .containsExactly(SponsorPageCodes.PRODUCT_PRICE);
        assertThat(codes(refused(SponsorProductProcesses.SAVE, token, product("Odd", "9.999", "USD", true), 422)))
            .containsExactly(SponsorPageCodes.PRODUCT_PRICE);
        assertThat(codes(refused(SponsorProductProcesses.SAVE, token, product("Odd", "-1", "USD", true), 422)))
            .containsExactly(SponsorPageCodes.PRODUCT_PRICE);

        String handle = "q60b-none";
        assertThat(publicItems(PAGE_PRODUCTS, "handle", handle)).isEmpty();
        handle = publishedPage(sponsor);
        // Listed ones only, in the sponsor's order, with the price as the shop's.
        assertThat(publicItems(PAGE_PRODUCTS, "handle", handle)).extracting(row -> row.get("productId"))
            .containsExactly(bread, cake);
        assertThat(publicItems(PAGE_PRODUCTS, "handle", handle).getFirst()).containsEntry("currency", "JPY")
            .containsEntry("shopUrl", "https://shop.example/bread")
            .containsOnlyKeys("productId", "name", "description", "image", "price", "currency", "shopUrl", "seq");

        // A change shows at once (no review); hidden, it is gone.
        Map<String, Object> hidden = product("Cake", "8.50", "USD", false);
        hidden.put("productId", cake);
        run(SponsorProductProcesses.SAVE, token, hidden);
        assertThat(publicItems(PAGE_PRODUCTS, "handle", handle)).extracting(row -> row.get("productId"))
            .containsExactly(bread);
        // A listed product's image is public; hidden, it stops being so at once.
        String image = (String) upload(QbSponsorPages.IMAGE_POLICY, com.jabiz.runtime.test.FileSamples.jpeg(600, 400),
            "bread.jpg", "image/jpeg", token).expectStatus().isCreated().expectBody(MAP).returnResult()
            .getResponseBody().get("fileId");
        Map<String, Object> pictured = product("Bread", "1200", "JPY", true);
        pictured.put("productId", bread);
        pictured.put("image", image);
        run(SponsorProductProcesses.SAVE, token, pictured);
        client.get().uri("/api/public/files/" + image).exchange().expectStatus().isOk();
        pictured.put("listed", false);
        run(SponsorProductProcesses.SAVE, token, pictured);
        client.get().uri("/api/public/files/" + image).exchange().expectStatus().isNotFound();
        pictured.put("listed", true);
        run(SponsorProductProcesses.SAVE, token, pictured);
        // Another's product is not found to change.
        Map<String, Object> foreign = product("Mine now", "1", "USD", true);
        foreign.put("productId", bread);
        refused(SponsorProductProcesses.SAVE, sponsorToken(newSponsor(unique("s-q60b-bob"))), foreign, 404);
    }

    /** Q60c: anonymous visitors' views of a published page and clicks on listed products, read by their sponsor only. */
    @Test
    void viewsAndClicksAreCountedForTheirSponsorOnly() {
        String ann = newSponsor(unique("s-q60c-ann"));
        String bob = newSponsor(unique("s-q60c-bob"));
        String annHandle = publishedPage(ann);
        String annProduct = save(sponsorToken(ann), product("Rye", "100", "JPY", true));
        String hidden = save(sponsorToken(ann), product("Hidden", "100", "JPY", false));
        String bobProduct = save(sponsorToken(bob), product("Cake", "100", "JPY", true));
        String bobHandle = "q60c-unpublished-" + UUID.randomUUID().toString().substring(0, 6);
        run(SponsorPageProcesses.SAVE, sponsorToken(bob), body("handle", bobHandle, "language", "en",
            "headline", "Not yet"));

        countPublic(SponsorStatsConfig.PAGE_VIEWS, annHandle, "A");
        countPublic(SponsorStatsConfig.PAGE_VIEWS, annHandle, "A");
        countPublic(SponsorStatsConfig.PAGE_VIEWS, annHandle, "B");
        countPublic(SponsorStatsConfig.PAGE_VIEWS, bobHandle, "A");
        countPublic(SponsorStatsConfig.PRODUCT_CLICKS, annProduct, "A");
        countPublic(SponsorStatsConfig.PRODUCT_CLICKS, hidden, "A");
        countPublic(SponsorStatsConfig.PRODUCT_CLICKS, bobProduct, "A");
        counters.flush().block();

        // Ann reads her page's views (twice: two visitors) and her listed product's click; not the hidden product,
        // nor Bob's unpublished page; Bob reads his product's click only.
        assertThat(myCounts(ann)).containsExactlyInAnyOrder(
            SponsorStatsConfig.PAGE_VIEWS + " " + annHandle + " 2",
            SponsorStatsConfig.PRODUCT_CLICKS + " " + annProduct + " 1");
        assertThat(myCounts(bob)).containsExactly(SponsorStatsConfig.PRODUCT_CLICKS + " " + bobProduct + " 1");
    }

    private void countPublic(String counter, String key, String browser) {
        client.post().uri("/api/public/counters/" + counter + "/" + key).header("User-Agent", browser).exchange()
            .expectStatus().isNoContent();
    }

    /** The caller's counts through their dataset, as "counter key hits". */
    @SuppressWarnings("unchecked")
    private List<String> myCounts(String sponsor) {
        Map<String, Object> page = client.post().uri("/api/datasets/" + SponsorStatsConfig.MY_DATASET + "/query")
            .header("Authorization", sponsorToken(sponsor)).bodyValue(Map.of()).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream().map(item -> {
            Map<String, Object> values = (Map<String, Object>) item.get("attributes");
            return values.get("counterName") + " " + values.get("counterKey") + " "
                + ((Number) values.get("hits")).longValue();
        }).toList();
    }

    @Test
    void aSponsorHasAtMostFiftyProducts() {
        String token = sponsorToken(newSponsor(unique("s-q60b-many")));
        for (int i = 0; i < QbSponsorProducts.MAX_PRODUCTS; i++) {
            save(token, product("Item " + i, "100", "JPY", false));
        }
        assertThat(codes(refused(SponsorProductProcesses.SAVE, token, product("One more", "100", "JPY", false), 422)))
            .containsExactly(SponsorPageCodes.PRODUCT_LIMIT);
    }

    @Test
    void aQuizAndItsQuestionsShowTheirProductsAtOnce() {
        String sponsor = newSponsor(unique("s-q60b-quiz"));
        String token = sponsorToken(sponsor);
        credit(sponsor, 10_000);
        String publication = livePublication(sponsor, "Bread quiz");
        String quiz = quizOf(publication);
        String a = save(token, product("A", "100", "JPY", true));
        String b = save(token, product("B", "200", "JPY", true));
        String c = save(token, product("C", "300", "JPY", true));
        String d = save(token, product("D", "400", "JPY", true));
        publishedPage(sponsor);

        run(SponsorProductProcesses.QUIZ_PRODUCTS_SET, token, body("quizId", quiz, "quizProducts", List.of(b, a),
            "questionProducts", List.of(body("questionNo", 2, "productId", c))));
        assertThat(publicItems(PUBLICATION_PRODUCTS, "publicationId", publication))
            .extracting(row -> row.get("questionNo") + ":" + row.get("productId"))
            .containsExactlyInAnyOrder("0:" + b, "0:" + a, "2:" + c);
        // The quiz's order is the sponsor's (its place, which the pages sort by).
        assertThat(publicItems(PUBLICATION_PRODUCTS, "publicationId", publication).stream()
            .filter(row -> Integer.valueOf(0).equals(row.get("questionNo")))
            .map(row -> row.get("seq") + ":" + row.get("productId"))).containsExactlyInAnyOrder("0:" + b, "1:" + a);

        // Replaced at once: the links no longer wanted go.
        run(SponsorProductProcesses.QUIZ_PRODUCTS_SET, token, body("quizId", quiz, "quizProducts", List.of(a)));
        assertThat(publicItems(PUBLICATION_PRODUCTS, "publicationId", publication))
            .extracting(row -> row.get("productId")).containsExactly(a);
        assertThat(query("SELECT count(*) AS n FROM qb_quiz_product WHERE quiz_id = ?", quiz).getFirst())
            .containsEntry("n", 1L);

        // At most three for the quiz, one per question, the sponsor's own products, their own quiz.
        assertThat(codes(refused(SponsorProductProcesses.QUIZ_PRODUCTS_SET, token, body("quizId", quiz,
            "quizProducts", List.of(a, b, c, d)), 422))).containsExactly(SponsorPageCodes.QUIZ_PRODUCTS);
        assertThat(codes(refused(SponsorProductProcesses.QUIZ_PRODUCTS_SET, token, body("quizId", quiz,
            "questionProducts", List.of(body("questionNo", 1, "productId", a), body("questionNo", 1, "productId", b))),
            422))).containsExactly(SponsorPageCodes.QUIZ_PRODUCTS);
        String other = newSponsor(unique("s-q60b-other"));
        String foreign = save(sponsorToken(other), product("X", "1", "JPY", true));
        assertThat(codes(refused(SponsorProductProcesses.QUIZ_PRODUCTS_SET, token, body("quizId", quiz,
            "quizProducts", List.of(foreign)), 422))).containsExactly(SponsorPageCodes.QUIZ_PRODUCTS);
        refused(SponsorProductProcesses.QUIZ_PRODUCTS_SET, sponsorToken(other), body("quizId", quiz,
            "quizProducts", List.of(foreign)), 404);

        // Deleting a product takes it off the quiz.
        run(SponsorProductProcesses.DELETE, token, body("productId", a));
        assertThat(query("SELECT count(*) AS n FROM qb_quiz_product WHERE quiz_id = ?", quiz).getFirst())
            .containsEntry("n", 0L);
        assertThat(query("SELECT 1 FROM qb_sponsor_product WHERE product_id = ?", a)).isEmpty();
    }

    @Test
    void headquartersOrTheOperatorTakesAProductDownAndPutsItBack() {
        String north = newOperator(unique("North"));
        String northSession = operatorSession(member(north, unique("q60b-n")), north);
        String ann = newSponsor(unique("s-q60b-op-ann"));
        String direct = newSponsor(unique("s-q60b-op-direct"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", ann, "operatorId", north, "reason", "Q60b"));
        String handle = publishedPage(ann);
        String annProduct = save(sponsorToken(ann), product("Ann's", "100", "JPY", true));
        String directProduct = save(sponsorToken(direct), product("Direct's", "100", "JPY", true));
        // Q60b-3: the administration's list, limited for an operator's session to its own sponsors' products.
        assertThat(items("qb.admin.sponsor-products", northSession, Map.of("limit", 500)))
            .extracting(row -> row.get("productId")).contains(annProduct).doesNotContain(directProduct);
        assertThat(items("qb.admin.sponsor-products", hq(), Map.of("params", Map.of("userId", direct))))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("productId", directProduct)
                .containsEntry("company", "Company of " + direct).containsEntry("currency", "JPY"));
        assertThat(items("qb.admin.sponsor-products", northSession, Map.of("params", Map.of("userId", direct))))
            .isEmpty();

        // Headquarters: hidden whatever the sponsor saves, until put back (hidden, for the sponsor to show).
        run(SponsorProductProcesses.TAKEDOWN, contentAdmin2(), body("productId", annProduct, "reason", "Fake"));
        assertThat(publicItems(PAGE_PRODUCTS, "handle", handle)).isEmpty();
        Map<String, Object> relist = product("Ann's", "100", "JPY", true);
        relist.put("productId", annProduct);
        assertThat(run(SponsorProductProcesses.SAVE, sponsorToken(ann), relist))
            .containsEntry("status", QbSponsorProducts.TAKEN_DOWN);
        // The operator does not undo headquarters' takedown.
        assertThat(codes(refused(SponsorProductProcesses.OPERATOR_RESTORE, northSession, body("productId", annProduct),
            422))).containsExactly(SponsorPageCodes.PRODUCT_STATE);
        run(SponsorProductProcesses.RESTORE, contentAdmin2(), body("productId", annProduct));
        assertThat(productStatus(annProduct)).isEqualTo(QbSponsorProducts.UNLISTED);
        run(SponsorProductProcesses.SAVE, sponsorToken(ann), relist);
        assertThat(publicItems(PAGE_PRODUCTS, "handle", handle)).hasSize(1);

        // The operator: its own sponsors' products only, and puts back its own takedowns.
        refused(SponsorProductProcesses.OPERATOR_TAKEDOWN, northSession, body("productId", directProduct,
            "reason", "Not ours"), 404);
        run(SponsorProductProcesses.OPERATOR_TAKEDOWN, northSession, body("productId", annProduct, "reason", "Ours"));
        assertThat(productStatus(annProduct)).isEqualTo(QbSponsorProducts.TAKEN_DOWN);
        run(SponsorProductProcesses.OPERATOR_RESTORE, northSession, body("productId", annProduct));
        assertThat(productStatus(annProduct)).isEqualTo(QbSponsorProducts.UNLISTED);
        assertThat(productStatus(directProduct)).isEqualTo(QbSponsorProducts.LISTED);
    }

    // ---- operators (as OperatorIsolationIT) ------------------------------------------------------------------------

    private String hq() {
        return TestTokens.bearer(tokens, "q60b-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ,
            QbPermissions.ADMIN_PUBLICATION_READ);
    }

    private String operatorSession(String userId, String operatorId) {
        Actor actor = new Actor(userId, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operatorId));
        return "Bearer " + tokens.issue(actor).token();
    }

    private String newOperator(String name) {
        return (String) run(OperatorProcesses.SAVE, hq(), body("name", name, "contactName", "Owner of " + name,
            "contactEmail", "ops@" + name.toLowerCase() + ".example")).get("operatorId");
    }

    private String member(String operatorId, String name) {
        String userId = createUser(name);
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operatorId, "userId", userId, "lead", true));
        return userId;
    }
}
