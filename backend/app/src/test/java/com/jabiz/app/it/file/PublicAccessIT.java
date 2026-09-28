package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.app.it.fixture.ItFileFixtures;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Public read access (ROADMAP phase 13c; docs/design/15-public-access.md; decision D17): anonymous visitors read the
 * public catalog and the files its rows show, and nothing else. Rows outside a public dataset's scope never appear,
 * whichever way they are asked for; columns outside its whitelist cannot be read even by physical name; files are
 * public exactly while a public row refers to them through a whitelisted field.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "jabiz.public.enabled=true")
class PublicAccessIT extends FileItSupport {

    private static final String CATALOG = "/api/public/queries/commerce.public.catalog";

    @Autowired
    AdvancedQueryExecutor executor;

    @Value("${jabiz.security.jwt.secret}")
    String secret;

    private String admin() {
        return bearer("it-admin", "*");
    }

    /** A prefix of product codes no other test uses. */
    private static String prefix() {
        return "PUB" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String product(String sku, int unitPrice, boolean active, String imageFileId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sku", sku);
        attributes.put("productName", "Product " + sku);
        attributes.put("unitPrice", unitPrice);
        attributes.put("active", active);
        attributes.put("imageFileId", imageFileId);
        return (String) post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes))))
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody().getFirst().get("id");
    }

    private WebTestClient.ResponseSpec anonymous(String uri) {
        return client.get().uri(uri).exchange();
    }

    private Map<String, Object> page(String uri) {
        return anonymous(uri).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static List<Object> skus(Map<String, Object> page) {
        return items(page).stream().map(item -> item.get("sku")).toList();
    }

    private String photo() {
        return (String) uploaded(CommerceFiles.IMAGE, FileSamples.jpeg(400, 300), "私の写真.jpg", "image/jpeg",
            photographer()).get("fileId");
    }

    @Test
    void rowsOutsideTheScopeNeverAppear() {
        String p = prefix();
        product(p + "-A", 100, true, null);
        product(p + "-B", 300, true, null);
        product(p + "-C", 200, false, null);

        Map<String, Object> all = page(CATALOG + "?p.q=" + p);
        assertThat(skus(all)).containsExactly(p + "-A", p + "-B");
        assertThat(all).containsEntry("total", 2).containsEntry("offset", 0);
        // Only the whitelisted columns leave the public dataset.
        assertThat(items(all).getFirst()).containsOnlyKeys("productId", "sku", "productName", "unitPrice",
            "imageFileId");

        // Through the outer filter and the count: the product off sale is not there.
        Map<String, Object> exact = page(CATALOG + "?filter=sku:eq:" + p + "-C");
        assertThat(items(exact)).isEmpty();
        assertThat(exact).containsEntry("total", 0);
        Map<String, Object> some = page(CATALOG + "?filter=sku:in:" + p + "-A," + p + "-C");
        assertThat(skus(some)).containsExactly(p + "-A");
        assertThat(some).containsEntry("total", 1);
        // Through the template's own parameters.
        assertThat(skus(page(CATALOG + "?p.q=" + p + "-C"))).isEmpty();

        Map<String, Object> filtered = page(CATALOG + "?p.q=" + p + "&filter=unitPrice:gte:150&sort=unitPrice:desc"
            + "&count=false&limit=1");
        assertThat(skus(filtered)).containsExactly(p + "-B");
        assertThat(filtered.get("total")).isNull();
        assertThat(filtered).containsEntry("limit", 1);
        assertThat(skus(page(CATALOG + "?p.q=" + p + "&p.maxPrice=150"))).containsExactly(p + "-A");
        // The page size never exceeds jabiz.public.max-limit.
        assertThat(page(CATALOG + "?p.q=" + p + "&limit=100000")).containsEntry("limit", 100);
    }

    @Test
    void aColumnOutsideTheWhitelistCannotBeReadEvenByItsPhysicalName() {
        String p = prefix();
        product(p + "-A", 100, true, null);
        // The template names the physical column of Product.active, which the public dataset does not show.
        AdvancedQueryDefinition sneaky = AdvancedQueryDefinition.define("it.public.sneaky", q -> q
            .publicAccess()
            .fromEntities(CommerceEntities.PRODUCT)
            .dataset(CommerceEntities.PRODUCT, CommerceEntities.PUBLIC_PRODUCT_DATASET)
            .returns(ProjectedField.inherit("active", CommerceEntities.PRODUCT, "active"))
            .sqlTemplate("SELECT p.active AS active FROM {{Product}} p"));

        assertThatThrownBy(() -> asTestRequest(executor.page(sneaky, Map.of(), null, List.of(), 0, 10, false))
            .block()).rootCause().hasMessageContaining("column p.active does not exist");

        // The same with a whitelisted physical name reads, so it is the projection that refuses.
        AdvancedQueryDefinition plain = AdvancedQueryDefinition.define("it.public.plain", q -> q
            .publicAccess()
            .fromEntities(CommerceEntities.PRODUCT)
            .dataset(CommerceEntities.PRODUCT, CommerceEntities.PUBLIC_PRODUCT_DATASET)
            .returns(ProjectedField.inherit("sku", CommerceEntities.PRODUCT, "sku"))
            .sqlTemplate("SELECT p.sku AS sku FROM {{Product}} p WHERE p.sku = '" + p + "-A'"));
        assertThat(asTestRequest(executor.page(plain, Map.of(), null, List.of(), 0, 10, false)).block().items())
            .hasSize(1);
    }

    @Test
    void privateAndUnknownTemplatesAreNotFound() {
        anonymous("/api/public/queries/commerce.stock_availability").expectStatus().isNotFound();
        // Not even an administrator's token makes a private template public.
        client.get().uri("/api/public/queries/commerce.stock_availability")
            .header(HttpHeaders.AUTHORIZATION, admin()).exchange().expectStatus().isNotFound();
        anonymous("/api/public/queries/no.such.query").expectStatus().isNotFound();
        anonymous("/api/public/elsewhere").expectStatus().isNotFound();
    }

    @Test
    void writeMethodsAreNotAllowed() {
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            client.method(method).uri(CATALOG).exchange().expectStatus().isEqualTo(405)
                .expectHeader().value(HttpHeaders.ALLOW, allow -> assertThat(allow).contains("GET").contains("HEAD"));
            client.method(method).uri("/api/public/files/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION,
                admin()).exchange().expectStatus().isEqualTo(405);
        }
    }

    @Test
    void credentialsSentAlongAreIgnored() {
        JwtService yesterday = new JwtService(Base64.getDecoder().decode(secret), Duration.ofMinutes(15),
            Clock.fixed(clock.instant().minus(Duration.ofDays(1)), ZoneOffset.UTC));
        String expired = "Bearer " + yesterday.issue(new Actor("it-gone", null, Set.of(), Set.of("*"))).token();
        // The token really is expired for the rest of the API.
        client.get().uri("/api/meta/entities").header(HttpHeaders.AUTHORIZATION, expired).exchange()
            .expectStatus().isUnauthorized();

        for (String authorization : List.of(expired, "Bearer not-a-token", "Basic aXQ6aXQ=")) {
            client.get().uri(CATALOG).header(HttpHeaders.AUTHORIZATION, authorization).exchange()
                .expectStatus().isOk();
        }
    }

    @Test
    void responsesAreCacheableAndRevalidated() {
        String p = prefix();
        product(p + "-A", 100, true, null);
        String uri = CATALOG + "?p.q=" + p;

        String etag = anonymous(uri).expectStatus().isOk()
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
            .expectHeader().valueEquals(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE)
            .expectHeader().value(HttpHeaders.ETAG, tag -> assertThat(tag).matches("\"[0-9a-f]{64}\""))
            .returnResult(String.class).getResponseHeaders().getETag();

        client.get().uri(uri).header(HttpHeaders.IF_NONE_MATCH, etag).exchange()
            .expectStatus().isNotModified()
            .expectHeader().valueEquals(HttpHeaders.ETAG, etag)
            .expectBody().isEmpty();
        client.get().uri(uri).header(HttpHeaders.IF_NONE_MATCH, "\"other\", W/" + etag).exchange()
            .expectStatus().isNotModified();
        client.get().uri(uri).header(HttpHeaders.IF_NONE_MATCH, "\"other\"").exchange().expectStatus().isOk();
        client.head().uri(uri).exchange().expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.ETAG, etag);

        // A change of the data is a new body, so a new tag.
        product(p + "-B", 200, true, null);
        client.get().uri(uri).header(HttpHeaders.IF_NONE_MATCH, etag).exchange().expectStatus().isOk();
    }

    @Test
    void badRequestsAreRejected() {
        anonymous(CATALOG + "?q=shoes").expectStatus().isBadRequest();
        anonymous(CATALOG + "?p.colour=red").expectStatus().isBadRequest();
        anonymous(CATALOG + "?p.maxPrice=cheap").expectStatus().isBadRequest();
        anonymous(CATALOG + "?filter=sku").expectStatus().isBadRequest();
        anonymous(CATALOG + "?filter=productName:eq:x").expectStatus().isBadRequest();
        anonymous(CATALOG + "?sort=imageFileId:asc").expectStatus().isBadRequest();
        anonymous(CATALOG + "?sort=sku:sideways").expectStatus().isBadRequest();
        anonymous(CATALOG + "?limit=many").expectStatus().isBadRequest();
        anonymous(CATALOG + "?offset=-1").expectStatus().isBadRequest();
        anonymous(CATALOG + "?count=maybe").expectStatus().isBadRequest();
        // Errors are never cached publicly.
        anonymous(CATALOG + "?q=shoes").expectHeader().value(HttpHeaders.CACHE_CONTROL,
            value -> assertThat(value).doesNotContain("public"));
    }

    @Test
    void readingIsNotAnOperation() {
        long before = count("SELECT count(*) AS n FROM op_process");
        anonymous(CATALOG).expectStatus().isOk();
        anonymous(CATALOG + "?count=false").expectStatus().isOk();
        assertThat(count("SELECT count(*) AS n FROM op_process")).isEqualTo(before);
    }

    @Test
    void signedInUsersRunPublicTemplatesWithoutPermissions() {
        client.post().uri("/api/queries/commerce.public.catalog").header(HttpHeaders.AUTHORIZATION,
            bearer("it-nobody")).exchange().expectStatus().isOk();
    }

    @Test
    void aFileIsPublicWhileAPublicRowShowsIt() {
        String p = prefix();
        String shown = photo();
        String hidden = photo();
        String loose = photo();
        product(p + "-A", 100, true, shown);
        product(p + "-B", 100, false, hidden);

        byte[] original = anonymous("/api/public/files/" + shown).expectStatus().isOk()
            .expectHeader().contentType("image/jpeg")
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
            .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
            .expectHeader().value(HttpHeaders.CONTENT_DISPOSITION, value -> assertThat(value)
                .startsWith("inline;").contains(shown + ".jpg").doesNotContain("%E7%A7%81"))
            .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(original).isNotEmpty();
        anonymous("/api/public/files/" + shown + "/w160").expectStatus().isOk()
            .expectHeader().value(HttpHeaders.CONTENT_DISPOSITION, value -> assertThat(value)
                .contains(shown + "-w160.jpg"));
        client.get().uri("/api/public/files/" + shown).header(HttpHeaders.RANGE, "bytes=0-9").exchange()
            .expectStatus().isEqualTo(206);
        anonymous("/api/public/files/" + shown + "/w1280").expectStatus().isNotFound();
        anonymous("/api/public/files/" + shown + "/..").expectStatus().is4xxClientError();

        // Only a product off sale refers to it, or nothing does: as if it did not exist.
        anonymous("/api/public/files/" + hidden).expectStatus().isNotFound();
        anonymous("/api/public/files/" + loose).expectStatus().isNotFound();
        anonymous("/api/public/files/" + UUID.randomUUID()).expectStatus().isNotFound();
        anonymous("/api/public/files/not-a-uuid").expectStatus().isNotFound();
    }

    @Test
    void onlyWhitelistedFileFieldsMakeAFilePublic() {
        String writer = bearer("it-clerk", "it.read", "it.write", "entity.write");
        String cover = photo();
        String draftCover = photo();
        String document = uploadContract();
        String shownId = "att-" + UUID.randomUUID();
        post("/api/entities/ItAttachment", writer, Map.of("attachmentId", shownId,
            "title", ItFileFixtures.PUBLIC_TITLE, "cover", cover, "document", document)).expectStatus().isCreated();
        post("/api/entities/ItAttachment", writer, Map.of("attachmentId", "att-" + UUID.randomUUID(),
            "title", "draft", "cover", draftCover)).expectStatus().isCreated();

        anonymous("/api/public/files/" + cover).expectStatus().isOk();
        // The document is on a public row, but its field is not in the whitelist.
        anonymous("/api/public/files/" + document).expectStatus().isNotFound();
        anonymous("/api/public/files/" + draftCover).expectStatus().isNotFound();

        // The public template over the same (ordinary) table shows the public row only.
        Map<String, Object> page = page("/api/public/queries/it.public.attachments?filter=attachmentId:eq:" + shownId);
        assertThat(items(page)).singleElement().isEqualTo(Map.of("attachmentId", shownId, "cover", cover));
        anonymous("/api/public/queries/it.public.attachments").expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "public, max-age=0");
    }

    @Test
    void aWithdrawnProductLeavesTheCatalogAndItsPhotoAtOnce() {
        String p = prefix();
        String image = photo();
        String productId = product(p + "-A", 100, true, image);
        assertThat(skus(page(CATALOG + "?p.q=" + p))).containsExactly(p + "-A");
        // Served, so the decision is remembered now.
        anonymous("/api/public/files/" + image).expectStatus().isOk();

        Map<String, Object> result = post("/api/processes/PRODUCT_WITHDRAW/latest", admin(),
            Map.of("productId", productId)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(result.get("output")).isEqualTo(Map.of("productId", productId, "sku", p + "-A",
            "withdrawn", true));

        assertThat(skus(page(CATALOG + "?p.q=" + p))).isEmpty();
        // The process forgot the remembered decision after its commit.
        anonymous("/api/public/files/" + image).expectStatus().isNotFound();
        // Withdrawing again changes nothing.
        post("/api/processes/PRODUCT_WITHDRAW/latest", admin(), Map.of("productId", productId))
            .expectStatus().isOk().expectBody(MAP).value(body -> assertThat(body.get("output"))
                .isEqualTo(Map.of("productId", productId, "sku", p + "-A", "withdrawn", false)));
    }

    @Test
    void withoutInvalidationTheDecisionIsRememberedForItsLifetime() {
        String p = prefix();
        String image = photo();
        String productId = product(p + "-A", 100, true, image);
        anonymous("/api/public/files/" + image).expectStatus().isOk();

        // Taken off sale through the dataset API, which does not invalidate: the photo stays reachable for the
        // decision lifetime (jabiz.public.file-decision-ttl), as the design accepts.
        post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "UPDATE", "id", productId, "version", 1,
                "attributes", Map.of("active", false))))).expectStatus().isOk();
        assertThat(skus(page(CATALOG + "?p.q=" + p))).isEmpty();
        anonymous("/api/public/files/" + image).expectStatus().isOk();
    }

    private static long count(String sql) {
        return ((Number) query(sql).getFirst().get("n")).longValue();
    }
}
