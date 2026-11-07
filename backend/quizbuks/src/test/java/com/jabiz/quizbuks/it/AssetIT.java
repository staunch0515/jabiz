package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.AssetCodes;
import com.jabiz.quizbuks.asset.AssetProcesses;
import com.jabiz.quizbuks.asset.AssetRules;
import com.jabiz.quizbuks.asset.QbAssets;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-C1a (docs/quizbuks/plans/impl/F-C1.md): a sponsor's asset library: saved, changed and deleted by its owner only,
 * at most {@code qb.assets.max-count} under the library's lock, read by support and the sponsor's operator, and
 * collected from the sponsor's existing content once each.
 */
class AssetIT extends PlayItSupport {

    private static final String CANDIDATES = "qb.sponsor.asset-candidates";

    private String uploadImage(String token) {
        return (String) upload(QbContent.IMAGE_POLICY, FileSamples.jpeg(400, 300), "picture.jpg", "image/jpeg", token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    private String uploadPdf(String token) {
        return (String) upload(QbContent.PDF_POLICY, FileSamples.pdf(), "paper.pdf", "application/pdf", token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    private static Map<String, Object> asset(String kind, String name, Object... more) {
        Map<String, Object> asset = body("kind", kind, "name", name, "language", "en");
        asset.putAll(body(more));
        return asset;
    }

    private String save(String token, Map<String, Object> asset) {
        return (String) run(AssetProcesses.SAVE, token, asset).get("assetId");
    }

    private static Map<String, Object> assetRow(String assetId) {
        return query("SELECT * FROM qb_asset WHERE asset_id = ?", assetId).getFirst();
    }

    @Test
    void theSponsorSavesChangesAndDeletesTheirOwnAssets() {
        String ann = newSponsor(unique("s-fc1-ann"));
        String bob = newSponsor(unique("s-fc1-bob"));
        String annToken = sponsorToken(ann);
        String bobToken = sponsorToken(bob);
        String picture = uploadImage(annToken);

        Map<String, Object> saved = run(AssetProcesses.SAVE, annToken, asset("IMAGE", " Spring photo ",
            "image", picture, "description", "Our shop in spring", "tags", List.of(" spring ", "Sale"),
            "translations", Map.of("ja", Map.of("name", "春の写真"))));
        String photo = (String) saved.get("assetId");
        assertThat(saved).containsEntry("revision", 1);
        assertThat(assetRow(photo)).containsEntry("sponsor_id", ann).containsEntry("name", "Spring photo")
            .containsEntry("tags", "[\"spring\",\"Sale\"]").containsEntry("image", UUID.fromString(picture))
            .containsEntry("content_hash", null).containsEntry("kind", "IMAGE");
        assertThat(String.valueOf(assetRow(photo).get("translations"))).contains("春の写真");
        assertThat(AssetProcesses.tagsOf(assetRow(photo).get("tags"))).containsExactly("spring", "Sale");

        // The sponsor's own view: theirs only.
        assertThat(rows(QbAssets.MY_DATASET, annToken)).extracting(r -> r.get("assetId")).containsExactly(photo);
        assertThat(rows(QbAssets.MY_DATASET, bobToken)).isEmpty();

        // A change from the revision seen; a change from an older one is refused; the kind stays.
        Map<String, Object> change = asset("IMAGE", "Spring photo", "image", picture, "baseRevision", 1,
            "tags", List.of("spring"));
        change.put("assetId", photo);
        assertThat(run(AssetProcesses.SAVE, annToken, change)).containsEntry("revision", 2);
        assertThat(assetRow(photo)).containsEntry("tags", "[\"spring\"]");
        // No translations given: those stored stay.
        assertThat(String.valueOf(assetRow(photo).get("translations"))).contains("春の写真");
        assertThat(codes(refused(AssetProcesses.SAVE, annToken, change, 422))).containsExactly(AssetCodes.CHANGED);
        Map<String, Object> otherKind = asset("PDF", "Spring photo", "pdf", uploadPdf(annToken));
        otherKind.put("assetId", photo);
        assertThat(codes(refused(AssetProcesses.SAVE, annToken, otherKind, 422)))
            .containsExactly(AssetCodes.KIND_FIXED);

        // Another's asset is not found; another's file does not go in.
        refused(AssetProcesses.SAVE, bobToken, change, 404);
        refused(AssetProcesses.DELETE, bobToken, body("assetId", photo), 404);
        assertThat(violations(refused(AssetProcesses.SAVE, bobToken, asset("IMAGE", "Mine now", "image", picture),
            422))).singleElement().satisfies(v -> assertThat(v).containsEntry("ruleCode", AssetCodes.FILE_NOT_OWN)
                .containsEntry("field", "image"));

        // Every problem at once.
        assertThat(codes(refused(AssetProcesses.SAVE, annToken, asset("TEXT", " ", "body", "x".repeat(501),
            "usage", AssetRules.REDEEM_DESCRIPTION, "url", "https://example.com", "tags", List.of("a", "A")), 422)))
            .containsExactlyInAnyOrder(AssetCodes.NAME_BLANK, AssetCodes.TAG_TWICE, AssetCodes.FIELD_NOT_ALLOWED,
                AssetCodes.TOO_LONG);

        // A text's hash is the SQL's (what the template of candidates compares).
        String article = save(annToken, asset("ARTICLE", "How to brew", "body", "Grind, pour, wait. 淹れる。"));
        assertThat(query("SELECT content_hash = encode(sha256(convert_to(body, 'UTF8')), 'hex') AS same "
            + "FROM qb_asset WHERE asset_id = ?", article).getFirst()).containsEntry("same", true);

        // Deleting goes with the records of where it was copied to (F-C1b writes them); what was copied stays.
        execute("INSERT INTO qb_asset_use (use_id, asset_id, sponsor_id, target_kind, target_id, asset_revision, "
            + "used_at, version) VALUES (?, ?, ?, 'QUIZ_COVER', ?, 2, now(), 1)", UUID.randomUUID().toString(), photo,
            ann, UUID.randomUUID().toString());
        assertThat(rows(QbAssets.MY_USE_DATASET, annToken)).singleElement()
            .satisfies(use -> assertThat(use).containsEntry("assetId", photo));
        assertThat(codes(refused(AssetProcesses.DELETE, annToken, body("assetId", photo, "baseRevision", 1), 422)))
            .containsExactly(AssetCodes.CHANGED);
        assertThat(run(AssetProcesses.DELETE, annToken, body("assetId", photo, "baseRevision", 2)))
            .containsEntry("uses", 1);
        assertThat(query("SELECT 1 FROM qb_asset WHERE asset_id = ?", photo)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_asset_use WHERE asset_id = ?", photo)).isEmpty();
        refused(AssetProcesses.DELETE, annToken, body("assetId", photo), 404);
        assertThat(rows(QbAssets.MY_DATASET, annToken)).extracting(r -> r.get("assetId")).containsExactly(article);
    }

    @Test
    void theCountIsLimitedUnderTheLibrarysLock() throws Exception {
        String carl = newSponsor(unique("s-fc1-carl"));
        String token = sponsorToken(carl);
        run("PARAM_SET", admin(), body("key", QbParams.ASSETS_MAX_COUNT, "value", "3"));
        try {
            String first = save(token, asset("LINK", "One", "url", "https://example.com/1"));
            save(token, asset("LINK", "Two", "url", "https://example.com/2"));
            // Two at once for the last place: the lock lets one in.
            List<CompletableFuture<Integer>> racing = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Map<String, Object> asset = asset("LINK", "Race " + i, "url", "https://example.com/race" + i);
                racing.add(CompletableFuture.supplyAsync(() -> call(AssetProcesses.SAVE, token, asset)
                    .expectBody(MAP).returnResult().getStatus().value()));
            }
            assertThat(racing.stream().map(CompletableFuture::join).toList()).containsExactlyInAnyOrder(200, 422);
            assertThat(query("SELECT count(*) AS n FROM qb_asset WHERE sponsor_id = ?", carl).getFirst())
                .containsEntry("n", 3L);
            assertThat(codes(refused(AssetProcesses.SAVE, token, asset("LINK", "Four", "url",
                "https://example.com/4"), 422))).containsExactly(AssetCodes.LIMIT);
            assertThat(codes(refused(AssetProcesses.COLLECT, token, body("items", List.of(asset("LINK", "Five",
                "url", "https://example.com/5"))), 422))).containsExactly(AssetCodes.LIMIT);
            // A change is no new asset.
            Map<String, Object> change = asset("LINK", "One again", "url", "https://example.com/1");
            change.put("assetId", first);
            run(AssetProcesses.SAVE, token, change);
        } finally {
            run("PARAM_SET", admin(), body("key", QbParams.ASSETS_MAX_COUNT, "value", "1000"));
        }
    }

    @Test
    void supportAndTheSponsorsOperatorReadTheAssetsAndNobodyElseWritesThem() {
        // F-S1: an operator is created with its lead (its own sponsor), who reads for it.
        NewOperator created = newOperatorWithLead(hq(), unique("North"), null);
        String north = created.operatorId();
        String northSession = operatorSession(created.leadId(), north);
        String ann = newSponsor(unique("s-fc1-op-ann"));
        String direct = newSponsor(unique("s-fc1-op-direct"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", ann, "operatorId", north, "reason", "F-C1"));
        String annAsset = save(sponsorToken(ann), asset("LINK", "Ann's", "url", "https://example.com/ann"));
        String directAsset = save(sponsorToken(direct), asset("LINK", "Direct's", "url",
            "https://example.com/direct"));
        for (String dataset : List.of(QbAssets.DEFAULT_DATASET, QbAssets.USE_DATASET)) {
            queryDataset(dataset, northSession).expectStatus().isOk();
        }
        assertThat(rowsLimited(QbAssets.DEFAULT_DATASET, northSession)).contains(annAsset).doesNotContain(directAsset);
        assertThat(rowsLimited(QbAssets.DEFAULT_DATASET, hq())).contains(annAsset, directAsset);
        // Reading is all: the processes are the sponsor's.
        refused(AssetProcesses.SAVE, northSession, asset("LINK", "Ours", "url", "https://example.com/x"), 403);
        assertThat(query("SELECT 1 FROM qb_asset WHERE asset_id = ?", annAsset)).hasSize(1);
        // A sponsor's own view is not the administrators'.
        queryDataset(QbAssets.DEFAULT_DATASET, sponsorToken(ann)).expectStatus().isForbidden();
    }

    @SuppressWarnings("unchecked")
    private List<Object> rowsLimited(String dataset, String token) {
        Map<String, Object> page = client.post().uri("/api/datasets/{id}/query", dataset)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of("limit", 100))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(item -> ((Map<String, Object>) item.get("attributes")).get("assetId")).toList();
    }

    @Test
    void collectingListsTheSponsorsContentOnceAndTakesItInOnce() {
        String dana = newSponsor(unique("s-fc1-dana"));
        String token = sponsorToken(dana);
        String cover = uploadImage(token);
        String photo = uploadImage(token);
        String figure = uploadImage(token);
        String paper = uploadPdf(token);
        String quizId = newQuiz(token, "Coffee basics");
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Coffee basics", "intro", "Learn to brew.",
            "cover", cover));
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "ARTICLE", "title", "Brewing",
            "body", "Grind, pour, wait."));
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "LINK", "title", "Our shop",
            "url", "https://shop.example/coffee"));
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "VIDEO_LINK", "title", "Watch",
            "url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "durationSec", 90));
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "PDF", "title", "Paper", "pdf", paper));
        // The cover again, in an image group: listed once.
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "IMAGES", "title", "Pictures",
            "images", List.of(body("image", photo, "caption", "Beans"), body("image", cover))));
        // A question's image and an option's, the same file: once.
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Which?", "image", figure,
            "options", List.of(body("image", figure, "correct", true), body("text", "none"))));
        // The same text in an article of another quiz: once.
        String other = newQuiz(token, "Tea basics");
        run("QB_MATERIAL_SAVE", token, body("quizId", other, "kind", "ARTICLE", "title", "Also brewing",
            "body", "Grind, pour, wait."));
        // A removed quiz's content is not listed.
        String removed = newQuiz(token, "Gone");
        run("QB_MATERIAL_SAVE", token, body("quizId", removed, "kind", "LINK", "title", "Gone",
            "url", "https://shop.example/gone"));
        run("QB_QUIZ_DELETE", token, body("quizId", removed));
        // A publication's cover and in-store description.
        String versionId = version(token, "Coffee quiz");
        String pubCover = uploadImage(token);
        Map<String, Object> draft = complete(versionId, "Coffee week", clock.instant().plusSeconds(86_400),
            clock.instant().plusSeconds(8 * 86_400));
        draft.put("cover", pubCover);
        run(com.jabiz.quizbuks.publishing.RewardMethodProcesses.SET, TestTokens.bearer(tokens, "fc1-methods",
            QbPermissions.ADMIN_REWARD_METHODS), body("userId", dana, "methods",
            List.of(com.jabiz.quizbuks.publishing.RewardMethod.PLATFORM,
                com.jabiz.quizbuks.publishing.RewardMethod.OFFLINE_REDEEM), "note", "F-C1"));
        draft.put("rewardMethod", com.jabiz.quizbuks.publishing.RewardMethod.OFFLINE_REDEEM);
        draft.put("redeemDescription", "A free coffee at our counter");
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, draft).get("publicationId");
        // Another sponsor's content is never theirs to collect.
        String eve = newSponsor(unique("s-fc1-eve"));
        String eveQuiz = newQuiz(sponsorToken(eve), "Eve's");
        run("QB_MATERIAL_SAVE", sponsorToken(eve), body("quizId", eveQuiz, "kind", "LINK", "title", "Eve",
            "url", "https://eve.example"));

        List<Map<String, Object>> candidates = items(CANDIDATES, token, Map.of("limit", 100));
        Map<String, String> bySource = candidates.stream().collect(Collectors.toMap(
            c -> c.get("sourceKind") + "/" + c.get("kind") + "/" + (c.get("image") != null ? c.get("image")
                : c.get("pdf") != null ? c.get("pdf") : c.get("url") != null ? c.get("url") : c.get("body")),
            c -> String.valueOf(c.get("sourceId"))));
        assertThat(bySource.keySet()).containsExactlyInAnyOrder(
            "QUIZ_COVER/IMAGE/" + cover, "MATERIAL/IMAGE/" + photo, "QUESTION_IMAGE/IMAGE/" + figure,
            "MATERIAL/PDF/" + paper, "MATERIAL/ARTICLE/Grind, pour, wait.", "MATERIAL/LINK/https://shop.example/coffee",
            "MATERIAL/VIDEO_LINK/https://www.youtube.com/watch?v=dQw4w9WgXcQ", "QUIZ_INTRO/TEXT/Learn to brew.",
            "PUBLICATION_COVER/IMAGE/" + pubCover, "PUBLICATION_REDEEM/TEXT/A free coffee at our counter");
        assertThat(bySource).containsEntry("QUIZ_COVER/IMAGE/" + cover, quizId)
            .containsEntry("PUBLICATION_REDEEM/TEXT/A free coffee at our counter", publicationId);
        Map<String, Object> video = candidates.stream().filter(c -> "VIDEO_LINK".equals(c.get("kind"))).findFirst()
            .orElseThrow();
        assertThat(video).containsEntry("durationSec", 90).containsEntry("name", "Watch")
            .containsEntry("language", "en").containsEntry("quizId", quizId)
            .containsEntry("contentHash", AssetRules.sha256("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
            .containsEntry("candidateKey", "t:VIDEO_LINK::" + AssetRules.sha256(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertThat(candidates.stream().filter(c -> "TEXT".equals(c.get("kind"))).map(c -> c.get("usage")))
            .containsExactlyInAnyOrder(AssetRules.QUIZ_INTRO, AssetRules.REDEEM_DESCRIPTION);
        assertThat(candidates.stream().filter(c -> c.get("image") != null).map(c -> c.get("candidateKey")))
            .allSatisfy(key -> assertThat(String.valueOf(key)).startsWith("f:"));

        // Another's file in the batch: refused whole, saying which item.
        String evesPicture = uploadImage(sponsorToken(eve));
        assertThat(violations(refused(AssetProcesses.COLLECT, token, body("items", List.of(
            asset("LINK", "Fine", "url", "https://shop.example/fine"), asset("IMAGE", "Eve's", "image", evesPicture))),
            422))).singleElement().satisfies(v -> assertThat(v).containsEntry("ruleCode", AssetCodes.FILE_NOT_OWN)
                .containsEntry("field", "items[1].image"));

        // Ticked: all of them, one twice; the second is skipped as the same as the first.
        List<Map<String, Object>> ticked = new ArrayList<>(candidates.stream().map(AssetIT::item).toList());
        ticked.add(item(candidates.getFirst()));
        Map<String, Object> output = run(AssetProcesses.COLLECT, token, body("items", ticked));
        assertThat((List<?>) output.get("created")).hasSize(candidates.size());
        assertThat((List<Map<String, Object>>) cast(output.get("skipped"))).singleElement()
            .satisfies(skip -> {
                assertThat(skip).containsEntry("index", candidates.size()).containsEntry("sameAs", 0);
                assertThat(skip.get("assetId")).isNull();
            });
        assertThat(query("SELECT count(*) AS n FROM qb_asset WHERE sponsor_id = ?", dana).getFirst())
            .containsEntry("n", (long) candidates.size());

        // Collected, nothing is left to list; collecting again skips what the library has.
        assertThat(items(CANDIDATES, token, Map.of("limit", 100))).isEmpty();
        Map<String, Object> again = run(AssetProcesses.COLLECT, token, body("items", List.of(item(video))));
        assertThat((List<?>) again.get("created")).isEmpty();
        String videoAsset = String.valueOf(query("SELECT asset_id FROM qb_asset WHERE sponsor_id = ? AND kind = "
            + "'VIDEO_LINK'", dana).getFirst().get("asset_id"));
        assertThat((List<Map<String, Object>>) cast(again.get("skipped"))).singleElement()
            .satisfies(skip -> {
                assertThat(skip).containsEntry("assetId", videoAsset);
                assertThat(skip.get("sameAs")).isNull();
            });
        // New content shows up the next time; a text the library has under another use is another candidate.
        run("QB_QUIZ_SAVE", token, body("quizId", other, "title", "Tea basics", "intro", "Grind, pour, wait."));
        assertThat(items(CANDIDATES, token, Map.of("limit", 100))).singleElement()
            .satisfies(c -> assertThat(c).containsEntry("sourceKind", QbAssets.QUIZ_INTRO)
                .containsEntry("sourceId", other));
        // Another sponsor sees none of Dana's.
        assertThat(items(CANDIDATES, sponsorToken(eve), Map.of("limit", 100))).extracting(c -> c.get("url"))
            .containsExactly("https://eve.example");
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    /** A candidate as the console sends it to be collected. */
    private static Map<String, Object> item(Map<String, Object> candidate) {
        Map<String, Object> item = new LinkedHashMap<>();
        for (String field : List.of("kind", "usage", "name", "description", "language", "image", "pdf", "audio", "url",
            "durationSec", "body")) {
            if (candidate.get(field) != null) {
                item.put(field, candidate.get(field));
            }
        }
        return item;
    }

    // ---- operators (as SponsorProductIT) ---------------------------------------------------------------------------

    private String hq() {
        return TestTokens.bearer(tokens, "fc1-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ,
            QbPermissions.ADMIN_PUBLICATION_READ);
    }

    private String operatorSession(String userId, String operatorId) {
        Actor actor = new Actor(userId, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operatorId));
        return "Bearer " + tokens.issue(actor).token();
    }
}
