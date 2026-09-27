package com.jabiz.culture.it;

import com.jabiz.culture.Culture;
import com.jabiz.culture.CultureEntities;
import com.jabiz.culture.CultureSetup;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calls the HTTP API as the admin frontend does, with real access tokens: editors (the {@code CURATOR} role's
 * permissions), correspondents (the {@code CORRESPONDENT} role's) and an administrator. Every test class gets its own
 * schema; {@code CULTURE_SETUP} runs before each test (it is idempotent), so the switches exist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
abstract class CultureItSupport extends PostgresIntegrationTest {

    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    WebTestClient client;

    @BeforeEach
    void setUpCulture() {
        client = WebTestClient.bindToApplicationContext(context).build();
        run(CultureSetup.SETUP, admin(), Map.of()).expectStatus().isOk();
    }

    // Callers.

    String admin() {
        return TestTokens.bearer(tokens, "cu-admin", "*");
    }

    String curator() {
        return TestTokens.bearer(tokens, "cu-curator", CultureSetup.CURATOR_PERMISSIONS.toArray(String[]::new));
    }

    String correspondent(String account) {
        return TestTokens.bearer(tokens, account, CultureSetup.CORRESPONDENT_PERMISSIONS.toArray(String[]::new));
    }

    // HTTP.

    WebTestClient.ResponseSpec post(String path, String bearer, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    WebTestClient.ResponseSpec get(String path, String bearer) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).exchange();
    }

    WebTestClient.ResponseSpec commit(String dataset, String bearer, Map<String, Object> change) {
        return post("/api/datasets/" + dataset + "/commit", bearer, Map.of("changes", List.of(change)));
    }

    static Map<String, Object> insert(Map<String, Object> attributes) {
        return Map.of("action", "INSERT", "attributes", attributes);
    }

    static Map<String, Object> update(String id, long version, Map<String, Object> attributes) {
        return Map.of("action", "UPDATE", "id", id, "version", version, "attributes", attributes);
    }

    /** Inserts through the dataset and returns the new key. */
    String create(String dataset, String bearer, Map<String, Object> attributes) {
        return (String) commit(dataset, bearer, insert(attributes)).expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody().getFirst().get("id");
    }

    /** The row as the dataset shows it: key, version and attributes. */
    Map<String, Object> read(String dataset, String id, String bearer) {
        return get("/api/datasets/" + dataset + "/entities/" + id, bearer).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> attributes(String dataset, String id) {
        return (Map<String, Object>) read(dataset, id, admin()).get("attributes");
    }

    long version(String dataset, String id) {
        return ((Number) read(dataset, id, admin()).get("version")).longValue();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> query(String dataset, String bearer, List<Map<String, Object>> filters) {
        Map<String, Object> page = post("/api/datasets/" + dataset + "/query", bearer,
            Map.of("filters", filters, "limit", 100)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    WebTestClient.ResponseSpec run(String process, String bearer, Map<String, Object> input) {
        return post("/api/processes/" + process + "/latest", bearer, input);
    }

    /** Runs a process that must succeed and returns its output. */
    @SuppressWarnings("unchecked")
    Map<String, Object> ok(String process, String bearer, Map<String, Object> input) {
        return (Map<String, Object>) run(process, bearer, input).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody().get("output");
    }

    /** Runs a process that must be refused with {@code status}; returns the violations as field:code. */
    List<String> refused(String process, String bearer, Map<String, Object> input, int status) {
        return codes(run(process, bearer, input).expectStatus().isEqualTo(status).expectBody(MAP).returnResult()
            .getResponseBody());
    }

    @SuppressWarnings("unchecked")
    static List<String> codes(Map<String, Object> problem) {
        List<Map<String, Object>> violations = (List<Map<String, Object>>) problem.get("violations");
        return violations == null ? List.of() : violations.stream()
            .map(v -> v.get("field") + ":" + v.get("ruleCode")).toList();
    }

    void setSwitch(String key, boolean value) {
        ok("PARAM_SET", admin(), Map.of("key", key, "value", value));
    }

    // Files.

    private static final String BOUNDARY = "culture-it-boundary-3c9e";

    /** Uploads a file and returns its key; a hand-built multipart body (the client's writer blocks, BlockHound). */
    String upload(String policy, byte[] content, String fileName, String contentType, String bearer) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
            + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Map<String, Object> answer = client.post().uri("/api/files?policy=" + policy)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
            .bodyValue(body.toByteArray()).exchange()
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody();
        return (String) answer.get("fileId");
    }

    String photo(String bearer) {
        return upload(CultureEntities.IMAGE_POLICY, FileSamples.jpeg(64, 48), "photo.jpg", "image/jpeg", bearer);
    }

    String audio(String bearer) {
        return upload(CultureEntities.AUDIO_POLICY, FileSamples.mp3(), "voice.mp3", "audio/mpeg", bearer);
    }

    String consentScan() {
        return upload(CultureEntities.CONSENT_DOC_POLICY, FileSamples.pdf(), "consent.pdf", "application/pdf",
            curator());
    }

    // The content everybody needs.

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static Map<String, Object> en(String text) {
        return Map.of("en", text);
    }

    String location(String name) {
        return create(Culture.dataset(Culture.LOCATION), curator(), Map.of("slug", unique("place"),
            "name", en(name), "visible", true));
    }

    String theme(String title) {
        return create(Culture.dataset(Culture.THEME), curator(), Map.of("slug", unique("theme"),
            "title", en(title), "question", en("What makes somewhere feel like " + title + "?"), "visible", true));
    }

    /** A participant as a draft, with their login account when given. */
    String participant(String location, String account, boolean adult) {
        Map<String, Object> attributes = new HashMap<>(Map.of("slug", unique("person"), "displayName", "Aiko",
            "locationId", location, "adult", adult));
        if (account != null) {
            attributes.put("accountActorId", account);
        }
        return create(Culture.dataset(Culture.PARTICIPANT), curator(), attributes);
    }

    String consent(String participant, String party, boolean photo, boolean video, boolean voice) {
        return create(Culture.dataset(Culture.CONSENT), curator(), Map.of("participantId", participant,
            "party", party, "coversPhoto", photo, "coversVideo", video, "coversVoice", voice,
            "signedOn", "2026-01-10T00:00:00Z"));
    }

    /** An adult participant with full consent, active. */
    String activeParticipant(String location, String account) {
        String participant = participant(location, account, true);
        consent(participant, "PARTICIPANT", true, true, true);
        ok("CULTURE_PARTICIPANT_ACTIVATE", curator(), Map.of("participantId", participant));
        return participant;
    }

    /** Attributes of a story that passes the publish check once it has a theme and a perspective. */
    Map<String, Object> storyAttributes(String bearer) {
        Map<String, Object> story = new LinkedHashMap<>();
        story.put("slug", unique("story"));
        story.put("title", en("What does home mean to us?"));
        story.put("summary", en("A look at what makes a place feel like home."));
        story.put("mediaType", "ARTICLE");
        story.put("thumbnailFileId", photo(bearer));
        story.put("thumbnailAlt", en("A kitchen table set for dinner"));
        return story;
    }

    String link(String story, String theme) {
        return create(Culture.dataset(Culture.STORY_THEME), curator(), Map.of("storyId", story, "themeId", theme));
    }

    Map<String, Object> contribution(String story, String participant) {
        return new HashMap<>(Map.of("storyId", story, "participantId", participant, "text", en("Home is noise.")));
    }

    static List<Map<String, Object>> eq(String field, Object value) {
        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(Map.of("field", field, "op", "eq", "value", value));
        return filters;
    }
}
