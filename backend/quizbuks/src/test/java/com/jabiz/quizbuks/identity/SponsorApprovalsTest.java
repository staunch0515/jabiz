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
        return SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", AVATAR, TERMS, 2);
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
            stored, 2))).isEqualTo(SponsorApprovals.hash(content()));
    }

    @Test
    void anyChangeOfTheContentChangesTheHash() {
        String hash = SponsorApprovals.hash(content());
        assertThat(List.of(
            SponsorApprovals.content("Sarah", "Acme", "We make **quizzes**.", AVATAR, TERMS, 2),
            SponsorApprovals.content("Sara", "Acme Ltd", "We make **quizzes**.", AVATAR, TERMS, 2),
            SponsorApprovals.content("Sara", "Acme", "We make quizzes.", AVATAR, TERMS, 2),
            SponsorApprovals.content("Sara", "Acme", null, AVATAR, TERMS, 2),
            SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", null, TERMS, 2),
            SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", UUID.randomUUID(), TERMS, 2),
            SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", AVATAR, TERMS.plusSeconds(1), 2),
            SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", AVATAR, TERMS, 3),
            SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", AVATAR, TERMS, null)))
            .allSatisfy(changed -> assertThat(SponsorApprovals.hash(changed)).isNotEqualTo(hash));
    }

    /** Without a version of the terms the content is that of before Q7b, so earlier requests stay bound (L2). */
    @Test
    void withoutAVersionOfTheTermsTheContentIsAsBefore() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("contactName", "Sara");
        before.put("company", "Acme");
        before.put("intro", "We make **quizzes**.");
        before.put("avatar", AVATAR.toString());
        before.put("termsAcceptedAt", TERMS.toString());
        Map<String, Object> content = SponsorApprovals.content("Sara", "Acme", "We make **quizzes**.", AVATAR, TERMS,
            null);
        assertThat(content).doesNotContainKey("termsVersionNo");
        assertThat(SponsorApprovals.hash(content)).isEqualTo(SponsorApprovals.hash(before));
    }

    @Test
    void theOnlyFactIsTheReviewSwitch() {
        assertThat(SponsorApprovals.facts(true)).containsExactly(Map.entry("autoApprove", true));
        assertThat(SponsorApprovals.facts(false)).containsExactly(Map.entry("autoApprove", false));
    }
}
