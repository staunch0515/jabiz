package com.jabiz.quizbuks.page;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading a profile package's shape (docs/quizbuks/plans/Q100-profile-package.md, D-Q100-1, D-Q100-2, D-Q100-11). */
class ProfilePackageTest {

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> document() {
        return map("format", "quizbuks-profile", "schema", 1, "languages", List.of("ja", "zh", "en"),
            "basics", map("contactName", "Ann", "company", "Shop", "intro", map("ja", "紹介", "zh", "介绍"),
                "avatar", "f1"),
            "page", map("handle", "shop", "headline", "見出し", "website", "https://shop.example", "cover",
                map("url", "https://img.example/c.jpg")),
            "products", List.of(map("productId", "p1", "name", map("ja", "茶", "en", "Tea"), "price", "1200",
                "currency", "JPY", "shopUrl", "https://shop.example/tea", "listed", false)),
            "mailServer", map("host", "smtp.example", "port", 587, "tls", "STARTTLS", "username", "shop",
                "fromAddress", "shop@example.com"),
            "files", map("f1", map("type", "image/jpeg", "size", 1234, "data", "AAAA")));
    }

    private static List<String> paths(ProfilePackage.Read read) {
        return read.problems().stream().map(Violation::field).toList();
    }

    @Test
    void aWholePackageIsRead() {
        ProfilePackage.Read read = ProfilePackage.read(document());
        assertThat(read.problems()).isEmpty();
        ProfilePackage profile = read.profile();
        assertThat(profile.base()).isEqualTo("ja");
        assertThat(profile.basics().intro()).containsEntry("ja", "紹介").containsEntry("zh", "介绍");
        assertThat(profile.basics().avatar()).isEqualTo(new ProfilePackage.Image("f1", null));
        // A plain string is the base text; a link is kept as a link.
        assertThat(profile.page().headline()).containsExactly(Map.entry("ja", "見出し"));
        assertThat(profile.page().cover().isLink()).isTrue();
        ProfilePackage.Product tea = profile.products().getFirst();
        assertThat(tea.price()).isEqualByComparingTo(new BigDecimal("1200"));
        assertThat(tea.listed()).isFalse();
        // Left out: the image there is stays; not an image of the package.
        assertThat(tea.image()).isSameAs(ProfilePackage.Image.KEEP);
        assertThat(profile.images()).containsOnlyKeys("basics.avatar", "page.cover");
        assertThat(profile.mailServer().port()).isEqualTo(587);
        assertThat(profile.files()).containsKey("f1");
    }

    @Test
    void anExplicitNullIsNoImage() {
        Map<String, Object> document = document();
        Map<String, Object> page = new HashMap<>(castMap(document.get("page")));
        page.put("cover", null);
        document.put("page", page);
        assertThat(ProfilePackage.read(document).profile().page().cover()).isNull();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void everyProblemIsReportedWithItsPath() {
        Map<String, Object> document = document();
        document.put("languages", List.of("ja", "fr", "ja"));
        castMap(document.get("basics")).put("company", " ");
        castMap(document.get("basics")).put("avatar", "f9");
        castMap(document.get("page")).put("headline", map("zh", "只有中文"));
        castMap(document.get("page")).put("cover", map("url", "http://img.example/c.jpg"));
        document.put("products", List.of(map("name", map("ko", "차"), "price", "abc", "currency", "JPY",
            "shopUrl", "https://shop.example")));
        castMap(document.get("mailServer")).put("password", "secret");
        ProfilePackage.Read read = ProfilePackage.read(document);
        assertThat(read.profile()).isNull();
        assertThat(paths(read)).contains("languages", "basics.company", "basics.avatar", "page.headline.zh",
            "page.headline.ja", "page.cover", "products[0].name.ko", "products[0].name.ja", "products[0].price",
            "mailServer.password");
        assertThat(read.problems()).filteredOn(v -> v.field().equals("basics.avatar"))
            .extracting(Violation::ruleCode).containsOnly(ProfilePackage.FILE_INVALID);
    }

    @Test
    void notAPackageOrAnotherSchemaIsRefusedAtOnce() {
        assertThat(ProfilePackage.read(map("format", "quizbuks.quiz")).problems()).extracting(Violation::ruleCode)
            .containsExactly(ProfilePackage.FORMAT_INVALID);
        Map<String, Object> document = document();
        document.put("schema", 2);
        assertThat(paths(ProfilePackage.read(document))).containsExactly("schema");
        assertThat(ProfilePackage.read(null).profile()).isNull();
    }

    @Test
    void moreProductsThanASponsorMayHaveAreRefused() {
        // Without a limit given: the declared value of qb.products.max (50).
        int max = Integer.parseInt(QbParams.defaultOf(QbParams.PRODUCTS_MAX));
        Map<String, Object> document = withProducts(max + 1);
        assertThat(ProfilePackage.read(document).problems()).extracting(Violation::ruleCode)
            .containsExactly(SponsorPageCodes.PRODUCT_LIMIT);
        assertThat(ProfilePackage.read(withProducts(max)).problems()).isEmpty();
    }

    /** F-C11: the limit is the one the process read from qb.products.max. */
    @Test
    void theProductsLimitIsTheOneGiven() {
        List<Violation> problems = ProfilePackage.read(withProducts(4), 3).problems();
        assertThat(problems).extracting(Violation::ruleCode).containsExactly(SponsorPageCodes.PRODUCT_LIMIT);
        assertThat(problems.getFirst().params()).containsEntry("max", 3);
        assertThat(problems.getFirst().field()).isEqualTo("products");
        assertThat(ProfilePackage.read(withProducts(3), 3).problems()).isEmpty();
        assertThat(ProfilePackage.read(withProducts(60), 100).problems()).isEmpty();
    }

    private Map<String, Object> withProducts(int count) {
        Map<String, Object> document = document();
        List<Object> products = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            products.add(map("name", "P" + i, "price", 1, "currency", "JPY", "shopUrl", "https://shop.example"));
        }
        document.put("products", products);
        return document;
    }

    @Test
    void aFileIsAnImage() {
        Map<String, Object> document = document();
        document.put("files", map("f1", map("type", "application/pdf", "size", 10)));
        assertThat(paths(ProfilePackage.read(document))).contains("files.f1", "basics.avatar");
    }
}
