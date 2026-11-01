package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.TextTranslations;
import com.jabiz.quizbuks.page.QbSponsorPages;
import com.jabiz.quizbuks.page.SponsorPageProcesses;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sponsor's page in several languages (Q73c, docs/quizbuks/plans/Q73-content-languages.md §4): the page offers
 * some languages with its headline in each, complete and up to date (a published page changes at once); the
 * introduction and the products may be translated; the public page and its products read in the first of the asked
 * languages the page offers, else its main one; a translation out of date is refused unless confirmed.
 */
class SiteLanguagesIT extends PlayItSupport {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> publicItems(String template, String handle, String languages) {
        Map<String, Object> page = client.get().uri(b -> b.path("/api/public/queries/" + template)
                .queryParam("p.handle", "{handle}").queryParam("p.languages", "{languages}").build(handle, languages))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    private Map<String, Object> page(String handle, String headline, Object... more) {
        Map<String, Object> page = body("handle", handle, "language", "en", "headline", headline);
        page.putAll(body(more));
        return page;
    }

    private Map<String, Object> product(String name, Object... more) {
        Map<String, Object> product = body("name", name, "description", "Fresh beans", "price", 1200, "currency",
            "JPY", "shopUrl", "https://example.com/beans", "listed", true);
        product.putAll(body(more));
        return product;
    }

    @Test
    void aPageInTwoLanguagesShowsItsTextsInTheVisitorsAndRefusesWhatIsOutOfDate() {
        String sponsor = newSponsor(unique("s-q73c"));
        String token = sponsorToken(sponsor);
        // The introduction (the page's "About us") in Chinese too, changed without a review as before.
        run("QB_SPONSOR_PROFILE_SAVE", token, body("displayName", "Ann", "company", "Company of " + sponsor,
            "intro", "We roast coffee.", "introTranslations", Map.of("zh", Map.of("intro", "我们烘焙咖啡。"))));
        String handle = "q73c-" + UUID.randomUUID().toString().substring(0, 8);

        // Offering Chinese needs its headline; the page's own language must be one it offers.
        assertThat(codes(refused(SponsorPageProcesses.SAVE, token, page(handle, "Fresh coffee", "languages",
            List.of("en", "zh")), 422))).containsExactly(TextTranslations.MISSING);
        assertThat(codes(refused(SponsorPageProcesses.SAVE, token, page(handle, "Fresh coffee", "languages",
            List.of("zh"), "translations", Map.of("zh", Map.of("headline", "新鲜咖啡"))), 422)))
            .contains(TextTranslations.LANGUAGES_INVALID);
        run(SponsorPageProcesses.SAVE, token, page(handle, "Fresh coffee", "languages", List.of("en", "zh"),
            "translations", Map.of("zh", Map.of("headline", "新鲜咖啡"))));
        Map<String, Object> submitted = run(SponsorPageProcesses.SUBMIT, token, body());
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", submitted.get("approvalRequestId"),
            "decision", "APPROVE"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_sponsor_page WHERE user_id = ?", sponsor).getFirst())
            .containsEntry("status", QbSponsorPages.PUBLISHED);

        // In Chinese for a visitor who asks for it; in the main language (English, the first offered) otherwise.
        assertThat(publicItems("qb.public.sponsor-page", handle, "zh").getFirst())
            .containsEntry("headline", "新鲜咖啡").containsEntry("story", "我们烘焙咖啡。")
            .containsEntry("displayLanguage", "zh").containsEntry("languages", "en,zh");
        assertThat(publicItems("qb.public.sponsor-page", handle, "ja").getFirst())
            .containsEntry("headline", "Fresh coffee").containsEntry("story", "We roast coffee.")
            .containsEntry("displayLanguage", "en");

        // A new headline leaves the Chinese one out of date: refused, unless the sponsor confirms it still holds.
        assertThat(codes(refused(SponsorPageProcesses.SAVE, token, page(handle, "Fresh coffee, roasted daily",
            "languages", List.of("en", "zh"), "translations", Map.of("zh", Map.of("headline", "新鲜咖啡"))), 422)))
            .containsExactly(TextTranslations.STALE);
        run(SponsorPageProcesses.SAVE, token, page(handle, "Fresh coffee, roasted daily", "languages",
            List.of("en", "zh"), "translations", Map.of("zh", Map.of("headline", "新鲜咖啡")), "confirmed",
            List.of("zh.headline")));

        // Products need no translation; a translated one shows in Chinese, the other as written and says so.
        String translated = (String) run("QB_PRODUCT_SAVE", token, product("Arabica", "translations",
            Map.of("zh", Map.of("name", "阿拉比卡", "description", "新鲜的咖啡豆")))).get("productId");
        run("QB_PRODUCT_SAVE", token, product("Robusta"));
        List<Map<String, Object>> products = publicItems("qb.public.sponsor-page-products", handle, "zh");
        assertThat(products).extracting(p -> p.get("name"), p -> p.get("description"), p -> p.get("textLanguage"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("阿拉比卡", "新鲜的咖啡豆", "zh"),
                org.assertj.core.groups.Tuple.tuple("Robusta", "Fresh beans", "en"));
        // A product renamed with its old translation: out of date, refused.
        assertThat(codes(refused("QB_PRODUCT_SAVE", token, product("Arabica beans", "productId", translated,
            "translations", Map.of("zh", Map.of("name", "阿拉比卡", "description", "新鲜的咖啡豆"))), 422)))
            .contains(TextTranslations.STALE);
    }
}
