package com.jabiz.quizbuks.content.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the site job makes of Claude's reply (F-C7, docs/quizbuks/plans/Q104-site-to-profile.md): the package goes through
 * the import's own checks and what fails is left out and listed; a person's e-mail address and telephone number are
 * taken out of every text; a source or an article from outside the site is dropped; the questions have the options
 * asked, one correct.
 */
class SiteProfileAnswerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> SITE = List.of("www.aini.example", "aini.example");

    private static SiteProfileJob.Input input(boolean profile, boolean products, boolean quiz) {
        return new SiteProfileJob.Input("claude-opus-5-5", "medium", "https://www.aini.example/", SITE, "ja",
            List.of("ja", "en"), profile, products, quiz, 10, 50, 5, 2, 3, "key");
    }

    private static final String GOOD = """
        {"failure": null,
         "package": {
           "basics": {"company": "アイニ株式会社",
             "intro": {"ja": "ヘルメットの会社です。お問い合わせは info@aini.example または 03-1234-5678 まで。",
                        "en": "A helmet company. Write to taro@aini.example, call +81 90 1234 5678."},
             "contactName": "Taro Yamada", "avatar": {"url": "https://www.aini.example/logo.png"}},
           "page": {"handle": "aini", "headline": {"ja": "毎日を安心して", "en": "Ride with confidence"},
             "website": "https://www.aini.example/", "cover": {"url": "not an address"}},
           "products": [
             {"code": "AH-100", "name": {"ja": "通勤ヘルメット", "en": "City helmet"}, "price": 12800, "currency": "JPY",
              "shopUrl": "https://www.aini.example/shop/ah-100"},
             {"name": {"ja": "高い", "en": "Dear"}, "price": 12.5, "currency": "USD"},
             {"name": {"ja": "子供用", "en": "Kids"}, "price": 49.9, "currency": "USD",
              "shopUrl": "https://www.aini.example/shop/kids"}]},
         "sources": {"basics.company": "https://www.aini.example/about", "page.headline": "https://evil.example/x",
                     "products[0].price": "https://aini.example/shop/ah-100", "products[9].price": "https://aini.example/"},
         "notFound": ["page.cover", "basics.avatar"],
         "translated": ["en", "zh", "ja"],
         "materials": [
           {"title": "About us", "body": "We have made helmets since 1990.", "url": "https://www.aini.example/about"},
           {"title": "Elsewhere", "body": "Not our site.", "url": "https://other.example/about"},
           {"title": "Second", "body": "Safety tests.", "url": "https://aini.example/safety"}],
         "questions": [
           {"stem": "Since when?", "options": [{"text": "1990", "correct": true}, {"text": "2000", "correct": false},
             {"text": "2010", "correct": false}], "materials": [1]},
           {"stem": "Two correct?", "options": [{"text": "a", "correct": true}, {"text": "b", "correct": true},
             {"text": "c", "correct": false}], "materials": [1]},
           {"stem": "Wrong number of options", "options": [{"text": "a", "correct": true}, {"text": "b", "correct": false}],
             "materials": [1]},
           {"stem": "Third valid", "options": [{"text": "x", "correct": true}, {"text": "y", "correct": false},
             {"text": "z", "correct": false}], "materials": [2, 7]}]}
        """;

    @Test
    void aGoodReplyIsKeptAndWhatFailsTheChecksIsLeftOutAndListed() {
        SiteProfileJob.Result result = SiteProfileAnswer.parse("claude-opus-5-5", false, "Here you are:\n" + GOOD,
            input(true, true, true), JSON);
        assertThat(result.failure()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> basics = (Map<String, Object>) result.profile().get("basics");
        // The contact name is never taken; the avatar is a valid https link and stays.
        assertThat(basics).doesNotContainKey("contactName").containsKey("avatar");
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) result.profile().get("page");
        // The cover is not an address: left out, with the reason.
        assertThat(page).doesNotContainKey("cover");
        assertThat(result.dropped()).extracting(SiteProfileJob.Dropped::path).contains("page.cover")
            .contains("products[1]");
        // The product without a purchase link is out; the other two stay, in order.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) result.profile().get("products");
        assertThat(products).extracting(product -> product.get("code")).containsExactly("AH-100", null);
        assertThat(result.profile()).containsEntry("format", "quizbuks-profile").containsEntry("schema", 1);
        assertThat(result.translated()).containsExactly("en");
        assertThat(result.notFound()).containsExactly("page.cover", "basics.avatar");
    }

    @Test
    void aPersonsEmailAddressAndTelephoneNumberAreTakenOutOfEveryText() {
        SiteProfileJob.Result result = SiteProfileAnswer.parse("m", false, GOOD, input(true, true, true), JSON);
        String shown = result.profile().toString();
        assertThat(shown).doesNotContain("info@aini.example", "taro@aini.example", "03-1234-5678", "090 1234 5678",
            "90 1234 5678");
        assertThat(shown).contains("ヘルメットの会社です", "A helmet company");
        assertThat(SiteProfileAnswer.withoutPersonalDetails("Open 2026-01-31, price 12,800 yen, call 090-1234-5678."))
            .isEqualTo("Open 2026-01-31, price 12,800 yen, call.");
        assertThat(SiteProfileAnswer.withoutPersonalDetails("[mail us](mailto:a@b.example) [call](tel:0312345678)"))
            .doesNotContain("mailto", "tel:", "a@b.example");
    }

    @Test
    void sourcesAndArticlesFromOutsideTheSiteOrOfNothingInThePackageAreDropped() {
        SiteProfileJob.Result result = SiteProfileAnswer.parse("m", false, GOOD, input(true, true, true), JSON);
        assertThat(result.sources()).containsOnlyKeys("basics.company", "products[0].price");
        assertThat(result.materials()).extracting(SiteProfileJob.Material::url)
            .containsExactly("https://www.aini.example/about", "https://aini.example/safety");
    }

    @Test
    void theQuestionsHaveTheOptionsAskedOneCorrectAndRestOnTheArticlesThatExist() {
        SiteProfileJob.Result result = SiteProfileAnswer.parse("m", false, GOOD, input(true, true, true), JSON);
        // Two asked: the first and the fourth are right (three options, one correct); the other two are not.
        assertThat(result.questions()).extracting(AiQuestionsJob.Candidate::stem)
            .containsExactly("Since when?", "Third valid");
        assertThat(result.questions().get(1).materials()).containsExactly(2);
        assertThat(result.invalidQuestions()).isEqualTo(2);
    }

    @Test
    void whatWasNotAskedForIsNotInTheResult() {
        SiteProfileJob.Result result = SiteProfileAnswer.parse("m", false, GOOD, input(false, false, false), JSON);
        assertThat(result.profile()).doesNotContainKeys("basics", "page");
        assertThat((List<?>) result.profile().get("products")).isEmpty();
        assertThat(result.materials()).isEmpty();
        assertThat(result.questions()).isEmpty();
    }

    @Test
    void aSiteThatCouldNotBeReadIsAFailureAndNothingElse() {
        SiteProfileJob.Result robots = SiteProfileAnswer.parse("m", false, "{\"failure\": \"ROBOTS\"}",
            input(true, true, true), JSON);
        assertThat(robots.failure()).isEqualTo("ROBOTS");
        assertThat(robots.profile()).isEmpty();
        assertThat(SiteProfileAnswer.parse("m", false, "{\"failure\": \"WHATEVER\", \"package\": {}}",
            input(true, true, true), JSON).failure()).isNull();
    }

    @Test
    void aReplyWithoutAnObjectIsNotAnAnswer() {
        assertThat(SiteProfileAnswer.parse("m", false, "I could not do that.", input(true, true, true), JSON)).isNull();
        assertThat(SiteProfileAnswer.parse("m", false, null, input(true, true, true), JSON)).isNull();
    }

    @Test
    void onlyHttpsAddressesOfTheSitesOwnDomainsAreOnTheSite() {
        assertThat(SiteProfileAnswer.onSite("https://www.aini.example/a", SITE)).isTrue();
        assertThat(SiteProfileAnswer.onSite("https://AINI.example/a", SITE)).isTrue();
        assertThat(SiteProfileAnswer.onSite("http://www.aini.example/a", SITE)).isFalse();
        assertThat(SiteProfileAnswer.onSite("https://shop.aini.example/a", SITE)).isFalse();
        assertThat(SiteProfileAnswer.onSite("https://user@www.aini.example/a", SITE)).isFalse();
        assertThat(SiteProfileAnswer.onSite("https://www.aini.example:8443/a", SITE)).isFalse();
        assertThat(SiteProfileAnswer.onSite("https://www.aini.example/a b", SITE)).isFalse();
        assertThat(SiteProfileAnswer.onSite(null, SITE)).isFalse();
    }
}
