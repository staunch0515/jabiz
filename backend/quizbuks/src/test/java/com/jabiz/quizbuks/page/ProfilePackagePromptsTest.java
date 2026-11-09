package com.jabiz.quizbuks.page;

import com.jabiz.quizbuks.identity.AccountLimits;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The examples and the numbers of docs/quizbuks/profile-package-prompts.md, the prompt sponsors give other AI tools to
 * write a profile package from a company's website (F-C5, Q104a): each example must be read as an import reads a
 * package, and every limit the document states must be the code's. A rule changed here fails this test until the
 * document is changed with it.
 */
class ProfilePackagePromptsTest {

    private static final Path DOCUMENT = Path.of("../../docs/quizbuks/profile-package-prompts.md");
    private static final Pattern JSON_BLOCK = Pattern.compile("(?m)^```json\\n(.*?)^```", Pattern.DOTALL);

    private static String document() throws IOException {
        return Files.readString(DOCUMENT, StandardCharsets.UTF_8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyExampleIsReadAsAPackage() throws IOException {
        Matcher blocks = JSON_BLOCK.matcher(document());
        List<String> examples = new ArrayList<>();
        while (blocks.find()) {
            examples.add(blocks.group(1));
        }
        assertThat(examples).hasSize(2);
        for (String example : examples) {
            Map<String, Object> json = JsonMapper.builder().build().readValue(example, Map.class);
            ProfilePackage.Read read = ProfilePackage.read(json);
            assertThat(read.problems()).isEmpty();
            ProfilePackage profile = read.profile();
            // The prompt never has the AI write files or a mail server; images are links.
            assertThat(profile.files()).isEmpty();
            assertThat(profile.mailServer()).isNull();
            profile.images().values().forEach(image -> assertThat(image.isLink()).isTrue());
            assertThat(profile.page().handle()).matches(QbSponsorPages.HANDLE_PATTERN);
            assertThat(profile.page().website()).matches(QbSponsorPages.WEBSITE_PATTERN);
            for (ProfilePackage.Product product : profile.products()) {
                assertThat(QbAccounts.CURRENCIES).contains(product.currency());
                assertThat(product.price().scale()).isLessThanOrEqualTo(QbSponsorProducts.scaleOf(product.currency()));
                assertThat(product.shopUrl()).matches(QbSponsorPages.WEBSITE_PATTERN);
                assertThat(product.name().values()).allMatch(s -> s.length() <= QbSponsorProducts.NAME_LENGTH);
                assertThat(product.description().values())
                    .allMatch(s -> s.length() <= QbSponsorProducts.DESCRIPTION_LENGTH);
                if (product.code() != null) {
                    assertThat(QbSponsorProducts.CODE_PATTERN.matcher(product.code()).matches()).isTrue();
                }
            }
            assertThat(profile.page().headline().values())
                .allMatch(s -> s.length() <= QbSponsorPages.HEADLINE_LENGTH);
            assertThat(profile.basics().company().length()).isLessThanOrEqualTo(AccountLimits.COMPANY_LENGTH);
        }
        // The two examples teach both shapes: one language without products, three languages with them.
        Map<String, Object> first = JsonMapper.builder().build().readValue(examples.get(0), Map.class);
        Map<String, Object> second = JsonMapper.builder().build().readValue(examples.get(1), Map.class);
        assertThat(ProfilePackage.read(first).profile().languages()).hasSize(1);
        assertThat(ProfilePackage.read(first).profile().products()).isEmpty();
        assertThat(ProfilePackage.read(second).profile().languages()).containsExactly("zh", "ja", "en");
        assertThat(ProfilePackage.read(second).profile().products()).isNotEmpty();
    }

    @Test
    void theNumbersTheDocumentStatesAreTheCodes() throws IOException {
        String text = document();
        assertThat(text).contains("`" + QbSponsorPages.HANDLE_PATTERN + "`");
        assertThat(text).contains("≤ " + QbSponsorPages.HEADLINE_LENGTH + " 字");
        assertThat(text).contains("≤ " + QbSponsorPages.WEBSITE_LENGTH + " 字");
        assertThat(text).contains("≤ " + QbSponsorProducts.NAME_LENGTH + " 字");
        assertThat(text).contains("≤ " + QbSponsorProducts.DESCRIPTION_LENGTH + " 字");
        assertThat(text).contains("≤ " + QbSponsorProducts.SHOP_URL_LENGTH + " 字");
        assertThat(text).contains("≤ " + QbSponsorProducts.CODE_LENGTH + " 字");
        assertThat(text).contains("≤ " + AccountLimits.COMPANY_LENGTH + " 字");
        assertThat(text).contains("≤ " + AccountLimits.DISPLAY_NAME_LENGTH + " 字");
        assertThat(text).contains("≤ " + AccountLimits.INTRO_LENGTH + " 字");
        assertThat(text).contains("（缺省 " + QbParams.defaultOf(QbParams.PRODUCTS_MAX) + "）");
        assertThat(text).contains(QbParams.PRODUCTS_MAX);
        assertThat(text).contains("商品个数上限：" + QbParams.defaultOf(QbParams.PRODUCTS_MAX));
        assertThat(text).contains("USD、EUR、GBP、JPY、CNY、INR");
        assertThat(String.join("、", QbAccounts.CURRENCIES)).isEqualTo("USD、EUR、GBP、JPY、CNY、INR");
        assertThat(text).contains("\"format\": \"quizbuks-profile\", \"schema\": " + ProfilePackage.SCHEMA);
        assertThat(text).contains("字母、数字与 . _ -");
        assertThat(QbSponsorProducts.CODE_PATTERN.pattern()).isEqualTo("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");
        // Users read the word for no part of the product (glossary): the document keeps to it.
        assertThat(text).doesNotContain("后台");
    }
}
