package com.jabiz.runtime.file;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileContentTest {

    @Test
    void readsSingleRanges() {
        assertThat(FileContent.range(null, 100)).isEmpty();
        assertThat(FileContent.range(" ", 100)).isEmpty();
        assertThat(FileContent.range("bytes=0-9", 100)).contains(new FileContent.Range(0, 10));
        assertThat(FileContent.range("bytes=90-", 100)).contains(new FileContent.Range(90, 10));
        assertThat(FileContent.range("bytes=90-500", 100)).contains(new FileContent.Range(90, 10));
        assertThat(FileContent.range("bytes=-30", 100)).contains(new FileContent.Range(70, 30));
        assertThat(FileContent.range("bytes=-300", 100)).contains(new FileContent.Range(0, 100));
    }

    @Test
    void servesWhatItCannotSplitWhole() {
        assertThat(FileContent.range("bytes=0-1,5-6", 100)).isEmpty();
        assertThat(FileContent.range("items=0-1", 100)).isEmpty();
    }

    @Test
    void refusesUnsatisfiableRanges() {
        assertThatThrownBy(() -> FileContent.range("bytes=100-", 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileContent.range("bytes=9-3", 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileContent.range("bytes=-", 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileContent.range("bytes=-0", 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileContent.range("bytes=0-0", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileContent.range("bytes=99999999999999999999-", 100))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
