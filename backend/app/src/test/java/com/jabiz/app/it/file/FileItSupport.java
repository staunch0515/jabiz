package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.app.commerce.SupplierDefinitions;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Uploads and reads files through the HTTP API, as the admin frontend does (docs/design/14-files.md section 5). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
abstract class FileItSupport extends PostgresIntegrationTest {

    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    String bearer(String actor, String... permissions) {
        return TestTokens.bearer(tokens, actor, permissions);
    }

    /** May upload and read product photos. */
    String photographer() {
        return bearer("it-photographer", "commerce.media.upload", "commerce.media.read");
    }

    static final String BOUNDARY = "jabiz-it-boundary-7d1f";

    /** One part of a hand-built multipart body; {@code fileName} null for a plain form field. */
    record Part(String name, String fileName, String contentType, byte[] content) {}

    /**
     * A {@code multipart/form-data} body built by hand: the client's own writer draws its boundary from a blocking
     * random source, which BlockHound would report.
     */
    static byte[] multipart(Part... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Part part : parts) {
            StringBuilder head = new StringBuilder("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(part.name()).append('"');
            if (part.fileName() != null) {
                head.append("; filename=\"").append(part.fileName()).append('"');
            }
            head.append("\r\n");
            if (part.contentType() != null) {
                head.append("Content-Type: ").append(part.contentType()).append("\r\n");
            }
            head.append("\r\n");
            out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
            out.writeBytes(part.content());
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    static MediaType multipartType() {
        return MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY);
    }

    WebTestClient.ResponseSpec upload(String policy, byte[] content, String fileName, String contentType,
        String authorization) {
        return uploadParts(policy, authorization, new Part("file", fileName, contentType, content));
    }

    WebTestClient.ResponseSpec uploadParts(String policy, String authorization, Part... parts) {
        WebTestClient.RequestBodySpec request = client.post().uri("/api/files?policy=" + policy)
            .contentType(multipartType());
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.bodyValue(multipart(parts)).exchange();
    }

    /** Uploads and returns the answer (201 expected). */
    Map<String, Object> uploaded(String policy, byte[] content, String fileName, String contentType,
        String authorization) {
        return upload(policy, content, fileName, contentType, authorization)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody();
    }

    WebTestClient.ResponseSpec get(String path, String authorization) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, authorization).exchange();
    }

    WebTestClient.ResponseSpec post(String path, String authorization, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    /** Keys of the stored objects (relative paths under the storage directory), upload leftovers excluded. */
    static List<String> storedKeys() {
        Path root = filesRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(root::relativize)
                .map(path -> path.toString().replace('\\', '/'))
                .filter(key -> !key.startsWith("."))
                .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Files left in the upload area. */
    static List<Path> uploadLeftovers() {
        Path area = filesRoot().resolve(".uploads");
        if (!Files.isDirectory(area)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(area)) {
            return walk.filter(path -> !path.equals(area)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] stored(String key) {
        try {
            return Files.readAllBytes(filesRoot().resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final String SUPPLIERS = "/api/datasets/" + SupplierDefinitions.DATASET + "/commit";

    String buyer() {
        return bearer("it-buyer", "commerce.supplier.read", "commerce.supplier.write", "commerce.document.upload",
            "commerce.document.read");
    }

    /** A supplier code no other test uses. */
    static String supplierCode() {
        return "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    static Map<String, Object> supplier(String code, Object contractFileId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("supplierCode", code);
        attributes.put("supplierName", "Supplier " + code);
        attributes.put("countryCode", "JP");
        attributes.put("leadTimeDays", 7);
        attributes.put("active", true);
        attributes.put("contractFileId", contractFileId);
        return attributes;
    }

    /** Commits changes to suppliers through the dataset API. */
    WebTestClient.ResponseSpec commitSuppliers(List<Map<String, Object>> changes) {
        return post(SUPPLIERS, buyer(), Map.of("changes", changes));
    }

    /** Creates a supplier and returns its id. */
    String createSupplier(Object contractFileId) {
        return (String) commitSuppliers(List.of(Map.of("action", "INSERT", "attributes",
                supplier(supplierCode(), contractFileId))))
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody().getFirst().get("id");
    }

    /** Uploads a contract and returns its file id. */
    String uploadContract() {
        return (String) uploaded(CommerceFiles.DOCUMENT, FileSamples.pdf(), "contract.pdf", "application/pdf",
            buyer()).get("fileId");
    }

    @SuppressWarnings("unchecked")
    static List<String> ruleCodes(Map<String, Object> problem) {
        return ((List<Map<String, Object>>) problem.get("violations")).stream()
            .map(v -> (String) v.get("ruleCode")).toList();
    }
}
