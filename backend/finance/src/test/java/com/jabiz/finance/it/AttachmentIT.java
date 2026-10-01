package com.jabiz.finance.it;

import com.jabiz.finance.gl.JournalAttachments;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supporting documents (FIN-GL-016): PDF, images and spreadsheets attach to an entry with their content hash; once the
 * entry is posted they are evidence: they neither go nor change, and the file cannot be deleted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AttachmentIT extends FinanceItSupport {

    private static boolean booksOpen;

    @BeforeEach
    void books() {
        if (!booksOpen) {
            openBooks();
            booksOpen = true;
        }
    }

    private String accountant() {
        return as("accountant", "fin.journal.prepare", "fin.journal.read", "fin.journal.attach");
    }

    static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** FIN-GL-016 acceptance 1. */
    @Test
    void documentsOfAPostedEntryAreEvidence() throws Exception {
        byte[] pdf = FileSamples.pdf();
        String pdfId = upload(accountant(), JournalEntities.SUPPORT_FILES, pdf, "bonus-memo.pdf", "application/pdf");
        byte[] csv = "employee,amount\nA,15000.00\n".getBytes(StandardCharsets.UTF_8);
        String sheetId = upload(accountant(), JournalEntities.SHEET_FILES, csv, "bonus.csv", "text/csv");
        String id = (String) ok(JournalProcesses.SAVE, accountant(), entry("2026-01-31", "Bonus accrual",
            List.of(line("6100", "500.00", null, null), line("2100", null, "500.00", null)))).get("journalId");

        Map<String, Object> attached = ok(JournalAttachments.ATTACH, accountant(),
            Map.of("journalId", id, "fileId", pdfId, "description", "Board memo"));
        assertThat(attached).containsEntry("sha256", sha256(pdf));
        Map<String, Object> sheet = ok(JournalAttachments.ATTACH, accountant(), Map.of("journalId", id,
            "sheetFileId", sheetId));
        // One file per attachment, and a spreadsheet is not a document of the other policy.
        assertThat(refused(JournalAttachments.ATTACH, accountant(), Map.of("journalId", id), 422))
            .isEqualTo(JournalEntities.ATTACHMENT_ONE_FILE);
        run(JournalAttachments.ATTACH, accountant(), Map.of("journalId", id, "fileId", sheetId))
            .expectStatus().isBadRequest();
        // Before posting, a document may go again.
        ok(JournalAttachments.DETACH, accountant(), Map.of("attachmentId", sheet.get("attachmentId")));
        assertThat(find(JournalEntities.ATTACHMENT_DATASET, "journalId", id)).singleElement()
            .satisfies(a -> assertThat(a).containsEntry("fileId", pdfId).containsEntry("sha256", sha256(pdf)));

        ok(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", id));
        assertThat(refused(JournalAttachments.DETACH, accountant(),
            Map.of("attachmentId", attached.get("attachmentId")), 422)).isEqualTo(JournalAttachments.POSTED_ENTRY);
        assertThat(refused(JournalAttachments.ATTACH, accountant(), Map.of("journalId", id, "sheetFileId", sheetId),
            422)).isEqualTo(JournalAttachments.POSTED_ENTRY);
        // The file it refers to stays, unchanged: its hash is the one recorded.
        run("FILE_DELETE", as("admin", "*"), Map.of("fileId", pdfId)).expectStatus().is4xxClientError();
        byte[] served = get("/api/files/" + pdfId + "/content", accountant()).expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(sha256(served)).isEqualTo(sha256(pdf));
        assertOnlyInserted("fi_journal_attachment_version");
    }

    /** The documents are the preparer's own, and do not change while the entry waits for approval. */
    @Test
    void onlyThePreparerAttachesTheirOwnUploadsToADraft() {
        String other = as("other", "fin.journal.prepare", "fin.journal.read", "fin.journal.attach");
        String theirs = upload(other, JournalEntities.SUPPORT_FILES, FileSamples.pdf(), "theirs.pdf",
            "application/pdf");
        String mine = upload(accountant(), JournalEntities.SUPPORT_FILES, FileSamples.pdf(), "mine.pdf",
            "application/pdf");
        String id = (String) ok(JournalProcesses.SAVE, accountant(), entry("2026-01-31", "Large accrual",
            List.of(line("6400", "20000.00", null, null), line("2100", null, "20000.00", null)))).get("journalId");

        assertThat(refused(JournalAttachments.ATTACH, accountant(), Map.of("journalId", id, "fileId", theirs), 422))
            .isEqualTo(JournalAttachments.NOT_UPLOADER);
        assertThat(refused(JournalAttachments.ATTACH, other, Map.of("journalId", id, "fileId", theirs), 422))
            .isEqualTo(JournalAttachments.NOT_PREPARER);
        Map<String, Object> attached = ok(JournalAttachments.ATTACH, accountant(),
            Map.of("journalId", id, "fileId", mine));

        // Submitted, it waits for approval: what the approver sees stays as it is.
        assertThat(ok(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", id)))
            .containsEntry("status", "SUBMITTED");
        assertThat(refused(JournalAttachments.DETACH, accountant(),
            Map.of("attachmentId", attached.get("attachmentId")), 422)).isEqualTo(JournalAttachments.LOCKED);
    }
}
