package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writing {@code jabiz.file} fields (docs/design/14-files.md section 4): a new value must be an existing file of the
 * field's policy, on every write path (dataset API and generic entity processes; temporal and ordinary entities,
 * the latter being {@code ItAttachment} of {@code ItFileFixtures}).
 */
class FileFieldIT extends FileItSupport {

    private String attachments() {
        return bearer("it-clerk", "it.read", "it.write", "entity.write");
    }

    @Test
    void temporalEntitiesAcceptOnlyExistingFilesOfTheirPolicy() {
        String contract = uploadContract();
        String photo = (String) uploaded(CommerceFiles.IMAGE, FileSamples.jpeg(20, 20), "p.jpg", "image/jpeg",
            photographer()).get("fileId");

        Map<String, Object> missing = commitSuppliers(List.of(Map.of("action", "INSERT", "attributes",
            supplier(supplierCode(), UUID.randomUUID().toString()))))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(missing)).containsExactly("FILE_NOT_FOUND");

        Map<String, Object> wrongPolicy = commitSuppliers(List.of(Map.of("action", "INSERT", "attributes",
            supplier(supplierCode(), photo))))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(wrongPolicy)).containsExactly("FILE_POLICY_MISMATCH");

        Map<String, Object> notAnId = commitSuppliers(List.of(Map.of("action", "INSERT", "attributes",
            supplier(supplierCode(), "contract.pdf"))))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(notAnId)).containsExactly("INVALID_VALUE");

        String supplierId = createSupplier(contract);
        assertThat(query("SELECT contract_file_id FROM supplier_version WHERE supplier_id = ?::uuid",
            supplierId).getFirst().get("contract_file_id")).isEqualTo(UUID.fromString(contract));

        // An update is checked where it changes the file.
        Map<String, Object> update = commitSuppliers(List.of(Map.of("action", "UPDATE", "id", supplierId,
            "version", 1, "attributes", Map.of("contractFileId", photo))))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(update)).containsExactly("FILE_POLICY_MISMATCH");
    }

    @Test
    void ordinaryEntitiesAreCheckedToo() {
        String contract = uploadContract();
        Map<String, Object> missing = post("/api/entities/ItAttachment", attachments(), Map.of(
            "attachmentId", "att-" + UUID.randomUUID(), "title", "x", "document", UUID.randomUUID().toString()))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(missing)).containsExactly("FILE_NOT_FOUND");

        String id = "att-" + UUID.randomUUID();
        post("/api/entities/ItAttachment", attachments(), Map.of("attachmentId", id, "title", "x",
            "document", contract)).expectStatus().isCreated();

        // A value the update does not change is not checked again: even a file gone behind the platform's back
        // does not block other fields.
        execute("DELETE FROM sys_file WHERE file_id = ?", UUID.fromString(contract));
        client.patch().uri("/api/entities/ItAttachment/" + id)
            .header(HttpHeaders.AUTHORIZATION, attachments())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("version", 1, "attributes", Map.of("title", "renamed", "document", contract)))
            .exchange().expectStatus().isOk();
        Map<String, Object> changed = client.patch().uri("/api/entities/ItAttachment/" + id)
            .header(HttpHeaders.AUTHORIZATION, attachments())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("version", 2, "attributes", Map.of("document", UUID.randomUUID().toString())))
            .exchange().expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(changed)).containsExactly("FILE_NOT_FOUND");
    }
}
