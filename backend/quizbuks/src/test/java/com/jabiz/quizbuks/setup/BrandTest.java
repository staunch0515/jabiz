package com.jabiz.quizbuks.setup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build writes the brand profile QB_BRAND chose into the resources (Q55b): the brand's name in every message,
 * its support address and site in {@link Brand}, nothing left of the tokens, and no message names QuizBuks itself.
 */
class BrandTest {

    private static final List<String> MESSAGES = List.of("/messages_en.properties", "/messages_zh.properties",
        "/messages_ja.properties", "/jabiz/quizbuks/shopify/messages_en.properties",
        "/jabiz/quizbuks/shopify/messages_zh.properties", "/jabiz/quizbuks/shopify/messages_ja.properties");

    /** The chosen profile's brand.json, read as the build reads it (the test runs in backend/quizbuks). */
    private static String profileValue(String key) throws IOException {
        String id = Objects.requireNonNullElse(System.getenv("QB_BRAND"), "").trim();
        Path profile = Path.of("../../quizbuks-web/brands", id.isEmpty() ? "quizbuks" : id, "brand.json");
        String json = Files.readString(profile);
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertThat(matcher.find()).as(key).isTrue();
        return matcher.group(1);
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = BrandTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theBuildIsTheChosenBrand() throws IOException {
        Brand brand = Brand.current();
        assertThat(brand.name()).isEqualTo(profileValue("name"));
        assertThat(brand.supportEmail()).isEqualTo(profileValue("supportEmail"));
        assertThat(brand.siteUrl()).isEqualTo(profileValue("siteUrl"));
        assertThat(QbParams.ALL.stream().filter(param -> param.key().equals(QbParams.SUPPORT_EMAIL)).findFirst()
            .orElseThrow().value()).isEqualTo(brand.supportEmail());
    }

    @Test
    void theMessagesNameTheBrandAndNoTokenIsLeft() throws IOException {
        for (String path : MESSAGES) {
            String text = resource(path);
            assertThat(text).as(path).doesNotContain("@QB_BRAND_");
            if (!path.startsWith("/jabiz")) {
                Properties properties = new Properties();
                try (InputStream in = BrandTest.class.getResourceAsStream(path)) {
                    properties.load(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8));
                }
                assertThat(properties.getProperty("app.name")).as(path).isEqualTo(Brand.current().name());
            }
        }
        assertThat(resource("/application.yml")).doesNotContain("@QB_BRAND_");
    }

    @Test
    void theSourceMessagesNameNoBrandOfTheirOwn() throws IOException {
        List<Path> sources = List.of(Path.of("src/main/resources/messages_en.properties"),
            Path.of("src/main/resources/messages_zh.properties"), Path.of("src/main/resources/messages_ja.properties"),
            Path.of("../quizbuks-shopify/src/main/resources/jabiz/quizbuks/shopify/messages_en.properties"),
            Path.of("../quizbuks-shopify/src/main/resources/jabiz/quizbuks/shopify/messages_zh.properties"),
            Path.of("../quizbuks-shopify/src/main/resources/jabiz/quizbuks/shopify/messages_ja.properties"));
        for (Path source : sources) {
            List<String> named = Files.readAllLines(source, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.stripLeading().startsWith("#") && line.contains("QuizBuks")).toList();
            assertThat(named).as(source.toString()).isEmpty();
        }
    }
}
