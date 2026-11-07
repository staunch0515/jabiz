package com.jabiz.app.it.security;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.runtime.file.FileKeys;
import com.jabiz.runtime.file.FileStore;
import com.jabiz.runtime.ops.RestoreVerifier;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.security.Base32;
import com.jabiz.security.MfaSecretCipher;
import com.jabiz.security.Totp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checking a restored copy (docs/design/13-observability-ops.md section 8; ROADMAP 17zv): the seal chain, the second
 * factors under the configured key, the stored files, the last operation against the target time and the
 * application's own templates. This class's schema stands in for the restored database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.ops.verify-restore.templates=it.verify_none, it.verify_rows, it.no_such_template")
class RestoreVerificationIT extends SecurityItSupport {

    private static final String PASSWORD = "correct horse battery";
    private static final String BOUNDARY = "jabiz-restore-boundary";

    @Autowired
    RestoreVerifier verifier;

    @Autowired
    ProcessExecutor processes;

    @Autowired
    FileStore files;

    @Autowired
    SqlTemplateRegistry templates;

    @Autowired
    AdvancedQueryExecutor queries;

    @Autowired
    StorageAdapterRegistry storages;

    private void enrolled(String name) {
        userWith(name, PASSWORD);
        String bearer = bearerOf(signIn(name, PASSWORD));
        String secret = (String) post("/api/auth/mfa/enroll", bearer, Map.of())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("secret");
        post("/api/auth/mfa/enroll/confirm", bearer, Map.of("code",
            Totp.code(Base32.decode(secret), Totp.step(clock.instant())))).expectStatus().isOk();
        clock.advance(Duration.ofSeconds(30));
    }

    private UUID uploadedDocument() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.pdf\"\r\n"
            + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(FileSamples.pdf());
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Map<String, Object> file = client.post().uri("/api/files?policy=" + CommerceFiles.DOCUMENT)
            .header(HttpHeaders.AUTHORIZATION, bearer("commerce.document.upload"))
            .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
            .bodyValue(body.toByteArray()).exchange()
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody();
        return UUID.fromString((String) file.get("fileId"));
    }

    private void warehouse() {
        String code = "R" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        post("/api/datasets/" + CommerceEntities.WAREHOUSE_DATASET + "/commit", bearer("*"), Map.of("changes",
            List.of(Map.of("action", "INSERT", "attributes", Map.of("warehouseCode", code, "warehouseName", code,
                "active", true))))).expectStatus().isOk();
    }

    private RestoreVerifier verifier(MfaSecretCipher mfa, String targetTime) {
        return new RestoreVerifier(processes, mfa, files, templates, queries, storages, "default", "", targetTime,
            clock);
    }

    private static RestoreVerifier.Item item(RestoreVerifier.Report report, String name) {
        return report.items().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aSoundCopyPassesAndEachDefectIsReported() throws Exception {
        enrolled(unique("restored"));
        UUID file = uploadedDocument();
        warehouse();

        RestoreVerifier.Report report = verifier.verify().block();
        assertThat(report.items()).extracting(RestoreVerifier.Item::name).containsExactly("last-operation", "integrity",
            "mfa-secrets", "files", "template it.verify_none", "template it.verify_rows",
            "template it.no_such_template");
        assertThat(item(report, "integrity").passed()).as(item(report, "integrity").detail()).isTrue();
        assertThat(item(report, "mfa-secrets")).satisfies(i -> {
            assertThat(i.passed()).isTrue();
            assertThat(i.detail()).startsWith("1 second factors, 0 cannot be opened");
        });
        assertThat(item(report, "files")).satisfies(i -> {
            assertThat(i.passed()).isTrue();
            assertThat(i.detail()).startsWith("1 files, 0 objects missing");
        });
        assertThat(item(report, "last-operation").passed()).isTrue();
        assertThat(item(report, "template it.verify_none")).extracting(RestoreVerifier.Item::passed,
            RestoreVerifier.Item::detail).containsExactly(true, "no rows");
        assertThat(item(report, "template it.verify_rows")).extracting(RestoreVerifier.Item::passed,
            RestoreVerifier.Item::detail).containsExactly(false, "1 rows");
        assertThat(item(report, "template it.no_such_template").passed()).isFalse();
        assertThat(report.passed()).isFalse();
        // Counts only: no secret, user, file name or warehouse code in the report.
        assertThat(report.toString()).doesNotContain(file.toString(), "a.pdf", PASSWORD);

        // A file whose object did not come back with the restore.
        Files.delete(filesRoot().resolve(FileKeys.key(file, FileKeys.ORIGINAL)));
        assertThat(item(verifier.verify().block(), "files")).satisfies(i -> {
            assertThat(i.passed()).isFalse();
            assertThat(i.detail()).startsWith("1 files, 1 objects missing");
        });

        // Started with another JABIZ_MFA_KEY than production's.
        MfaSecretCipher other = new MfaSecretCipher(new byte[32]);
        assertThat(item(verifier(other, "").verify().block(), "mfa-secrets")).satisfies(i -> {
            assertThat(i.passed()).isFalse();
            assertThat(i.detail()).contains("1 cannot be opened").contains(other.keyId());
        });
    }

    @Test
    void theVerificationsOwnOperationsAreNotPartOfTheCopy() {
        clock.advance(Duration.ofHours(2));
        warehouse();
        Instant target = clock.instant().plusSeconds(10);
        // The verification runs later than the target, and INTEGRITY_VERIFY records an operation at that time.
        clock.advance(Duration.ofMinutes(5));
        RestoreVerifier atTarget = verifier(new MfaSecretCipher(new byte[32]), target.toString());
        assertThat(item(atTarget.verify().block(), "last-operation").passed()).isTrue();
        assertThat(item(atTarget.verify().block(), "last-operation").passed()).as("run again on the same copy")
            .isTrue();
    }

    @Test
    void theLastOperationMustNotPassTheTargetTime() {
        // Later than anything the other tests of this class (same schema) did.
        clock.advance(Duration.ofHours(4));
        warehouse();
        MfaSecretCipher any = new MfaSecretCipher(new byte[32]);
        Instant now = clock.instant();
        assertThat(item(verifier(any, now.plusSeconds(90).toString()).verify().block(), "last-operation"))
            .satisfies(i -> {
                assertThat(i.passed()).isTrue();
                assertThat(i.detail()).contains("90 s before the target time");
            });
        assertThat(item(verifier(any, now.minusSeconds(1).toString()).verify().block(), "last-operation"))
            .satisfies(i -> {
                assertThat(i.passed()).isFalse();
                assertThat(i.detail()).contains("restored past it");
            });
    }

}
