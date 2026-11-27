package com.jabiz.quizbuks.avatar;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The preset avatars the server accepts are the ones the app draws (Q114a): {@link AvatarPresets#CODES} and the app's
 * {@code avatar/presets.json}, which the app's own test checks against its drawings, in the same order.
 */
class AvatarPresetsTest {

    private static final Path APP_LIST = Path.of("../../quizbuks-web/app/src/avatar/presets.json");

    @Test
    void theServerAcceptsExactlyThePresetsTheAppDraws() throws IOException {
        JsonNode list = JsonMapper.builder().build().readTree(Files.readString(APP_LIST));
        List<String> codes = new ArrayList<>();
        list.get("codes").forEach(code -> codes.add(code.asString()));
        assertThat(codes).containsExactlyElementsOf(AvatarPresets.CODES);
    }

    @Test
    void codesAreDistinctAndShort() {
        assertThat(AvatarPresets.CODES).doesNotHaveDuplicates()
            .allSatisfy(code -> assertThat(code).matches("p\\d{2}").hasSizeLessThanOrEqualTo(10));
    }
}
