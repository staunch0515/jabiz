package com.jabiz.quizbuks.content;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunksTest {

    @Test
    void splitsInOrderIntoChunksOfAtMostTheSize() {
        List<Integer> items = IntStream.rangeClosed(1, 2_501).boxed().toList();
        List<List<Integer>> chunks = Chunks.of(items, ContentLimits.RELEASE_CHUNK);
        assertThat(chunks).extracting(List::size).containsExactly(1_000, 1_000, 501);
        assertThat(chunks.stream().flatMap(List::stream).toList()).isEqualTo(items);
        assertThat(Chunks.of(List.of(), 3)).isEmpty();
        assertThat(Chunks.of(List.of(1, 2, 3), 3)).containsExactly(List.of(1, 2, 3));
        assertThatThrownBy(() -> Chunks.of(List.of(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneVersionsFilesFitOneCommit() {
        // Publishing a version writes its new files in one commit through the dataset limited to RELEASE_CHUNK.
        assertThat(ContentLimits.MAX_FILES_PER_VERSION).isEqualTo(901)
            .isLessThanOrEqualTo(ContentLimits.RELEASE_CHUNK);
    }
}
