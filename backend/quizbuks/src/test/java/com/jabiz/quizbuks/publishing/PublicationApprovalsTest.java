package com.jabiz.quizbuks.publishing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class PublicationApprovalsTest {

    private static final UUID VERSION = UUID.fromString("0190a000-0000-7000-8000-0000000000aa");
    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-08T00:00:00Z");

    private static PublicationReadiness.Draft draft() {
        return new PublicationReadiness.Draft(VERSION, "Autumn", "GLOBAL", START, END, 300, 3,
            BigDecimal.valueOf(10_000), 50);
    }

    private static List<TierCoverage.Tier> tiers() {
        return List.of(new TierCoverage.Tier(0, 2, BigDecimal.ZERO), new TierCoverage.Tier(3, 5, BigDecimal.TEN));
    }

    private static String hash(PublicationReadiness.Draft draft, String versionHash, List<TierCoverage.Tier> tiers) {
        return PublicationApprovals.hash(PublicationApprovals.content(draft, versionHash, tiers));
    }

    @Test
    void theHashDoesNotDependOnKeyOrderScaleOrTierOrder() {
        String hash = hash(draft(), "abc", tiers());
        Map<String, Object> content = PublicationApprovals.content(draft(), "abc", tiers());
        List<String> keys = new ArrayList<>(content.keySet());
        Collections.reverse(keys);
        Map<String, Object> reversed = new LinkedHashMap<>();
        keys.forEach(key -> reversed.put(key, content.get(key)));
        assertThat(PublicationApprovals.hash(reversed)).isEqualTo(hash);
        // 10 000 and 10 000.00 Kudos are the same budget; tiers given in another order are the same tiers.
        PublicationReadiness.Draft scaled = new PublicationReadiness.Draft(VERSION, "Autumn", "GLOBAL", START, END,
            300, 3, new BigDecimal("10000.00"), 50);
        assertThat(hash(scaled, "abc", tiers().reversed())).isEqualTo(hash);
        // A time is the instant it names, in whatever zone it was read.
        Map<String, Object> zoned = new LinkedHashMap<>(content);
        zoned.put("startAt", OffsetDateTime.ofInstant(START, ZoneOffset.ofHours(9)));
        assertThat(PublicationApprovals.hash(zoned)).isEqualTo(hash);
    }

    @Test
    void anyChangeOfThePolicyOrTheVersionChangesTheHash() {
        String hash = hash(draft(), "abc", tiers());
        List<UnaryOperator<PublicationReadiness.Draft>> changes = List.of(
            d -> new PublicationReadiness.Draft(UUID.randomUUID(), d.name(), d.regions(), d.startAt(), d.endAt(),
                d.timeLimitSec(), d.passScore(), d.budgetCap(), d.maxWinners()),
            d -> new PublicationReadiness.Draft(d.versionId(), "Winter", d.regions(), d.startAt(), d.endAt(),
                d.timeLimitSec(), d.passScore(), d.budgetCap(), d.maxWinners()),
            d -> new PublicationReadiness.Draft(d.versionId(), d.name(), "JP", d.startAt(), d.endAt(),
                d.timeLimitSec(), d.passScore(), d.budgetCap(), d.maxWinners()),
            d -> new PublicationReadiness.Draft(d.versionId(), d.name(), d.regions(), d.startAt().plusSeconds(1),
                d.endAt(), d.timeLimitSec(), d.passScore(), d.budgetCap(), d.maxWinners()),
            d -> new PublicationReadiness.Draft(d.versionId(), d.name(), d.regions(), d.startAt(),
                d.endAt().plusSeconds(1), d.timeLimitSec(), d.passScore(), d.budgetCap(), d.maxWinners()),
            d -> new PublicationReadiness.Draft(d.versionId(), d.name(), d.regions(), d.startAt(), d.endAt(), 301,
                d.passScore(), d.budgetCap(), d.maxWinners()),
            d -> new PublicationReadiness.Draft(d.versionId(), d.name(), d.regions(), d.startAt(), d.endAt(),
                d.timeLimitSec(), 4, d.budgetCap(), d.maxWinners()),
            d -> new PublicationReadiness.Draft(d.versionId(), d.name(), d.regions(), d.startAt(), d.endAt(),
                d.timeLimitSec(), d.passScore(), null, d.maxWinners()),
            d -> new PublicationReadiness.Draft(d.versionId(), d.name(), d.regions(), d.startAt(), d.endAt(),
                d.timeLimitSec(), d.passScore(), d.budgetCap(), 51));
        for (UnaryOperator<PublicationReadiness.Draft> change : changes) {
            assertThat(hash(change.apply(draft()), "abc", tiers())).isNotEqualTo(hash);
        }
        assertThat(hash(draft(), "abd", tiers())).isNotEqualTo(hash);
        assertThat(hash(draft(), "abc", List.of(new TierCoverage.Tier(0, 2, BigDecimal.ZERO),
            new TierCoverage.Tier(3, 5, BigDecimal.valueOf(11))))).isNotEqualTo(hash);
        assertThat(hash(draft(), "abc", List.of(new TierCoverage.Tier(0, 3, BigDecimal.ZERO),
            new TierCoverage.Tier(4, 5, BigDecimal.TEN)))).isNotEqualTo(hash);
    }

    @Test
    void theFactsAreThoseOfTheSubject() {
        assertThat(PublicationApprovals.facts(true, BigDecimal.TEN, null, "sponsor-a", "GLOBAL", false))
            .containsEntry("autoApprove", true).containsEntry("maxReward", BigDecimal.TEN)
            .containsEntry("publisherKind", "SPONSOR").containsEntry("publisherId", "sponsor-a")
            .containsEntry("regions", "GLOBAL").containsKey("maxSpend");
        assertThat(QbPublications.approvalSubject().normalizeFacts(PublicationApprovals.facts(false, BigDecimal.TEN,
            BigDecimal.ONE, "s", "JP", true))).containsEntry("autoApprove", false).containsEntry("takenDown", true);
    }
}
