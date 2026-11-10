package com.jabiz.quizbuks.setup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-F3: the Sponsor app's bottom menu starts from its own copy of the parameter's declared value (so that it does not
 * change once the parameter is read); the two must be the same, and every key an area of the app.
 */
class SponsorLayoutParamsTest {

    private static final Path SHELL = Path.of("../../quizbuks-web/sponsor/src/shell");

    @Test
    void theAppsDefaultIsTheParametersDeclaredValue() throws IOException {
        String json = Files.readString(SHELL.resolve("bottom-bar.default.json"));
        Matcher value = Pattern.compile("\"bottomBar\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertThat(value.find()).isTrue();
        assertThat(value.group(1)).isEqualTo(QbParams.defaultOf(QbParams.SPONSOR_BOTTOM_BAR));
    }

    @Test
    void everyKeyOfTheDeclaredValueIsAnAreaOfTheApp() throws IOException {
        String areas = Files.readString(SHELL.resolve("areas.ts"));
        Matcher keys = Pattern.compile("export type AreaKey = ([^\\n]*\\n[^\\n]*)").matcher(areas);
        assertThat(keys.find()).isTrue();
        List<String> declared = Arrays.stream(QbParams.defaultOf(QbParams.SPONSOR_BOTTOM_BAR).split(","))
            .map(String::strip).toList();
        assertThat(declared).isNotEmpty().doesNotHaveDuplicates();
        for (String key : declared) {
            assertThat(keys.group(1)).as(key).contains("'" + key + "'");
        }
    }

    @Test
    void theParameterIsOrdinary() {
        assertThat(QbParams.CONTROLLED).doesNotContain(QbParams.SPONSOR_BOTTOM_BAR);
    }
}
