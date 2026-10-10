package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.test.TestTokens;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Content integration tests: two sponsors, an administrator, and the processes and templates they use. */
abstract class ContentItSupport extends QbItSupport {

    /** The seven tables of content, only ever inserted into. */
    static final List<String> TABLES = List.of("qb_quiz_version", "qb_material_version", "qb_material_image_version",
        "qb_question_version", "qb_option_version", "qb_quiz_version_version", "qb_version_file_version");

    String sponsorA() {
        return TestTokens.bearer(tokens, "sponsor-a", QbPermissions.CONTENT_WRITE, QbPermissions.CONTENT_FILE_READ);
    }

    String sponsorB() {
        return TestTokens.bearer(tokens, "sponsor-b", QbPermissions.CONTENT_WRITE, QbPermissions.CONTENT_FILE_READ);
    }

    /** A content administrator: reads everything, writes nothing. */
    String contentAdmin() {
        return TestTokens.bearer(tokens, "content-admin", QbPermissions.ADMIN_QUIZ_READ,
            QbPermissions.CONTENT_FILE_READ);
    }

    WebTestClient.ResponseSpec call(String process, String token, Object body) {
        return client.post().uri("/api/processes/" + process + "/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(body).exchange();
    }

    /** Runs a process that must succeed; returns its output. */
    @SuppressWarnings("unchecked")
    Map<String, Object> run(String process, String token, Map<String, ?> body) {
        var result = call(process, token, body).expectBody(MAP).returnResult();
        assertThat(result.getStatus().value()).as(process + " answered " + result.getResponseBody()).isEqualTo(200);
        return (Map<String, Object>) result.getResponseBody().get("output");
    }

    /** Runs a process that must be refused with {@code status}; returns the problem. */
    Map<String, Object> refused(String process, String token, Map<String, ?> body, int status) {
        var result = call(process, token, body).expectBody(MAP).returnResult();
        assertThat(result.getStatus().value()).as(process + " answered " + result.getResponseBody())
            .isEqualTo(status);
        return result.getResponseBody();
    }

    /** The rule codes of a refusal, in order. */
    @SuppressWarnings("unchecked")
    static List<Object> codes(Map<String, Object> problem) {
        List<Map<String, Object>> violations = (List<Map<String, Object>>) problem.get("violations");
        return violations == null ? List.of() : violations.stream().map(v -> v.get("ruleCode")).toList();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> violations(Map<String, Object> problem) {
        return (List<Map<String, Object>>) problem.get("violations");
    }

    static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    String newQuiz(String token, String title) {
        return (String) run("QB_QUIZ_SAVE", token, body("title", title)).get("quizId");
    }

    /** A question with two options, the first correct. */
    String question(String token, String quizId, String stem) {
        return (String) run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", stem, "options",
            List.of(body("text", "yes", "correct", true), body("text", "no")))).get("itemId");
    }

    /** Runs a template; returns its page. */
    Map<String, Object> template(String id, String token, Map<String, Object> request) {
        return client.post().uri("/api/queries/" + id).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(request).exchange().expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items(String id, String token, Map<String, Object> request) {
        return (List<Map<String, Object>>) template(id, token, request).get("items");
    }

    static final String BOUNDARY = "quizbuks-it-boundary-3c9e";

    /**
     * Uploads a file under a policy. The multipart body is built by hand: the client's own writer draws its boundary
     * from a blocking random source, which BlockHound would report.
     */
    WebTestClient.ResponseSpec upload(String policy, byte[] content, String fileName, String contentType,
        String token) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
            + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.post().uri("/api/files?policy=" + policy)
            .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(out.toByteArray()).exchange();
    }

    /** Uploads a file that must be accepted; returns what the platform answers. */
    Map<String, Object> uploaded(String policy, byte[] content, String fileName, String contentType) {
        return upload(policy, content, fileName, contentType, sponsorA()).expectStatus().isCreated()
            .expectBody(MAP).returnResult().getResponseBody();
    }

    String fileId(String policy, byte[] content, String fileName, String contentType) {
        return (String) uploaded(policy, content, fileName, contentType).get("fileId");
    }

    /** The table was only ever inserted into, and carries the platform's guard against updates and deletes. */
    static void assertOnlyInserted() {
        for (String table : TABLES) {
            assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
                .as(table).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
            assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
                + "AND c.relname = ? AND NOT t.tgisinternal", table)).as(table).isNotEmpty();
        }
    }
}
