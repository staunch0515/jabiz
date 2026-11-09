package com.jabiz.quizbuks.block;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F-S8: the one definition of what a block covers, the limit and the rounded count. */
class BlockRulesTest {

    static final BlockRules.Target SHARED = new BlockRules.Target("p1", "q1", "s1", "v1");
    static final BlockRules.Target OWN_VIDEOS = new BlockRules.Target("p2", "q1", "s1", null);

    static BlockRules.Block block(String kind, String objectId) {
        return new BlockRules.Block(kind, objectId);
    }

    @Test
    void eachOfTheFourObjectsCoversTheCard() {
        assertThat(BlockRules.blocks(List.of(block(BlockRules.PUBLICATION, "p1")), SHARED)).isTrue();
        assertThat(BlockRules.blocks(List.of(block(BlockRules.QUIZ, "q1")), SHARED)).isTrue();
        assertThat(BlockRules.blocks(List.of(block(BlockRules.SPONSOR, "s1")), SHARED)).isTrue();
        assertThat(BlockRules.blocks(List.of(block(BlockRules.PROVIDER, "v1")), SHARED)).isTrue();
        // Another publication of the same quiz and sponsor: covered by those, not by the first's own block.
        assertThat(BlockRules.blocks(List.of(block(BlockRules.PUBLICATION, "p1")), OWN_VIDEOS)).isFalse();
        assertThat(BlockRules.blocks(List.of(block(BlockRules.QUIZ, "q1")), OWN_VIDEOS)).isTrue();
        // Without a shared video, no provider covers it.
        assertThat(BlockRules.blocks(List.of(block(BlockRules.PROVIDER, "v1")), OWN_VIDEOS)).isFalse();
        assertThat(BlockRules.blocks(List.of(), SHARED)).isFalse();
    }

    @Test
    void theKindMattersNotOnlyTheId() {
        // A sponsor who is also a provider: blocking it as a provider does not block its own publications.
        BlockRules.Target theirs = new BlockRules.Target("p3", "q3", "s9", null);
        assertThat(BlockRules.blocks(List.of(block(BlockRules.PROVIDER, "s9")), theirs)).isFalse();
        assertThat(BlockRules.blocks(List.of(block(BlockRules.SPONSOR, "v1")), SHARED)).isFalse();
        assertThat(BlockRules.matching(List.of(block(BlockRules.SPONSOR, "s1"), block(BlockRules.QUIZ, "q2"),
            block(BlockRules.PROVIDER, "v1")), SHARED))
            .containsExactly(block(BlockRules.SPONSOR, "s1"), block(BlockRules.PROVIDER, "v1"));
    }

    @Test
    void theObjectComesFromTheCard() {
        assertThat(BlockRules.objectOf(BlockRules.PUBLICATION, SHARED)).contains("p1");
        assertThat(BlockRules.objectOf(BlockRules.QUIZ, SHARED)).contains("q1");
        assertThat(BlockRules.objectOf(BlockRules.SPONSOR, SHARED)).contains("s1");
        assertThat(BlockRules.objectOf(BlockRules.PROVIDER, SHARED)).contains("v1");
        assertThat(BlockRules.objectOf(BlockRules.PROVIDER, OWN_VIDEOS)).isEmpty();
        assertThatThrownBy(() -> BlockRules.objectOf("TOPIC", SHARED)).isInstanceOf(IllegalArgumentException.class);
        assertThat(BlockRules.candidates(OWN_VIDEOS)).extracting(BlockRules.Block::kind)
            .containsExactly(BlockRules.PUBLICATION, BlockRules.QUIZ, BlockRules.SPONSOR);
    }

    @Test
    void oneDoesNotBlockOnesOwn() {
        assertThat(BlockRules.own("s1", SHARED)).isTrue();
        assertThat(BlockRules.own("v1", SHARED)).isTrue();
        assertThat(BlockRules.own("t1", SHARED)).isFalse();
        assertThat(BlockRules.own(null, OWN_VIDEOS)).isFalse();
    }

    @Test
    void theParametersAreReadWithinBounds() {
        assertThat(BlockRules.maxPerUser(null)).isEqualTo(1000);
        assertThat(BlockRules.maxPerUser(0)).isEqualTo(1);
        assertThat(BlockRules.maxPerUser(50)).isEqualTo(50);
        assertThat(BlockRules.maxPerUser(1_000_000)).isEqualTo(BlockRules.HARD_MAX_PER_USER);
        assertThat(BlockRules.minGroup(null)).isEqualTo(20);
        assertThat(BlockRules.minGroup(2)).isEqualTo(5);
        assertThat(BlockRules.minGroup(30)).isEqualTo(30);
    }

    @Test
    void aSponsorIsShownARoundedCountFromTheMinimumGroupUp() {
        assertThat(BlockRules.shownCount(0, 20)).isEmpty();
        assertThat(BlockRules.shownCount(19, 20)).isEmpty();
        assertThat(BlockRules.shownCount(20, 20)).isEqualTo(Optional.of(20L));
        assertThat(BlockRules.shownCount(29, 20)).isEqualTo(Optional.of(20L));
        assertThat(BlockRules.shownCount(30, 20)).isEqualTo(Optional.of(30L));
        assertThat(BlockRules.shownCount(1234, 20)).isEqualTo(Optional.of(1230L));
        assertThat(BlockRules.shownCount(7, 5)).isEqualTo(Optional.of(5L));
        assertThat(BlockRules.shownCount(25, 25)).isEqualTo(Optional.of(25L));
        assertThat(BlockRules.shownCount(31, 25)).isEqualTo(Optional.of(30L));
    }
}
