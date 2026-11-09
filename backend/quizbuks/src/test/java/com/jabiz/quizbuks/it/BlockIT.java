package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.AssetProcesses;
import com.jabiz.quizbuks.block.BlockCodes;
import com.jabiz.quizbuks.block.BlockProcesses;
import com.jabiz.quizbuks.block.BlockRules;
import com.jabiz.quizbuks.publishing.PublicationSharedVideos;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses;
import com.jabiz.quizbuks.topic.TopicProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S8 (docs/quizbuks/plans/impl/F-S8.md): a taker blocks a publication, a quiz, a sponsor or a video provider from a
 * card; the home (its shelf of shorts, the search and F-S13's pushes), the page, the inbox's notices and the start all
 * apply the one definition {@link BlockRules#blocks} (the comparison test); unblocking restores; sponsors see a
 * rounded count only, headquarters aggregates only; the limit and the processes' refusals.
 */
class BlockIT extends InboxItSupport {

    /** A site mainland China plays (F-S11): the tests' unknown address counts as mainland China. */
    static final String VIDEO = "https://www.bilibili.com/video/BV1GJ411x7h7";

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    // ---- the world: a provider's shared video, two sponsors, four publications --------------------------------------

    /** The cards of the comparison: what each publication's card names. */
    record World(String provider, String s1, String s2, String quiz1, Map<String, BlockRules.Target> cards,
        Map<String, String> notices) {

        String id(String name) {
            return cards.get(name).publicationId();
        }
    }

    String topicAdmin() {
        return TestTokens.bearer(tokens, "fs8-topic-admin", QbPermissions.ADMIN_TOPIC_READ,
            QbPermissions.ADMIN_TOPIC_WRITE);
    }

    String word(String code) {
        if (query("SELECT 1 FROM qb_topic WHERE topic_id = ?", code).isEmpty()) {
            run(TopicProcesses.ADD, topicAdmin(), body("topicId", code, "nameZh", code + " 中", "nameJa", code + " 日",
                "nameEn", code + " en"));
        }
        return code;
    }

    /** A vertical video link of the provider's, shared and approved; the shared video's key. */
    String approvedVideo(String provider) {
        String token = sponsorToken(provider);
        String assetId = (String) run(AssetProcesses.SAVE, token, body("kind", "VIDEO_LINK", "name", "Cats",
            "language", "en", "url", VIDEO, "durationSec", 95)).get("assetId");
        Map<String, Object> shared = run(SharedVideoProcesses.SHARE, token, body("assetId", assetId,
            "titleZh", "猫 " + assetId.substring(0, 6), "titleJa", "猫の動画", "titleEn", "Cats " + assetId.substring(0, 6),
            "topics", List.of(word("fs8-pets")), "vertical", true));
        decide((String) shared.get("approvalRequestId"), "APPROVE", null);
        return (String) shared.get("sharedVideoId");
    }

    /** A publication of the version, aimed at JP and ASIA, live for a week, picking the shared video when given. */
    String live(String sponsor, String versionId, String name, String sharedVideoId) {
        String token = sponsorToken(sponsor);
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        if (sharedVideoId != null) {
            run(PublicationSharedVideos.SET, token, body("publicationId", publicationId, "sharedVideoId",
                sharedVideoId));
        }
        assertThat(submit(token, publicationId).get("status")).isEqualTo("LIVE");
        return publicationId;
    }

    static BlockRules.Target target(String publicationId) {
        Map<String, Object> card = query("SELECT * FROM qb_publication_card WHERE publication_id = ?::uuid",
            publicationId).getFirst();
        return new BlockRules.Target(publicationId, (String) card.get("quiz_id"), (String) card.get("publisher_id"),
            (String) card.get("video_provider_id"));
    }

    /**
     * Sponsor 1's quiz 1 is on A (its own videos) and B (the provider's shared video); sponsor 2's C uses the shared
     * video too and D its own. Each has a notice of its sponsor's in the inbox of takers in JP.
     */
    World world(String prefix) {
        String provider = newSponsor(unique("s-" + prefix + "-provider"));
        String s1 = newSponsor(unique("s-" + prefix + "-one"));
        String s2 = newSponsor(unique("s-" + prefix + "-two"));
        credit(s1, 100_000);
        credit(s2, 100_000);
        String video = approvedVideo(provider);
        String version1 = version(sponsorToken(s1), prefix + " quiz 1");
        Map<String, BlockRules.Target> cards = new LinkedHashMap<>();
        cards.put("A", target(live(s1, version1, prefix + " A", null)));
        cards.put("B", target(live(s1, version1, prefix + " B", video)));
        cards.put("C", target(live(s2, version(sponsorToken(s2), prefix + " quiz 2"), prefix + " C", video)));
        cards.put("D", target(live(s2, version(sponsorToken(s2), prefix + " quiz 3"), prefix + " D", null)));
        Map<String, String> notices = new LinkedHashMap<>();
        cards.forEach((name, card) -> notices.put(name, notice(card.sponsorId(), card.publicationId(),
            List.of("JP"))));
        assertThat(cards.get("B").providerId()).isEqualTo(provider);
        assertThat(cards.get("A").quizId()).isEqualTo(cards.get("B").quizId());
        return new World(provider, s1, s2, cards.get("A").quizId(), cards, notices);
    }

    Map<String, Object> block(String token, String publicationId, String kind) {
        return run(BlockProcesses.ADD, token, body("publicationId", publicationId, "kind", kind));
    }

    void unblock(String token, Object blockId) {
        run(BlockProcesses.REMOVE, token, body("blockId", blockId));
    }

    List<Object> listed(String token, String q, boolean shorts) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", q);
        if (shorts) {
            params.put("shorts", true);
        }
        return feed(token, "JP", params).stream().map(row -> row.get("publicationId")).toList();
    }

    /** F-S13: the vertical mode's pushes (the feed's template with {@code push}) among the search's. */
    List<Object> pushed(String token, String q) {
        return feed(token, "JP", Map.of("q", q, "push", true)).stream().map(row -> row.get("publicationId")).toList();
    }

    Map<String, Object> page(String token, String publicationId) {
        List<Map<String, Object>> rows = items("qb.app.publication", token, Map.of("params", Map.of("publicationId",
            publicationId, "at", clock.instant().toString(), "country", "JP")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    // ---- the comparison ---------------------------------------------------------------------------------------------

    /**
     * The comparison test: for takers with various blocks, the home, its shelf of shorts, the search, the page, the
     * inbox's notices with the unread count, and the start agree with {@link BlockRules#blocks} for every card.
     */
    @Test
    void theHomeThePageTheInboxAndTheStartApplyTheOneDefinition() {
        World w = world("fs8-cmp");
        String a = w.id("A");
        String b = w.id("B");
        String c = w.id("C");
        String d = w.id("D");
        // Each combination: the blocks to make, as (the card made from, the kind).
        List<List<String[]>> combinations = List.of(
            List.of(),
            List.<String[]>of(new String[] {a, BlockRules.PUBLICATION}),
            List.<String[]>of(new String[] {b, BlockRules.QUIZ}),
            List.<String[]>of(new String[] {a, BlockRules.SPONSOR}),
            List.<String[]>of(new String[] {c, BlockRules.PROVIDER}),
            List.of(new String[] {d, BlockRules.PUBLICATION}, new String[] {b, BlockRules.PROVIDER}),
            List.of(new String[] {c, BlockRules.SPONSOR}, new String[] {a, BlockRules.QUIZ}));
        Map<String, Object> baseline = unread(taker(newTaker(unique("t-fs8-cmp-base"), "JP")));
        int index = 0;
        for (List<String[]> combination : combinations) {
            String token = taker(newTaker(unique("t-fs8-cmp-" + index++), "JP"));
            List<BlockRules.Block> blocks = new ArrayList<>();
            for (String[] made : combination) {
                Map<String, Object> output = block(token, made[0], made[1]);
                blocks.add(new BlockRules.Block((String) output.get("kind"), (String) output.get("objectId")));
                assertThat(output).containsEntry("created", true);
            }
            String label = blocks.toString();
            Set<String> blocked = w.cards().values().stream().filter(card -> BlockRules.blocks(blocks, card))
                .map(BlockRules.Target::publicationId).collect(Collectors.toSet());
            List<String> shown = w.cards().values().stream().map(BlockRules.Target::publicationId)
                .filter(id -> !blocked.contains(id)).toList();
            // The home and its search.
            assertThat(listed(token, "fs8-cmp", false)).as("home " + label).containsExactlyInAnyOrderElementsOf(shown);
            // The shelf of shorts: the shared video is vertical (B and C).
            assertThat(listed(token, "fs8-cmp", true)).as("shorts " + label)
                .containsExactlyInAnyOrderElementsOf(shown.stream().filter(id -> id.equals(b) || id.equals(c))
                    .toList());
            // F-S13: the pushes of the vertical mode, the provider's vertical shared video on another's publication (B
            // and C): a hard block, nothing paid gets past it.
            assertThat(pushed(token, "fs8-cmp")).as("pushes " + label)
                .containsExactlyInAnyOrderElementsOf(shown.stream().filter(id -> id.equals(b) || id.equals(c))
                    .toList());
            // The page: found, saying blocked and not offering the start exactly when blocked.
            for (BlockRules.Target card : w.cards().values()) {
                Map<String, Object> row = page(token, card.publicationId());
                boolean isBlocked = blocked.contains(card.publicationId());
                assertThat(row).as("page " + card.publicationId() + " " + label).isNotNull();
                assertThat(row.get("blocked")).as("blocked " + card.publicationId() + " " + label).isEqualTo(isBlocked);
                assertThat(row.get("canStart")).as("canStart " + card.publicationId() + " " + label)
                    .isEqualTo(!isBlocked);
                if (isBlocked) {
                    assertThat(String.valueOf(row.get("blocks"))).contains("blockId");
                } else {
                    assertThat(row.get("blocks")).isNull();
                }
            }
            // The inbox's notices of the four publications, and the unread count less those hidden.
            Set<Object> notices = Set.copyOf(w.notices().values());
            List<Object> inNotices = itemIds(inbox(token)).stream().filter(notices::contains).toList();
            List<Object> expectedNotices = w.notices().entrySet().stream()
                .filter(e -> !blocked.contains(w.id(e.getKey()))).map(e -> (Object) e.getValue()).toList();
            assertThat(inNotices).as("inbox " + label).containsExactlyInAnyOrderElementsOf(expectedNotices);
            assertThat(count(unread(token), "broadcasts")).as("unread " + label)
                .isEqualTo(count(baseline, "broadcasts") - (w.notices().size() - expectedNotices.size()));
            // The start: refused exactly when blocked.
            for (BlockRules.Target card : w.cards().values()) {
                if (blocked.contains(card.publicationId())) {
                    assertThat(codes(refused("QB_ATTEMPT_START", token, body("publicationId", card.publicationId()),
                        422))).as("start " + card.publicationId() + " " + label).containsExactly(BlockCodes.BLOCKED);
                } else {
                    assertThat(start(token, card.publicationId())).as("start " + label).containsKey("attemptId");
                }
            }
        }
    }

    /** A quiz's block covers its publications to come; unblocking restores them all. */
    @Test
    void aBlockedQuizCoversItsLaterPublicationsAndUnblockingRestores() {
        String sponsor = newSponsor(unique("s-fs8-later"));
        credit(sponsor, 100_000);
        String versionId = version(sponsorToken(sponsor), "fs8-later quiz");
        String first = live(sponsor, versionId, "fs8-later first", null);
        String token = taker(newTaker(unique("t-fs8-later"), "JP"));
        Map<String, Object> quiz = block(token, first, BlockRules.QUIZ);
        assertThat(listed(token, "fs8-later", false)).isEmpty();
        // A later publication of the same quiz is blocked from the start.
        String later = live(sponsor, versionId, "fs8-later second", null);
        assertThat(listed(token, "fs8-later", false)).isEmpty();
        assertThat(page(token, later)).containsEntry("blocked", true).containsEntry("canStart", false);
        // Blocking it again answers the row there is.
        Map<String, Object> again = block(token, later, BlockRules.QUIZ);
        assertThat(again).containsEntry("blockId", quiz.get("blockId")).containsEntry("created", false);
        unblock(token, quiz.get("blockId"));
        assertThat(listed(token, "fs8-later", false)).containsExactlyInAnyOrder(first, later);
        assertThat(page(token, later)).containsEntry("blocked", false).containsEntry("canStart", true);
        assertThat(query("SELECT action FROM sys_audit_record WHERE entity_type = 'QbBlock' AND entity_id = ? "
            + "ORDER BY record_no", quiz.get("blockId"))).extracting(row -> row.get("action"))
            .containsExactly("INSERT", "DELETE");
    }

    /** Blocking the provider hides every publication using its video, whoever's; unblocking brings them back. */
    @Test
    void aBlockedProviderHidesEveryPublicationUsingItsVideo() {
        World w = world("fs8-prov");
        String token = taker(newTaker(unique("t-fs8-prov"), "JP"));
        Map<String, Object> provider = block(token, w.id("C"), BlockRules.PROVIDER);
        assertThat(provider).containsEntry("objectId", w.provider());
        assertThat(listed(token, "fs8-prov", false)).containsExactlyInAnyOrder(w.id("A"), w.id("D"));
        assertThat(listed(token, "fs8-prov", true)).isEmpty();
        assertThat(rows(token)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", BlockRules.PROVIDER).containsEntry("objectId", w.provider());
            assertThat(row.get("name")).isNotNull();
        });
        unblock(token, provider.get("blockId"));
        assertThat(listed(token, "fs8-prov", false)).hasSize(4);
        assertThat(rows(token)).isEmpty();
    }

    List<Map<String, Object>> rows(String token) {
        return items("qb.app.blocks", token, Map.of("limit", 100));
    }

    /** The processes' refusals: one's own, a card without a provider, another's block, a card there is not. */
    @Test
    void theProcessesRefuseWhatCannotBeBlocked() {
        World w = world("fs8-refuse");
        // A sponsor answering others' quizzes does not block their own publication, nor themselves.
        String own = sponsorToken(w.s1());
        assertThat(codes(refused(BlockProcesses.ADD, own, body("publicationId", w.id("A"), "kind",
            BlockRules.PUBLICATION), 422))).containsExactly(BlockCodes.OWN);
        // Nor the provider their own video, on another sponsor's card.
        assertThat(codes(refused(BlockProcesses.ADD, sponsorToken(w.provider()), body("publicationId", w.id("C"),
            "kind", BlockRules.PROVIDER), 422))).containsExactly(BlockCodes.OWN);
        String token = taker(newTaker(unique("t-fs8-refuse"), "JP"));
        assertThat(codes(refused(BlockProcesses.ADD, token, body("publicationId", w.id("A"), "kind",
            BlockRules.PROVIDER), 422))).containsExactly(BlockCodes.NO_PROVIDER);
        refused(BlockProcesses.ADD, token, body("publicationId", java.util.UUID.randomUUID(), "kind",
            BlockRules.PUBLICATION), 404);
        refused(BlockProcesses.ADD, token, body("publicationId", w.id("A"), "kind", "TOPIC"), 400);
        Map<String, Object> mine = block(token, w.id("A"), BlockRules.SPONSOR);
        String other = taker(newTaker(unique("t-fs8-refuse-other"), "JP"));
        refused(BlockProcesses.REMOVE, other, body("blockId", mine.get("blockId")), 404);
        refused(BlockProcesses.REMOVE, token, body("blockId", java.util.UUID.randomUUID().toString()), 404);
        // Another taker sees nothing of it.
        assertThat(rows(other)).isEmpty();
        assertThat(listed(other, "fs8-refuse", false)).hasSize(4);
    }

    /** At most qb.block.max-per-user blocks (an ordinary parameter). */
    @Test
    void theNumberOfBlocksIsLimited() {
        World w = world("fs8-limit");
        String token = taker(newTaker(unique("t-fs8-limit"), "JP"));
        run("PARAM_SET", admin(), body("key", QbParams.BLOCK_MAX_PER_USER, "value", "2"));
        try {
            block(token, w.id("A"), BlockRules.PUBLICATION);
            block(token, w.id("B"), BlockRules.PUBLICATION);
            // Blocking again what is blocked is no new row.
            block(token, w.id("B"), BlockRules.PUBLICATION);
            assertThat(violations(refused(BlockProcesses.ADD, token, body("publicationId", w.id("C"), "kind",
                BlockRules.PUBLICATION), 422))).singleElement().satisfies(v -> {
                    assertThat(v).containsEntry("ruleCode", BlockCodes.LIMIT);
                    assertThat(String.valueOf(v.get("message"))).contains("2");
                });
        } finally {
            run("PARAM_SET", admin(), body("key", QbParams.BLOCK_MAX_PER_USER, "value", "1000"));
        }
        block(token, w.id("C"), BlockRules.PUBLICATION);
        assertThat(rows(token)).hasSize(3);
    }

    /**
     * A sponsor sees how many takers block it (it, its quizzes and publications, it as a provider), each once, from the
     * minimum group up and rounded down; headquarters sees the same in its list and the counts by kind, never whose.
     */
    @Test
    void sponsorsSeeARoundedCountAndHeadquartersOnlyAggregates() {
        World w = world("fs8-count");
        String console = sponsorToken(w.s1());
        assertThat(items("qb.sponsor.blockers", console, Map.of()).getFirst()).containsEntry("blockers", null)
            .containsEntry("minGroup", 20);
        run("PARAM_SET", admin(), body("key", QbParams.BLOCK_COUNT_MIN_GROUP, "value", "5"));
        try {
            List<String> takers = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                takers.add(taker(newTaker(unique("t-fs8-count-" + i), "JP")));
            }
            // Four takers: fewer than the group.
            String[][] firstFour = {{w.id("A"), BlockRules.PUBLICATION}, {w.id("B"), BlockRules.QUIZ},
                {w.id("A"), BlockRules.SPONSOR}, {w.id("B"), BlockRules.PUBLICATION}};
            for (int i = 0; i < 4; i++) {
                block(takers.get(i), firstFour[i][0], firstFour[i][1]);
            }
            // The first taker blocks the sponsor too: still one taker.
            block(takers.getFirst(), w.id("A"), BlockRules.SPONSOR);
            assertThat(items("qb.sponsor.blockers", console, Map.of()).getFirst()).containsEntry("blockers", null)
                .containsEntry("minGroup", 5);
            // Blocking the provider or sponsor 2 is not sponsor 1's.
            block(takers.get(4), w.id("C"), BlockRules.PROVIDER);
            block(takers.get(5), w.id("D"), BlockRules.SPONSOR);
            assertThat(items("qb.sponsor.blockers", console, Map.of()).getFirst()).containsEntry("blockers", null);
            // Seven of sponsor 1: shown as the group (5), not rounded to 0.
            for (int i = 6; i < 9; i++) {
                block(takers.get(i), w.id("A"), BlockRules.QUIZ);
            }
            assertThat(items("qb.sponsor.blockers", console, Map.of()).getFirst()).containsEntry("blockers", 5);
            for (int i = 9; i < 12; i++) {
                block(takers.get(i), w.id("B"), BlockRules.SPONSOR);
            }
            // Ten: shown as 10.
            assertThat(items("qb.sponsor.blockers", console, Map.of()).getFirst()).containsEntry("blockers", 10);
            List<Map<String, Object>> sponsors = items("qb.admin.blocked-sponsors", blockAdmin(), Map.of("limit", 200));
            assertThat(sponsors).filteredOn(row -> w.s1().equals(row.get("sponsorId"))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("blockers", 10));
            // The provider (one taker) and sponsor 2 (one taker) are below the group: not listed.
            assertThat(sponsors).extracting(row -> row.get("sponsorId")).doesNotContain(w.provider(), w.s2());
        } finally {
            run("PARAM_SET", admin(), body("key", QbParams.BLOCK_COUNT_MIN_GROUP, "value", "20"));
        }
        assertThat(items("qb.sponsor.blockers", console, Map.of()).getFirst()).containsEntry("blockers", null);
        assertThat(items("qb.admin.blocked-sponsors", blockAdmin(), Map.of("limit", 200)))
            .extracting(row -> row.get("sponsorId")).doesNotContain(w.s1());
        // The counts by kind are the table's.
        Map<Object, Map<String, Object>> byKind = items("qb.admin.blocks", blockAdmin(), Map.of()).stream()
            .collect(Collectors.toMap(row -> row.get("kind"), row -> row));
        assertThat(byKind.keySet()).containsExactlyInAnyOrderElementsOf(BlockRules.KINDS);
        for (String kind : BlockRules.KINDS) {
            Map<String, Object> expected = query("SELECT count(*) AS blocks, count(DISTINCT user_id) AS blockers "
                + "FROM qb_block WHERE kind = ?", kind).getFirst();
            assertThat(((Number) byKind.get(kind).get("blocks")).longValue()).as(kind)
                .isEqualTo(((Number) expected.get("blocks")).longValue());
            assertThat(((Number) byKind.get(kind).get("blockers")).longValue()).as(kind)
                .isEqualTo(((Number) expected.get("blockers")).longValue());
        }
        // Only headquarters' permission reads the figures; nobody reads whose blocks through the datasets.
        refusedQuery("qb.admin.blocks", console);
        refusedQuery("qb.admin.blocked-sponsors", console);
        queryDataset(com.jabiz.quizbuks.block.QbBlocks.DEFAULT_DATASET, console).expectStatus().isForbidden();
        queryDataset(com.jabiz.quizbuks.block.QbBlocks.DEFAULT_DATASET, blockAdmin()).expectStatus().isForbidden();
    }

    String blockAdmin() {
        return TestTokens.bearer(tokens, "fs8-block-admin", QbPermissions.ADMIN_BLOCK_READ);
    }

    void refusedQuery(String id, String token) {
        client.post().uri("/api/queries/" + id).header(org.springframework.http.HttpHeaders.AUTHORIZATION, token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isForbidden();
    }
}
