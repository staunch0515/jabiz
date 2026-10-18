package com.jabiz.runtime.file;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileKeysTest {

    /** Created 2026-01-31T09:00:00Z. */
    private static final UUID ID = new UUID((Instant.parse("2026-01-31T09:00:00Z").toEpochMilli() << 16) | 0x7abcL,
        0x8123_4567_89ab_cdefL);

    @Test
    void derivesKeysFromTheIdAlone() {
        assertThat(FileKeys.timeOf(ID)).isEqualTo(Instant.parse("2026-01-31T09:00:00Z"));
        assertThat(FileKeys.prefix(ID)).isEqualTo("2026/01/" + ID);
        assertThat(FileKeys.key(ID, "w320")).isEqualTo("2026/01/" + ID + "/w320");
        assertThat(FileKeys.fileIdOf("2026/01/" + ID + "/original")).contains(ID);
    }

    /** Files of a policy with private metadata have random ids (decision D48 item 3): their keys carry no time. */
    @Test
    void randomIdsLiveUnderTheirFirstTwoDigits() {
        UUID random = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        assertThat(FileKeys.timed(random)).isFalse();
        assertThat(FileKeys.timed(ID)).isTrue();
        assertThat(FileKeys.prefix(random)).isEqualTo("r/3f/" + random);
        assertThat(FileKeys.key(random, "original")).isEqualTo("r/3f/" + random + "/original");
        assertThat(FileKeys.fileIdOf("r/3f/" + random + "/w320")).contains(random);
        assertThat(FileKeys.fileIdOf("r/40/" + random + "/original")).as("wrong directory").isEmpty();
        assertThat(FileKeys.fileIdOf("2026/01/" + random + "/original")).as("not dated").isEmpty();
        assertThat(FileKeys.fileIdOf("r/" + ID.toString().substring(0, 2) + "/" + ID + "/original"))
            .as("a dated id in the random area").isEmpty();
        assertThatThrownBy(() -> FileKeys.timeOf(random)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileKeys.prefix(UUID.fromString("3fa85f64-5717-1562-b3fc-2c963f66afa6")))
            .as("neither dated nor random").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyItsOwnLayout() {
        assertThatThrownBy(() -> FileKeys.key(ID, "../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileKeys.key(ID, "w0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileKeys.timeOf(UUID.fromString("00000000-0000-4000-8000-000000000000")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(FileKeys.fileIdOf("2026/02/" + ID + "/original")).as("wrong month").isEmpty();
        assertThat(FileKeys.fileIdOf("2026/01/" + ID + "/thumb")).isEmpty();
        assertThat(FileKeys.fileIdOf(".uploads/x.part")).isEmpty();
        assertThat(FileKeys.fileIdOf("2026/01/" + ID.toString().toUpperCase() + "/original")).isEmpty();
    }
}
