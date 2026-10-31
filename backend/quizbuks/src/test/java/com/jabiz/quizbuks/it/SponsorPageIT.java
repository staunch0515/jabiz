package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.page.QbSponsorPages;
import com.jabiz.quizbuks.page.SponsorPageCodes;
import com.jabiz.quizbuks.page.SponsorPageProcesses;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sponsors' pages (docs/quizbuks/plans/Q60-sponsor-page.md, Q60a): a draft goes to review once, is then shown to
 * anybody by its handle with the sponsor's live publications, and changes at once afterwards; the sponsor takes it
 * back, headquarters or the sponsor's operator takes it down (back only after a new review), and a ban takes it down.
 */
class SponsorPageIT extends PlayItSupport {

    private static final String PAGE_TEMPLATE = "qb.public.sponsor-page";
    private static final String PUBLICATIONS_TEMPLATE = "qb.public.sponsor-page-publications";

    private static String newHandle() {
        return "q60-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String image(String token) {
        return (String) upload(QbSponsorPages.IMAGE_POLICY, FileSamples.jpeg(800, 400), "cover.jpg", "image/jpeg",
            token).expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    private static Map<String, Object> page(String handle, String headline) {
        return body("handle", handle, "language", "en", "headline", headline, "story", "We make **things**.",
            "website", "https://example.com/shop");
    }

    private Map<String, Object> save(String token, Map<String, Object> page) {
        return run(SponsorPageProcesses.SAVE, token, page);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> publicItems(String template, String handle) {
        Map<String, Object> page = client.get().uri(b -> b.path("/api/public/queries/" + template)
                .queryParam("p.handle", "{handle}").build(handle))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    /**
     * The sponsor's page as both pages of a publication name it, which must agree: the share page's (anonymous) and the
     * app's (a taker's); null while the page is not published.
     */
    @SuppressWarnings("unchecked")
    private Object sponsorHandleOf(String publicationId) {
        Map<String, Object> shared = client.get().uri(b -> b.path("/api/public/queries/qb.public.publication")
                .queryParam("p.publicationId", "{id}").build(publicationId))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        Object anonymous = ((List<Map<String, Object>>) shared.get("items")).getFirst().get("sponsorHandle");
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("publicationId", publicationId);
        params.put("at", clock.instant().toString());
        params.put("country", "JP");
        Object app = items("qb.app.publication", taker(newTaker(unique("t-q60-page"), "JP")),
            Map.of("params", params)).getFirst().get("sponsorHandle");
        assertThat(app).isEqualTo(anonymous);
        return anonymous;
    }

    private static Map<String, Object> stored(String userId) {
        return query("SELECT * FROM qb_sponsor_page WHERE user_id = ?", userId).getFirst();
    }

    private void sponsorAutoApprove(boolean on) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", QbParams.REVIEW_SPONSOR_AUTO, "value", String.valueOf(on)),
            "reason", "Review switch"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    /** Publishes the sponsor's page through a review; returns its handle. */
    private String published(String sponsor, String headline) {
        String token = sponsorToken(sponsor);
        String handle = newHandle();
        save(token, page(handle, headline));
        Map<String, Object> submitted = run(SponsorPageProcesses.SUBMIT, token, body());
        assertThat(submitted).containsEntry("status", QbSponsorPages.PENDING);
        approve((String) submitted.get("approvalRequestId"));
        assertThat(stored(sponsor)).containsEntry("status", QbSponsorPages.PUBLISHED);
        return handle;
    }

    private void approve(String requestId) {
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", requestId, "decision", "APPROVE"));
        deliverEvents();
    }

    private String contentAdminToken() {
        return TestTokens.bearer(tokens, "q60-content-admin", QbPermissions.ADMIN_CONTENT_WRITE);
    }

    @Test
    void aPageIsReviewedOnceThenShownAndChangedAtOnce() {
        String sponsor = newSponsor(unique("s-q60-ann"));
        String token = sponsorToken(sponsor);
        String cover = image(token);
        String handle = newHandle();

        // Typed in capitals, stored lowercase: one name whatever the case.
        Map<String, Object> draft = save(token, body("handle", handle.toUpperCase(), "language", "en", "headline",
            "Fresh bread daily", "story", "Since 1990.", "cover", cover, "website", "https://example.com"));
        assertThat(draft).containsEntry("handle", handle).containsEntry("status", QbSponsorPages.DRAFT)
            .containsEntry("revision", 1);
        assertThat(publicItems(PAGE_TEMPLATE, handle)).isEmpty();

        Map<String, Object> submitted = run(SponsorPageProcesses.SUBMIT, token, body("baseRevision", 1));
        assertThat(submitted).containsEntry("status", QbSponsorPages.PENDING).containsEntry("approval", "PENDING");
        assertThat(publicItems(PAGE_TEMPLATE, handle)).isEmpty();
        approve((String) submitted.get("approvalRequestId"));

        // Shown to anybody, with the company of the onboarding and the cover as a public file.
        assertThat(publicItems(PAGE_TEMPLATE, handle)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("headline", "Fresh bread daily").containsEntry("company", "Company of " + sponsor)
            .containsEntry("cover", cover).containsEntry("language", "en")
            .containsOnlyKeys("handle", "language", "headline", "story", "logo", "cover", "website", "company"));
        client.get().uri("/api/public/files/" + cover).exchange().expectStatus().isOk();
        credit(sponsor, 10_000);
        String publication = livePublication(sponsor, "Ann's bread quiz");
        assertThat(publicItems(PUBLICATIONS_TEMPLATE, handle)).extracting(row -> row.get("publicationId"))
            .containsExactly(publication);
        // The publication's pages name the page the sponsor's name links to (Q60a-2): its share page and the app's.
        assertThat(sponsorHandleOf(publication)).isEqualTo(handle);

        // Changes apply at once; a stale revision is refused.
        long revision = ((Number) stored(sponsor).get("revision")).longValue();
        Map<String, Object> changed = page(handle, "Bread and cakes");
        changed.put("cover", cover);
        changed.put("baseRevision", revision);
        assertThat(save(token, changed)).containsEntry("status", QbSponsorPages.PUBLISHED);
        assertThat(publicItems(PAGE_TEMPLATE, handle)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("headline", "Bread and cakes"));
        assertThat(codes(refused(SponsorPageProcesses.SAVE, token, changed, 422)))
            .containsExactly(SponsorPageCodes.CHANGED);
        // Submitting a published page is refused.
        assertThat(codes(refused(SponsorPageProcesses.SUBMIT, token, body(), 422)))
            .containsExactly(SponsorPageCodes.STATE);

        // A new handle: the old link finds nothing.
        String renamed = newHandle();
        Map<String, Object> rename = page(renamed, "Bread and cakes");
        rename.put("cover", cover);
        save(token, rename);
        assertThat(publicItems(PAGE_TEMPLATE, handle)).isEmpty();
        assertThat(publicItems(PAGE_TEMPLATE, renamed)).hasSize(1);

        // Taken back by the sponsor: gone; publishing it again needs a review (its text changed since approval).
        assertThat(run(SponsorPageProcesses.UNPUBLISH, token, body())).containsEntry("status", QbSponsorPages.DRAFT);
        assertThat(publicItems(PAGE_TEMPLATE, renamed)).isEmpty();
        assertThat(publicItems(PUBLICATIONS_TEMPLATE, renamed)).isEmpty();
        assertThat(sponsorHandleOf(publication)).isNull();
        // Its images stop being public at once, not after the remembered decision's lifetime.
        client.get().uri("/api/public/files/" + cover).exchange().expectStatus().isNotFound();
        assertThat(run(SponsorPageProcesses.SUBMIT, token, body())).containsEntry("status", QbSponsorPages.PENDING);
        assertThat(query("SELECT count(*) AS n FROM qb_sponsor_page WHERE user_id = ?", sponsor).getFirst())
            .containsEntry("n", 1L);
    }

    @Test
    void handlesAreUniqueAndTheFieldsChecked() {
        String ann = newSponsor(unique("s-q60-h1"));
        String bob = newSponsor(unique("s-q60-h2"));
        String handle = newHandle();
        save(sponsorToken(ann), page(handle, "Ann's"));

        assertThat(codes(refused(SponsorPageProcesses.SAVE, sponsorToken(bob), page(handle.toUpperCase(), "Bob's"),
            422))).containsExactly(SponsorPageCodes.HANDLE_TAKEN);
        assertThat(codes(refused(SponsorPageProcesses.SAVE, sponsorToken(bob), page("-bad-", "Bob's"), 400)))
            .contains(SponsorPageCodes.HANDLE_FORMAT);
        assertThat(codes(refused(SponsorPageProcesses.SAVE, sponsorToken(bob), page("ab", "Bob's"), 400)))
            .contains(SponsorPageCodes.HANDLE_FORMAT);
        Map<String, Object> plainHttp = page(newHandle(), "Bob's");
        plainHttp.put("website", "http://example.com");
        assertThat(codes(refused(SponsorPageProcesses.SAVE, sponsorToken(bob), plainHttp, 400)))
            .contains(SponsorPageCodes.WEBSITE_FORMAT);
        // Another's image: refused.
        Map<String, Object> borrowed = page(newHandle(), "Bob's");
        borrowed.put("logo", image(sponsorToken(ann)));
        assertThat(codes(refused(SponsorPageProcesses.SAVE, sponsorToken(bob), borrowed, 422)))
            .containsExactly("QB_FILE_NOT_OWN");
        assertThat(query("SELECT 1 FROM qb_sponsor_page WHERE user_id = ?", bob)).isEmpty();
    }

    @Test
    void onlyAnApprovedSponsorSubmitsAndTheReviewersReasonIsKept() {
        // Signed up, not onboarded: the draft is kept, publishing waits for the onboarding.
        String newcomer = user(unique("s-q60-new"));
        save(sponsorToken(newcomer), page(newHandle(), "Soon"));
        assertThat(codes(refused(SponsorPageProcesses.SUBMIT, sponsorToken(newcomer), body(), 422)))
            .containsExactly(SponsorPageCodes.SPONSOR_NOT_APPROVED);

        String sponsor = newSponsor(unique("s-q60-rej"));
        String token = sponsorToken(sponsor);
        String handle = newHandle();
        save(token, page(handle, "Too loud"));
        String first = (String) run(SponsorPageProcesses.SUBMIT, token, body()).get("approvalRequestId");
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", first, "decision", "REJECT", "reason",
            "Tone it down"));
        deliverEvents();
        assertThat(stored(sponsor)).containsEntry("status", QbSponsorPages.REJECTED)
            .containsEntry("reject_reason", "Tone it down");

        // Submitted again, then changed while in review: back to the draft, the review withdrawn.
        String second = (String) run(SponsorPageProcesses.SUBMIT, token, body()).get("approvalRequestId");
        assertThat(save(token, page(handle, "Calmer"))).containsEntry("status", QbSponsorPages.DRAFT);
        assertThat(query("SELECT status FROM sys_approval_request_version WHERE request_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", second).getFirst().get("status")).isEqualTo("WITHDRAWN");
        // A late approval of the withdrawn request changes nothing.
        refused("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", second, "decision", "APPROVE"), 422);
        assertThat(stored(sponsor)).containsEntry("status", QbSponsorPages.DRAFT);
    }

    @Test
    void aPageTakenDownComesBackOnlyThroughAReview() {
        String sponsor = newSponsor(unique("s-q60-down"));
        String token = sponsorToken(sponsor);
        String handle = published(sponsor, "Bold claims");

        Map<String, Object> down = run(SponsorPageProcesses.TAKEDOWN, contentAdminToken(), body("userId", sponsor,
            "reason", "Misleading claims"));
        assertThat(down).containsEntry("status", QbSponsorPages.TAKEN_DOWN);
        assertThat(stored(sponsor)).containsEntry("takedown_reason", "Misleading claims");
        assertThat(publicItems(PAGE_TEMPLATE, handle)).isEmpty();
        // Once down, it is not taken down again.
        assertThat(codes(refused(SponsorPageProcesses.TAKEDOWN, contentAdminToken(), body("userId", sponsor,
            "reason", "Again"), 422))).containsExactly(SponsorPageCodes.STATE);
        // Sponsors cannot take pages down.
        refused(SponsorPageProcesses.TAKEDOWN, token, body("userId", sponsor, "reason", "Mine"), 403);

        // Even with reviews switched off, the same text goes back to a person's review; and still does after the
        // page left the review for its draft (changed, or taken back) without being published.
        sponsorAutoApprove(true);
        try {
            assertThat(run(SponsorPageProcesses.SUBMIT, token, body())).containsEntry("status", QbSponsorPages.PENDING);
            assertThat(save(token, page(handle, "Modest claims"))).containsEntry("status", QbSponsorPages.DRAFT);
            assertThat(run(SponsorPageProcesses.SUBMIT, token, body())).containsEntry("status", QbSponsorPages.PENDING);
            assertThat(run(SponsorPageProcesses.UNPUBLISH, token, body())).containsEntry("status",
                QbSponsorPages.DRAFT);
            Map<String, Object> again = run(SponsorPageProcesses.SUBMIT, token, body());
            assertThat(again).containsEntry("status", QbSponsorPages.PENDING);
            approve((String) again.get("approvalRequestId"));
            // Published after its review, the takedown is behind it: changes and the switch work as for any page.
            run(SponsorPageProcesses.UNPUBLISH, token, body());
            assertThat(run(SponsorPageProcesses.SUBMIT, token, body())).containsEntry("status",
                QbSponsorPages.PUBLISHED);
        } finally {
            sponsorAutoApprove(false);
        }
        assertThat(publicItems(PAGE_TEMPLATE, handle)).hasSize(1);

        // A ban takes it down too, with the fixed reason the sponsor reads.
        run("QB_USER_BAN", TestTokens.bearer(tokens, "q60-banner", QbPermissions.ADMIN_USERS_BAN),
            body("userId", sponsor, "reason", "Fraud"));
        assertThat(stored(sponsor)).containsEntry("status", QbSponsorPages.TAKEN_DOWN)
            .containsEntry("takedown_reason", PublicationAdmin.ACCOUNT_SUSPENDED);
        assertThat(publicItems(PAGE_TEMPLATE, handle)).isEmpty();
    }

    @Test
    void anOperatorSeesAndTakesDownItsOwnSponsorsPagesOnly() {
        String north = newOperator(unique("North"));
        String northSession = operatorSession(member(north, unique("q60-n")), north);
        String ann = newSponsor(unique("s-q60-op-ann"));
        String direct = newSponsor(unique("s-q60-op-direct"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", ann, "operatorId", north, "reason", "Q60"));

        // Ann's page goes to her operator's review.
        String token = sponsorToken(ann);
        save(token, page(newHandle(), "Ann's page"));
        String requestId = (String) run(SponsorPageProcesses.SUBMIT, token, body()).get("approvalRequestId");
        assertThat(query("SELECT scope_name, scope_value FROM sys_approval_request_version WHERE request_id = ?::uuid",
            requestId).getFirst()).containsEntry("scope_name", QbOperators.SCOPE).containsEntry("scope_value", north);
        approve(requestId);
        published(direct, "Direct page");

        assertThat(datasetIds(QbSponsorPages.DEFAULT_DATASET, northSession)).contains(ann).doesNotContain(direct);
        assertThat(datasetIds(QbSponsorPages.DEFAULT_DATASET, hq())).contains(ann, direct);
        // Q60a-3: the administration's template likewise (the list, the review queue, one page).
        assertThat(items("qb.admin.sponsor-pages", northSession, Map.of("limit", 500)))
            .extracting(row -> row.get("userId")).contains(ann).doesNotContain(direct);
        assertThat(items("qb.admin.sponsor-pages", hq(), Map.of("params", Map.of("userId", direct))))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", QbSponsorPages.PUBLISHED)
                .containsEntry("headline", "Direct page").containsEntry("company", "Company of " + direct));
        assertThat(items("qb.admin.sponsor-pages", northSession, Map.of("params", Map.of("userId", direct)))).isEmpty();
        refused(SponsorPageProcesses.OPERATOR_TAKEDOWN, northSession, body("userId", direct, "reason", "Not ours"),
            404);
        assertThat(run(SponsorPageProcesses.OPERATOR_TAKEDOWN, northSession, body("userId", ann, "reason", "Ours")))
            .containsEntry("status", QbSponsorPages.TAKEN_DOWN);
        assertThat(stored(direct)).containsEntry("status", QbSponsorPages.PUBLISHED);
    }

    // ---- operators (as OperatorIsolationIT) ------------------------------------------------------------------------

    private String hq() {
        return TestTokens.bearer(tokens, "q60-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ,
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

    @SuppressWarnings("unchecked")
    private List<Object> datasetIds(String dataset, String token) {
        var result = client.post().uri("/api/datasets/" + dataset + "/query").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of("limit", 1000)).exchange().expectBody(MAP)
            .returnResult();
        assertThat(result.getStatus().value()).as(dataset + ": " + result.getResponseBody()).isEqualTo(200);
        return ((List<Map<String, Object>>) result.getResponseBody().get("items")).stream()
            .map(row -> row.get("id")).map(String::valueOf).map(Object.class::cast).toList();
    }
}
