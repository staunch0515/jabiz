package com.jabiz.quizbuks.content;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunksTest {

    @Test
    void splitsInOrderIntoChunksOfAtMostTheSize() {
        List<Integer> items = IntStream.rangeClosed(1, 7_501).boxed().toList();
        List<List<Integer>> chunks = Chunks.of(items, ContentLimits.RELEASE_CHUNK);
        assertThat(chunks).extracting(List::size).containsExactly(3_000, 3_000, 1_501);
        assertThat(chunks.stream().flatMap(List::stream).toList()).isEqualTo(items);
        assertThat(Chunks.of(List.of(), 3)).isEmpty();
        assertThat(Chunks.of(List.of(1, 2, 3), 3)).containsExactly(List.of(1, 2, 3));
        assertThatThrownBy(() -> Chunks.of(List.of(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneVersionsFilesFitOneCommit() {
        // Publishing a version writes its new files in one commit. F-S6: a bank of up to MAX_QUESTIONS_TECH questions
        // has more files than a removal releases per commit, so the files' dataset allows the larger of the two
        // (ContentConfig); a version of the old limit (100 questions) still fits one release chunk.
        assertThat(ContentLimits.MAX_FILES_PER_VERSION).isEqualTo(1 + ContentLanguages.MAX_LANGUAGES
            * (ContentLimits.MAX_QUESTIONS_TECH * (1 + ContentLimits.MAX_OPTIONS)
            + ContentLimits.MAX_MATERIALS * ContentLimits.MAX_IMAGES)).isEqualTo(42_601)
            .isGreaterThan(ContentLimits.RELEASE_CHUNK);
        assertThat(1 + ContentLanguages.MAX_LANGUAGES * (100 * (1 + ContentLimits.MAX_OPTIONS)
            + ContentLimits.MAX_MATERIALS * ContentLimits.MAX_IMAGES)).isLessThanOrEqualTo(ContentLimits.RELEASE_CHUNK);
    }
}
