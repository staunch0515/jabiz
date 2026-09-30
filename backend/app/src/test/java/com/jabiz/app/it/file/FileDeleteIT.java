package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.runtime.file.FileKeys;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Deleting files (docs/design/14-files.md section 6; ROADMAP 13b acceptance 3 and 4): a file current or scheduled
 * data refers to cannot be deleted; history does not count; the row goes with the transaction and the content right
 * after; every upload and deletion is an operation whose summary holds no file name.
 */
class FileDeleteIT extends FileItSupport {

    private static final String DELETE = "/api/processes/FILE_DELETE/latest";

    private String deleter() {
        return bearer("it-deleter", "file.delete");
    }

    private WebTestClient.ResponseSpec delete(String fileId, String authorization) {
        return post(DELETE, authorization, Map.of("fileId", fileId));
    }

    private static boolean stored(UUID fileId) {
        String prefix = FileKeys.prefix(fileId);
        return storedKeys().stream().anyMatch(key -> key.startsWith(prefix + "/"));
    }

    private static long rows(String fileId) {
        return (Long) query("SELECT count(*) AS n FROM sys_file WHERE file_id = ?", UUID.fromString(fileId))
            .getFirst().get("n");
    }

    @Test
    void aFileInCurrentUseCannotBeDeleted() {
        String contract = uploadContract();
        createSupplier(contract);
        Map<String, Object> problem = delete(contract, deleter()).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(problem)).containsExactly("FILE_IN_USE");
        assertThat(problem.get("violations").toString()).contains("Supplier.contractFileId");
        assertThat(rows(contract)).isOne();
        assertThat(stored(UUID.fromString(contract))).isTrue();
    }

    @Test
    void aFileAScheduledVersionWillUseCannotBeDeleted() {
        String contract = uploadContract();
        String supplierId = createSupplier(null);
        commitSuppliers(List.of(Map.of("action", "UPDATE", "id", supplierId, "version", 1,
            "attributes", Map.of("contractFileId", contract), "effectiveTime", START.plus(Duration.ofDays(30)))))
            .expectStatus().isOk();
        delete(contract, deleter()).expectStatus().isEqualTo(422);
    }

    @Test
    void anOrdinaryEntityBlocksTooButHistoryDoesNot() {
        String contract = uploadContract();
        String attachment = "att-" + UUID.randomUUID();
        post("/api/entities/ItAttachment", bearer("it-clerk", "it.read", "it.write", "entity.write"),
            Map.of("attachmentId", attachment, "title", "x", "document", contract)).expectStatus().isCreated();
        Map<String, Object> problem = delete(contract, deleter()).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(problem.get("violations").toString()).contains("ItAttachment.document");

        String old = uploadContract();
        String supplierId = createSupplier(old);
        commitSuppliers(List.of(Map.of("action", "UPDATE", "id", supplierId, "version", 1,
            "attributes", Map.of("contractFileId", uploadContract())))).expectStatus().isOk();
        // Only the history refers to the old contract now.
        delete(old, deleter()).expectStatus().isOk();
        assertThat(rows(old)).isZero();
        await().atMost(Duration.ofSeconds(10)).until(() -> !stored(UUID.fromString(old)));
    }

    @Test
    void deletingNeedsItsPermissionAndAnExistingFile() {
        String contract = uploadContract();
        delete(contract, bearer("it-reader", "file.read")).expectStatus().isForbidden();
        delete(UUID.randomUUID().toString(), deleter()).expectStatus().isNotFound();
        assertThat(rows(contract)).isOne();
    }

    @Test
    void aBusinessProcessClearsTheReferenceAndDeletesTheFileInOneOperation() {
        String contract = uploadContract();
        String supplierId = createSupplier(contract);

        Map<String, Object> response = post("/api/processes/" + CommerceFiles.CONTRACT_REMOVE + "/latest", buyer(),
            Map.of("supplierId", supplierId)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();

        assertThat(response.get("output").toString()).contains(contract);
        assertThat(rows(contract)).isZero();
        await().atMost(Duration.ofSeconds(10)).until(() -> !stored(UUID.fromString(contract)));
        long seq = ((Number) response.get("processSeqId")).longValue();
        assertThat(query("SELECT process_name FROM op_process WHERE parent_seq_id = ?", seq))
            .extracting(row -> row.get("process_name")).containsExactly("FILE_DELETE");
        // Without a contract there is nothing to delete.
        post("/api/processes/" + CommerceFiles.CONTRACT_REMOVE + "/latest", buyer(), Map.of("supplierId", supplierId))
            .expectStatus().isOk();
    }

    @Test
    void uploadsAndDeletionsAreOperationsWithoutFileNames() {
        String name = "山田花子_passport.pdf";
        String fileId = (String) uploaded(CommerceFiles.DOCUMENT, FileSamples.pdf(), name,
            "application/pdf", buyer()).get("fileId");
        delete(fileId, deleter()).expectStatus().isOk();

        List<Map<String, Object>> operations = query("SELECT process_name, actor_id, input_summary::text AS input_summary FROM op_process "
            + "WHERE input_summary::text LIKE ? ORDER BY process_seq_id", "%" + fileId + "%");
        assertThat(operations).extracting(row -> row.get("process_name")).containsExactly("FILE_REGISTER",
            "FILE_DELETE");
        assertThat(operations).extracting(row -> row.get("actor_id")).containsExactly("it-buyer", "it-deleter");
        assertThat(operations).allSatisfy(row -> assertThat((String) row.get("input_summary"))
            .doesNotContain("山田").doesNotContain("passport"));
        assertThat((String) operations.getFirst().get("input_summary")).contains("commerce.document");

        // The audit trail tells that a file was registered and deleted, and which one, but not its name
        // (docs/design/21-audit-retention.md section 1.2).
        List<Map<String, Object>> audit = query("SELECT action, changes FROM sys_audit_record WHERE entity_id = ? "
            + "ORDER BY record_no", fileId);
        assertThat(audit).extracting(row -> row.get("action")).containsExactly("INSERT", "DELETE");
        assertThat(audit).allSatisfy(row -> assertThat(String.valueOf(row.get("changes")))
            .doesNotContain("山田").doesNotContain("passport").contains("\"originalName\"").contains("***"));
    }

    @Test
    void aFailedRegistrationLeavesNoObjects() {
        List<String> before = storedKeys();
        // The uploader's id does not fit SysFile.uploadedBy: recording the file fails after its content is stored.
        String tooLong = bearer("x".repeat(80), "commerce.document.upload");
        upload(CommerceFiles.DOCUMENT, FileSamples.pdf(), "a.pdf", "application/pdf", tooLong)
            .expectStatus().isBadRequest();
        assertThat(storedKeys()).isEqualTo(before);
        assertThat(uploadLeftovers()).isEmpty();
    }
}
