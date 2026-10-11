package com.jabiz.quizbuks.identity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SponsorApprovalsTest {

    private static final UUID AVATAR = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
    private static final Instant TERMS = Instant.parse("2026-10-01T09:00:00Z");

    private static Map<String, Object> content() {
        return SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", AVATAR, TERMS);
    }

    @Test
    void theOrderOfTheKeysDoesNotChangeTheHash() {
        Map<String, Object> reversed = new LinkedHashMap<>();
        List.copyOf(content().keySet()).reversed().forEach(key -> reversed.put(key, content().get(key)));
        assertThat(SponsorApprovals.hash(reversed)).isEqualTo(SponsorApprovals.hash(content()));
    }

    @Test
    void theSameTimeHashesTheSameWhateverItsOffset() {
        Instant stored = OffsetDateTime.of(2026, 10, 1, 18, 0, 0, 0, ZoneOffset.ofHours(9)).toInstant();
        assertThat(SponsorApprovals.hash(SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", AVATAR,
            stored))).isEqualTo(SponsorApprovals.hash(content()));
    }

    @Test
    void anyChangeOfTheContentChangesTheHash() {
        String hash = SponsorApprovals.hash(content());
        assertThat(List.of(
            SponsorApprovals.content("Sarah", "Acme", "We make **quizzes**.", AVATAR, TERMS),
            SponsorApprovals.content("Sara", "Acme Ltd", "We make **quizzes**.", AVATAR, TERMS),
            SponsorApprovals.content("Sara", "Acme", "We make quizzes.", AVATAR, TERMS),
            SponsorApprovals.content("Sara", "Acme", null, AVATAR, TERMS),
            SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", null, TERMS),
            SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", UUID.randomUUID(), TERMS),
            SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", AVATAR, TERMS.plusSeconds(1))))
            .allSatisfy(changed -> assertThat(SponsorApprovals.hash(changed)).isNotEqualTo(hash));
    }

    @Test
    void theOnlyFactIsTheReviewSwitch() {
        assertThat(SponsorApprovals.facts(true)).containsExactly(Map.entry("autoApprove", true));
        assertThat(SponsorApprovals.facts(false)).containsExactly(Map.entry("autoApprove", false));
    }
}
