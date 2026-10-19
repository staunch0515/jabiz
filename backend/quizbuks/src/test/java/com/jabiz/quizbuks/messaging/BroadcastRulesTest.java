package com.jabiz.quizbuks.messaging;

import com.jabiz.entity.Violation;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules of sending (Q7-9, Q7-10): every problem at once, sponsors' limits, links, regions and times. */
class BroadcastRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final BroadcastRules.Limits LIMITS = BroadcastRules.Limits.defaults();
    private static final BroadcastRules.Publication LIVE = new BroadcastRules.Publication("p1", "s1", "LIVE",
        "JP,ASIA", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(7)));

    private static BroadcastRules.Draft sponsor(String title, String body, String regions, Instant scheduledAt) {
        return new BroadcastRules.Draft(QbInbox.SENDER_SPONSOR, "APP", "PROMO", Map.of("en", title),
            Map.of("en", body), "REGIONS", regions, 0, "p1", scheduledAt, null);
    }

    private static BroadcastRules.Draft platform(Map<String, ?> title, String targetMode, String regions,
        int targets, Instant expiresAt) {
        return new BroadcastRules.Draft(QbInbox.SENDER_PLATFORM, "APP", "SYSTEM", title, Map.of("en", "Text"),
            targetMode, regions, targets, null, null, expiresAt);
    }

    private static List<String> codes(List<Violation> violations) {
        return violations.stream().map(Violation::ruleCode).toList();
    }

    @Test
    void aGoodNoticeHasNoProblemAndExpiresWithItsPublication() {
        BroadcastRules.Draft draft = sponsor("New quiz", "Play it", "JP", null);
        assertThat(BroadcastRules.sendProblems(draft, NOW, LIVE, List.of(), LIMITS)).isEmpty();
        assertThat(BroadcastRules.expiresAt(draft, NOW, LIVE, LIMITS)).isEqualTo(LIVE.endAt());
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        BroadcastRules.Draft draft = new BroadcastRules.Draft(QbInbox.SENDER_SPONSOR, "APP", "PROMO", Map.of(),
            Map.of("en", " "), "REGIONS", "US", 0, "p1", NOW.plus(Duration.ofDays(8)), null);
        assertThat(codes(BroadcastRules.sendProblems(draft, NOW, LIVE, List.of(), LIMITS))).containsExactlyInAnyOrder(
            "QB_BROADCAST_TITLE_REQUIRED", "QB_BROADCAST_BODY_REQUIRED", "QB_BROADCAST_AFTER_END",
            "QB_BROADCAST_REGIONS_OUTSIDE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"see https://evil.example", "www.quizbuks-prizes.com", "mail me: a.b@example.co.jp",
        "visit prizes.xyz now", "ftp://files", "bit.ly/abc", "QUIZBUKS.COM"})
    void addressesAreRefused(String text) {
        assertThat(codes(BroadcastRules.sendProblems(sponsor("Hello", text, "JP", null), NOW, LIVE, List.of(),
            LIMITS))).containsExactly("QB_BROADCAST_LINK");
    }

    /** The review's probes (M3): obfuscated, international and contact forms of an address. */
    @ParameterizedTest
    @ValueSource(strings = {"hxxp://evil.tld/x", "hxxps[:]//evil[.]com", "evil[.]com", "evil(.)com", "evil . com",
        "evil dot com", "evil。com", "evil．com", "ｅｖｉｌ.com", "ev​il.c​om", "evil.c​om",
        "xn--80ak6aa92e.com", "пример.com", "例え.jp", "claim at evil.ai", "evil.live", "evil.page", "bit.ly/abc",
        "[click](javascript:x)", "<https://x>", "LINE ID: quizfree", "follow @quizfree", "Call 0120-123-456",
        "w w w.evil.com", "WWW.evil.vip", "evil.com.", "t.me/abc", "evil.co.jp", "Visit evil . c o m",
        "１２３.45.67.89", "http://1.2.3.4", "1.2.3.4/claim", "evil­site.com", "evil⁠.com"})
    void obfuscatedAddressesAndContactsAreRefused(String text) {
        assertThat(BroadcastRules.hasAddress(text)).as(text).isTrue();
        assertThat(codes(BroadcastRules.sendProblems(sponsor("Hello", text, "JP", null), NOW, LIVE, List.of(),
            LIMITS))).containsExactly("QB_BROADCAST_LINK");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Win 100 Kudos today!", "e.g. the capital", "クイズに挑戦しよう", "3.14 is pi",
        "Ends at 9.30", "新しいクイズ：日本の首都", "Ends 2026-10-18 10:00 JST", "Top 10. Then more.",
        "日本の首都。東京です。", "Meet @ 5pm", "Score 5 of 5 (100%)", "Q&A: i.e. the answer"})
    void plainTextIsFine(String text) {
        assertThat(BroadcastRules.sendProblems(sponsor("Hello", text, "JP", null), NOW, LIVE, List.of(), LIMITS))
            .isEmpty();
    }

    @Test
    void aNoticeNamesAScheduledOrLiveOwnPublication() {
        assertThat(codes(BroadcastRules.sendProblems(sponsor("A", "B", "JP", null), NOW, null, List.of(), LIMITS)))
            .containsExactly("QB_BROADCAST_PUBLICATION_NOT_ON");
        BroadcastRules.Publication paused = new BroadcastRules.Publication("p1", "s1", "PAUSED", "JP", NOW,
            NOW.plus(Duration.ofDays(1)));
        assertThat(codes(BroadcastRules.sendProblems(sponsor("A", "B", "JP", null), NOW, paused, List.of(),
            LIMITS))).containsExactly("QB_BROADCAST_PUBLICATION_NOT_ON");
        BroadcastRules.Draft none = new BroadcastRules.Draft(QbInbox.SENDER_SPONSOR, "APP", "PROMO",
            Map.of("en", "A"), Map.of("en", "B"), "REGIONS", "JP", 0, null, null, null);
        assertThat(codes(BroadcastRules.sendProblems(none, NOW, null, List.of(), LIMITS)))
            .containsExactly("QB_BROADCAST_PUBLICATION_REQUIRED");
    }

    @Test
    void regionsAreAmongThePublicationsOrAnyWhenItIsAimedAtAll() {
        assertThat(BroadcastRules.within(List.of("JP"), "JP,ASIA")).isTrue();
        assertThat(BroadcastRules.within(List.of("JP", "ASIA"), "JP,ASIA")).isTrue();
        assertThat(BroadcastRules.within(List.of("US"), "JP,ASIA")).isFalse();
        assertThat(BroadcastRules.within(List.of("GLOBAL"), "JP,ASIA")).isFalse();
        assertThat(BroadcastRules.within(List.of("US", "EUROPE"), "GLOBAL")).isTrue();
    }

    @Property
    void noTwoNoticesOfAPublicationGoOutWithinTheInterval(@ForAll @IntRange(min = 0, max = 100) int hoursApart,
        @ForAll @IntRange(min = 1, max = 48) int interval) {
        BroadcastRules.Limits limits = new BroadcastRules.Limits(10, Duration.ofHours(interval), Duration.ofDays(30));
        BroadcastRules.Other other = new BroadcastRules.Other("b0", "p1", "SENT", NOW);
        BroadcastRules.Draft draft = sponsor("A", "B", "JP", hoursApart == 0 ? null
            : NOW.plus(Duration.ofHours(hoursApart)));
        List<String> problems = codes(BroadcastRules.sendProblems(draft, NOW.minusSeconds(1), LIVE, List.of(other),
            limits));
        if (hoursApart < interval) {
            assertThat(problems).contains("QB_BROADCAST_TOO_SOON");
        } else {
            assertThat(problems).doesNotContain("QB_BROADCAST_TOO_SOON");
        }
    }

    @Test
    void aPublicationHasAtMostSoManyNoticesWithdrawnOnesCountingCancelledOnesNot() {
        List<BroadcastRules.Other> others = List.of(
            new BroadcastRules.Other("b1", "p1", "SENT", NOW.minus(Duration.ofDays(3))),
            new BroadcastRules.Other("b2", "p1", "WITHDRAWN", NOW.minus(Duration.ofDays(2))),
            new BroadcastRules.Other("b3", "p1", "CANCELLED", NOW.minus(Duration.ofDays(1))),
            new BroadcastRules.Other("b4", "p2", "SENT", NOW.minus(Duration.ofHours(1))));
        assertThat(BroadcastRules.sendProblems(sponsor("A", "B", "JP", null), NOW, LIVE, others, LIMITS)).isEmpty();
        List<BroadcastRules.Other> full = new java.util.ArrayList<>(others);
        full.add(new BroadcastRules.Other("b5", "p1", "SCHEDULED", NOW.plus(Duration.ofDays(2))));
        assertThat(codes(BroadcastRules.sendProblems(sponsor("A", "B", "JP", null), NOW, LIVE, full, LIMITS)))
            .containsExactly("QB_BROADCAST_TOO_MANY");
    }

    @Test
    void thePlatformsNeedEnglishTargetsAndAnExpiryAfterTheSending() {
        assertThat(BroadcastRules.sendProblems(platform(Map.of("en", "Hi"), "ALL", null, 0, null), NOW, null,
            List.of(), LIMITS)).isEmpty();
        assertThat(codes(BroadcastRules.sendProblems(platform(Map.of("ja", "やあ"), "REGIONS", null, 0,
            NOW.minusSeconds(1)), NOW, null, List.of(), LIMITS))).containsExactlyInAnyOrder(
            "QB_BROADCAST_TRANSLATION_MISSING", "QB_BROADCAST_REGIONS_REQUIRED", "QB_BROADCAST_EXPIRY");
        assertThat(codes(BroadcastRules.sendProblems(platform(Map.of("en", "Hi"), "USERS", null, 0, null), NOW,
            null, List.of(), LIMITS))).containsExactly("QB_BROADCAST_USERS_REQUIRED");
        assertThat(codes(BroadcastRules.sendProblems(platform(Map.of("en", "Hi"), "USERS", null, 1_001, null), NOW,
            null, List.of(), LIMITS))).containsExactly("QB_BROADCAST_TOO_MANY_USERS");
        BroadcastRules.Draft toSponsorsByRegion = new BroadcastRules.Draft(QbInbox.SENDER_PLATFORM, "SPONSOR",
            "SYSTEM", Map.of("en", "Hi"), Map.of("en", "Text"), "REGIONS", "JP", 0, null, null, null);
        assertThat(codes(BroadcastRules.sendProblems(toSponsorsByRegion, NOW, null, List.of(), LIMITS)))
            .containsExactly("QB_BROADCAST_TARGET_MODE");
        BroadcastRules.Draft draft = platform(Map.of("en", "Hi"), "ALL", null, 0, null);
        assertThat(BroadcastRules.expiresAt(draft, NOW, null, LIMITS)).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(codes(BroadcastRules.sendProblems(new BroadcastRules.Draft(QbInbox.SENDER_PLATFORM, "APP",
            "SYSTEM", Map.of("en", "Hi"), Map.of("en", "T"), "ALL", null, 0, null, NOW.plus(Duration.ofDays(400)),
            null), NOW, null, List.of(), LIMITS))).containsExactly("QB_BROADCAST_SCHEDULE_TOO_LATE");
    }

    @Test
    void sponsorsSeeNoCountBelowTheMinimumGroup() {
        assertThat(BroadcastRules.shown(4)).isNull();
        assertThat(BroadcastRules.shown(5)).isEqualTo(5L);
        assertThat(BroadcastRules.clean(Map.of("ja", " やあ ", "en", "", "zh", "你好")))
            .containsExactly(Map.entry("ja", "やあ"), Map.entry("zh", "你好"));
    }
}
