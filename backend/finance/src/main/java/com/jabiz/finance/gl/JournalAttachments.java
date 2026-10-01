package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.FileEntities;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.finance.gl.JournalEntities.ATTACHMENT;
import static com.jabiz.finance.gl.JournalEntities.ATTACHMENT_DATASET;
import static com.jabiz.finance.gl.JournalEntities.JOURNAL_DATASET;
import static com.jabiz.finance.gl.JournalEntities.POSTED;

/**
 * Supporting documents of journal entries (FIN-GL-016): {@code FIN_JOURNAL_ATTACH} links an uploaded file (a
 * document or image of {@value JournalEntities#SUPPORT_FILES}, or a spreadsheet of
 * {@value JournalEntities#SHEET_FILES}) to an entry and keeps the file's SHA-256 as it was then;
 * {@code FIN_JOURNAL_DETACH} removes one. Neither works on a posted entry: its documents are evidence and never
 * change, and while an attachment refers to a file the platform keeps the file from being deleted.
 */
public final class JournalAttachments {

    public static final String ATTACH = "FIN_JOURNAL_ATTACH";
    public static final String DETACH = "FIN_JOURNAL_DETACH";

    public static final String POSTED_ENTRY = "FIN_ATTACHMENT_POSTED";

    /** @param fileId a document or image; @param sheetFileId or a spreadsheet: exactly one of the two */
    public record AttachInput(@NotNull UUID journalId, UUID fileId, UUID sheetFileId,
        @Size(max = 200) String description) {}

    public record AttachmentId(@NotNull UUID attachmentId) {}

    public record AttachmentOutput(String attachmentId, String journalId, String sha256) {}

    static final String INPUT = "input";
    static final String JOURNAL_ID = "journalId";
    static final String JOURNAL_KEY = "journal";
    static final String FILE_ID = "fileId";
    static final String FILE = "file";
    static final String ATTACHMENT_ID = "attachmentId";
    static final String ATTACHMENT_KEY = "attachment";
    static final String OUTPUT = "output";

    public static final ProcessDefinition<AttachInput, AttachmentOutput, ProcessContext> ATTACH_PROCESS =
        ProcessDefinition.define(ATTACH, 1, AttachInput.class, AttachmentOutput.class, ProcessContext.class,
            pb -> pb
                .description("Attaches an uploaded supporting document to a journal entry that is not posted.")
                .permissions(FinancePermissions.JOURNAL_ATTACH)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(JOURNAL_ID, input.journalId());
                    UUID file = input.fileId() != null ? input.fileId() : input.sheetFileId();
                    if (file != null) {
                        ctx.put(FILE_ID, file);
                    }
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, AttachmentOutput.class))
                .step("Load the entry", LoadEntity.by(JOURNAL_DATASET, JOURNAL_ID, JOURNAL_KEY))
                .step("Load the file", QueryEntities.of(FileEntities.DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("fileId", ctx.contains(FILE_ID) ? List.of(ctx.get(FILE_ID))
                        : List.of())).limit(1).build(), FILE))
                .compute("Attach it", (metadata, ctx) -> {
                    AttachInput input = ctx.get(INPUT, AttachInput.class);
                    EntityInstance journal = ctx.get(JOURNAL_KEY, EntityInstance.class);
                    if (POSTED.equals(journal.get("status"))) {
                        ctx.reject(new Violation("journalId", POSTED_ENTRY,
                            "The supporting documents of a posted entry do not change", Map.of()));
                        return;
                    }
                    List<EntityInstance> files = AccountProcesses.list(ctx, FILE);
                    if ((input.fileId() == null) == (input.sheetFileId() == null) || files.isEmpty()) {
                        ctx.reject(new Violation("fileId", JournalEntities.ATTACHMENT_ONE_FILE,
                            "An attachment is one uploaded document or one spreadsheet", Map.of()));
                        return;
                    }
                    EntityInstance file = files.getFirst();
                    Map<String, Object> attachment = new LinkedHashMap<>();
                    attachment.put("journalId", journal.id());
                    attachment.put("fileId", input.fileId());
                    attachment.put("sheetFileId", input.sheetFileId());
                    attachment.put("sha256", file.get("sha256"));
                    attachment.put("description", input.description() == null ? null : input.description().trim());
                    Object id = ctx.changes().insert(ATTACHMENT, attachment);
                    ctx.put(OUTPUT, new AttachmentOutput(String.valueOf(id), String.valueOf(journal.id()),
                        file.get("sha256")));
                }));

    public static final ProcessDefinition<AttachmentId, AttachmentOutput, ProcessContext> DETACH_PROCESS =
        ProcessDefinition.define(DETACH, 1, AttachmentId.class, AttachmentOutput.class, ProcessContext.class,
            pb -> pb
                .description("Removes a supporting document from a journal entry that is not posted.")
                .permissions(FinancePermissions.JOURNAL_ATTACH)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(ATTACHMENT_ID, input.attachmentId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, AttachmentOutput.class))
                .step("Load the attachment", LoadEntity.by(ATTACHMENT_DATASET, ATTACHMENT_ID, ATTACHMENT_KEY))
                .compute("Find its entry", (metadata, ctx) -> ctx.put(JOURNAL_ID,
                    ctx.get(ATTACHMENT_KEY, EntityInstance.class).get("journalId")))
                .step("Load the entry", LoadEntity.by(JOURNAL_DATASET, JOURNAL_ID, JOURNAL_KEY))
                .compute("Remove it", (metadata, ctx) -> {
                    EntityInstance attachment = ctx.get(ATTACHMENT_KEY, EntityInstance.class);
                    EntityInstance journal = ctx.get(JOURNAL_KEY, EntityInstance.class);
                    if (POSTED.equals(journal.get("status"))) {
                        ctx.reject(new Violation("attachmentId", POSTED_ENTRY,
                            "The supporting documents of a posted entry do not change", Map.of()));
                        return;
                    }
                    ctx.changes().delete(ATTACHMENT, attachment.id(), attachment.version());
                    ctx.put(OUTPUT, new AttachmentOutput(String.valueOf(attachment.id()),
                        String.valueOf(journal.id()), attachment.get("sha256")));
                }));

    private JournalAttachments() {}
}
