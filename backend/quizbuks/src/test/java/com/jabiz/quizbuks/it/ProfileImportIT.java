package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.page.ProfilePackage;
import com.jabiz.quizbuks.page.ProfileTransferProcesses;
import com.jabiz.quizbuks.page.QbSponsorPages;
import com.jabiz.quizbuks.page.SponsorPageProcesses;
import com.jabiz.quizbuks.page.SponsorProductProcesses;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Importing a profile package (docs/quizbuks/plans/Q100-profile-package.md, Q100a): the check's plan, the import
 * through the hub's own processes in one transaction, products replaced as a whole, translations up to date as
 * imported, the mail server's password typed in, and never a review changed.
 */
class ProfileImportIT extends PlayItSupport {

    private static String newHandle() {
        return "q100-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String upload(String token, String policy) {
        return (String) upload(policy, FileSamples.jpeg(400, 300), "image.jpg", "image/jpeg", token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    private static Map<String, Object> product(String productId, String name, String zhName, String image) {
        Map<String, Object> product = new LinkedHashMap<>();
        if (productId != null) {
            product.put("productId", productId);
        }
        product.put("name", body("ja", name, "zh", zhName));
        product.put("description", body("ja", name + "の説明"));
        product.put("price", "1200");
        product.put("currency", "JPY");
        product.put("shopUrl", "https://shop.example/" + UUID.randomUUID());
        if (image != null) {
            product.put("image", image);
        }
        return product;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> document(String handle, List<Map<String, Object>> products,
        boolean withFiles) {
        Map<String, Object> document = body("format", ProfilePackage.FORMAT, "schema", ProfilePackage.SCHEMA,
            "languages", List.of("ja", "zh"),
            "basics", body("contactName", "Ann", "company", "茶舗", "intro", body("ja", "お茶の店", "zh", "茶叶店")),
            "page", body("handle", handle, "headline", body("ja", "おいしいお茶", "zh", "好喝的茶"),
                "website", "https://shop.example"),
            "products", products);
        if (withFiles) {
            ((Map<String, Object>) document.get("basics")).put("avatar", "f1");
            ((Map<String, Object>) document.get("page")).put("cover", "f2");
            document.put("files", body("f1", body("type", "image/jpeg", "size", 1000),
                "f2", body("type", "image/jpeg", "size", 2000), "f3", body("type", "image/jpeg", "size", 3000)));
        }
        return document;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> plan(Map<String, Object> output) {
        return (Map<String, Object>) output.get("plan");
    }

    private static Map<String, Object> latestOnboarding(String userId) {
        return query("SELECT * FROM qb_sponsor_version WHERE user_id = ? ORDER BY version_no DESC LIMIT 1", userId)
            .getFirst();
    }

    private static List<Map<String, Object>> products(String userId) {
        return query("SELECT product_id, name, translations, image, status FROM qb_sponsor_product "
            + "WHERE user_id = ? ORDER BY seq", userId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anApprovedSponsorImportsEverythingAndProductsAreReplaced() {
        String sponsor = newSponsor(unique("s-q100-all"));
        String token = sponsorToken(sponsor);
        String handle = newHandle();
        Map<String, Object> document = document(handle, List.of(product(null, "緑茶", "绿茶", "f3"),
            product(null, "紅茶", "红茶", null)), true);
        document.put("mailServer", body("host", "localhost", "port", 1, "tls", "SMTPS", "username", "shop",
            "fromAddress", "shop@example.com", "fromName", "The Shop"));

        // The check: everything is saved, three images to upload, the mail server needs its password.
        Map<String, Object> plan = plan(run(ProfileTransferProcesses.CHECK, token, body("document", document)));
        assertThat(plan).containsEntry("basics", "SAVE").containsEntry("page", "SAVE")
            .containsEntry("mailServer", "PASSWORD").containsEntry("added", List.of("緑茶", "紅茶"))
            .containsEntry("deleted", List.of());
        assertThat((List<Map<String, Object>>) plan.get("files")).extracting(f -> f.get("path") + "=" + f.get("policy"))
            .containsExactly("basics.avatar=" + QbAccounts.AVATAR_POLICY, "page.cover=" + QbSponsorPages.IMAGE_POLICY,
                "products[0].image=" + QbSponsorPages.IMAGE_POLICY);

        Map<String, Object> files = body("basics.avatar", upload(token, QbAccounts.AVATAR_POLICY),
            "page.cover", upload(token, QbSponsorPages.IMAGE_POLICY),
            "products[0].image", upload(token, QbSponsorPages.IMAGE_POLICY));
        // Without the password nothing at all is written.
        assertThat(codes(refused(ProfileTransferProcesses.IMPORT, token, body("document", document, "files", files),
            422))).containsExactly(ProfileTransferProcesses.MAIL_PASSWORD);
        assertThat(products(sponsor)).isEmpty();

        run(ProfileTransferProcesses.IMPORT, token, body("document", document, "files", files,
            "mailPassword", "secret-1"));
        Map<String, Object> onboarding = latestOnboarding(sponsor);
        assertThat(onboarding).containsEntry("company", "茶舗").containsEntry("intro", "お茶の店")
            .containsEntry("review_status", "APPROVED");
        assertThat(String.valueOf(onboarding.get("avatar"))).isEqualTo(files.get("basics.avatar"));
        assertThat(String.valueOf(onboarding.get("intro_translations"))).contains("茶叶店");
        Map<String, Object> page = query("SELECT * FROM qb_sponsor_page WHERE user_id = ?", sponsor).getFirst();
        assertThat(page).containsEntry("handle", handle).containsEntry("language", "ja")
            .containsEntry("status", QbSponsorPages.DRAFT).containsEntry("languages", "ja,zh");
        assertThat(String.valueOf(page.get("translations"))).contains("好喝的茶");
        List<Map<String, Object>> saved = products(sponsor);
        assertThat(saved).extracting(row -> row.get("name")).containsExactly("緑茶", "紅茶");
        assertThat(String.valueOf(saved.getFirst().get("image"))).isEqualTo(files.get("products[0].image"));
        assertThat(query("SELECT host, username FROM qb_sponsor_mail_server WHERE sponsor_id = ?", sponsor))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("host", "localhost"));

        // Imported translations are up to date: the page saves again as it is (no out-of-date refusal).
        run(SponsorPageProcesses.SAVE, token, body("handle", handle, "language", "ja", "headline", "おいしいお茶",
            "languages", List.of("ja", "zh"), "website", "https://shop.example", "cover", files.get("page.cover")));

        // Again, with the first product changed, the second left out and a new one: replaced as a whole; the images
        // left out of the package stay; the same mail server keeps its stored password.
        String green = String.valueOf(saved.getFirst().get("product_id"));
        Map<String, Object> again = document(handle, List.of(product(green, "新茶", "新茶", null),
            product(null, "ほうじ茶", "焙茶", null)), false);
        again.put("mailServer", document.get("mailServer"));
        Map<String, Object> second = plan(run(ProfileTransferProcesses.CHECK, token, body("document", again)));
        assertThat(second).containsEntry("changed", List.of("新茶")).containsEntry("added", List.of("ほうじ茶"))
            .containsEntry("deleted", List.of("紅茶")).containsEntry("mailServer", "SAVE")
            .containsEntry("files", List.of());
        run(ProfileTransferProcesses.IMPORT, token, body("document", again));
        List<Map<String, Object>> replaced = products(sponsor);
        assertThat(replaced).extracting(row -> row.get("name")).containsExactly("新茶", "ほうじ茶");
        assertThat(String.valueOf(replaced.getFirst().get("product_id"))).isEqualTo(green);
        assertThat(String.valueOf(replaced.getFirst().get("image"))).isEqualTo(files.get("products[0].image"));
        assertThat(String.valueOf(latestOnboarding(sponsor).get("avatar"))).isEqualTo(files.get("basics.avatar"));
        assertNothingUpdated();
    }

    @Test
    void anImportNeverChangesAReview() {
        // Not yet applied: the basics go to the application form, the page waits for the approval.
        String applicant = user(unique("s-q100-new"));
        String applicantToken = sponsorToken(applicant);
        Map<String, Object> plan = plan(run(ProfileTransferProcesses.CHECK, applicantToken, body("document",
            document(newHandle(), List.of(), false))));
        assertThat(plan).containsEntry("basics", "APPLY_FORM").containsEntry("page", "NOT_APPROVED");
        run(ProfileTransferProcesses.IMPORT, applicantToken, body("document", document(newHandle(), List.of(),
            false)));
        assertThat(query("SELECT 1 FROM qb_sponsor_version WHERE user_id = ?", applicant)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_sponsor_page WHERE user_id = ?", applicant)).isEmpty();

        // A page waiting for review is left alone: a save would withdraw the review.
        String sponsor = newSponsor(unique("s-q100-pending"));
        String token = sponsorToken(sponsor);
        String handle = newHandle();
        run(SponsorPageProcesses.SAVE, token, body("handle", handle, "language", "en", "headline", "Before"));
        Map<String, Object> submitted = run(SponsorPageProcesses.SUBMIT, token, body());
        assertThat(submitted).containsEntry("status", QbSponsorPages.PENDING);
        Map<String, Object> pending = plan(run(ProfileTransferProcesses.CHECK, token, body("document",
            document(handle, List.of(product(null, "緑茶", "绿茶", null)), false))));
        assertThat(pending).containsEntry("page", "PENDING").containsEntry("basics", "SAVE");
        run(ProfileTransferProcesses.IMPORT, token, body("document", document(handle,
            List.of(product(null, "緑茶", "绿茶", null)), false)));
        Map<String, Object> page = query("SELECT * FROM qb_sponsor_page WHERE user_id = ?", sponsor).getFirst();
        assertThat(page).containsEntry("status", QbSponsorPages.PENDING).containsEntry("headline", "Before")
            .containsEntry("approval_request_id", submitted.get("approvalRequestId"));
        assertThat(products(sponsor)).extracting(row -> row.get("name")).containsExactly("緑茶");
    }

    @Test
    void aBadPackageOrSomeoneElsesFileWritesNothing() {
        String sponsor = newSponsor(unique("s-q100-bad"));
        String token = sponsorToken(sponsor);
        assertThat(codes(refused(ProfileTransferProcesses.CHECK, token, body("document", body("format", "x")),
            422))).containsExactly(ProfilePackage.FORMAT_INVALID);

        // A file the import needs and was not given.
        Map<String, Object> document = document(newHandle(), List.of(product(null, "緑茶", "绿茶", "f3")), true);
        String avatar = upload(token, QbAccounts.AVATAR_POLICY);
        String cover = upload(token, QbSponsorPages.IMAGE_POLICY);
        assertThat(codes(refused(ProfileTransferProcesses.IMPORT, token, body("document", document, "files",
            body("basics.avatar", avatar, "page.cover", cover)), 422)))
            .containsExactly(ProfileTransferProcesses.FILE_MISSING);

        // Another sponsor's file: refused by the product's own save, and nothing else is written either.
        String other = sponsorToken(newSponsor(unique("s-q100-other")));
        Map<String, Object> refusal = refused(ProfileTransferProcesses.IMPORT, token, body("document", document,
            "files", body("basics.avatar", avatar, "page.cover", cover,
                "products[0].image", upload(other, QbSponsorPages.IMAGE_POLICY))), 422);
        assertThat(codes(refusal)).contains(AccountCodes.FILE_NOT_OWN);
        assertThat(products(sponsor)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_sponsor_page WHERE user_id = ?", sponsor)).isEmpty();

        // The same product twice.
        String productId = (String) run(SponsorProductProcesses.SAVE, token, body("name", "Tea", "price", "100",
            "currency", "JPY", "shopUrl", "https://shop.example/tea", "listed", true)).get("productId");
        List<Map<String, Object>> twice = new ArrayList<>(List.of(product(productId, "A", "A", null),
            product(productId, "B", "B", null)));
        assertThat(codes(refused(ProfileTransferProcesses.CHECK, token, body("document",
            document(newHandle(), twice, false)), 422))).containsExactly(ProfileTransferProcesses.PRODUCT_TWICE);
    }
}
