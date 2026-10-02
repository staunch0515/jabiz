package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.jabiz.finance.gl.AccountProcesses.list;

/**
 * {@code FIN_JOURNAL_IMPORT} (FIN-GL-019): one entry of an imported file, saved as a draft (source {@code IMPORT})
 * and, unless asked not to, submitted at once: the same checks, numbering and approval rules as an entry typed by
 * hand, so a refused line refuses the entry and, through the import, the whole file. Each document of the source is
 * imported once.
 */
public final class JournalImportProcesses {

    public static final String IMPORT_ENTRY = "FIN_JOURNAL_IMPORT";
    public static final String IMPORTED_ALREADY = "FIN_JOURNAL_IMPORTED_ALREADY";

    /**
     * @param externalRef the document number in the source, e.g. the legacy entry number
     * @param submit      whether to submit it at once; yes when absent
     */
    public record ImportedEntry(@NotBlank @Size(max = 90) String externalRef, @NotNull LocalDate postingDate,
        LocalDate documentDate, @NotBlank @Size(max = 500) String description,
        @NotNull @Size(max = JournalValidator.MAX_LINES) List<JournalProcesses.@Valid @NotNull LineInput> lines,
        Boolean submit) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String FOUND = "found";
    static final String SUBMIT_INPUT = "submitInput";
    static final String SUBMITTED = "submitted";

    public static final ProcessDefinition<ImportedEntry, JournalProcesses.JournalOutput, ProcessContext>
        IMPORT_PROCESS = ProcessDefinition.define(IMPORT_ENTRY, 1, ImportedEntry.class,
            JournalProcesses.JournalOutput.class, ProcessContext.class, pb -> pb
                .description("Saves an imported journal entry and submits it, as if it had been typed.")
                .permissions(FinancePermissions.JOURNAL_PREPARE)
                .contextFactory(AccountProcesses::withInput)
                .outputMapper(ctx -> ctx.contains(SUBMITTED)
                    ? ctx.get(SUBMITTED, JournalProcesses.JournalOutput.class)
                    : ctx.get(OUTPUT, JournalProcesses.JournalOutput.class))
                .step("Look for the document", QueryEntities.of(JournalEntities.JOURNAL_DATASET,
                    ctx -> JournalProcesses.byExternalRef(JournalEntities.IMPORT,
                        input(ctx).externalRef().trim()), FOUND))
                .compute("Save the draft", (metadata, ctx) -> draft(ctx))
                .step("Save", SaveChanges.now())
                .step("Submit it", CallProcess.when(ctx -> ctx.contains(SUBMIT_INPUT), JournalProcesses.SUBMIT, 1,
                    ctx -> ctx.get(SUBMIT_INPUT), SUBMITTED)));

    static void draft(ProcessContext ctx) {
        ImportedEntry input = input(ctx);
        String ref = input.externalRef().trim();
        if (!list(ctx, FOUND).isEmpty()) {
            EntityInstance found = list(ctx, FOUND).getFirst();
            ctx.reject(new Violation("externalRef", IMPORTED_ALREADY, "Document " + ref + " was imported already"
                + (found.get("journalNo") == null ? "" : " as " + found.get("journalNo")), Map.of("externalRef", ref)));
            return;
        }
        for (Violation problem : JournalProcesses.dollarsOnly(input.lines())) {
            ctx.reject(problem);
        }
        List<JournalValidator.Line> lines = input.lines().stream().map(JournalProcesses::line).toList();
        for (Violation problem : JournalValidator.checkLines(lines)) {
            ctx.reject(problem);
        }
        if (ctx.hasViolations()) {
            return;
        }
        Object id = JournalProcesses.insertDraft(ctx, input.postingDate(), input.documentDate(), input.description(),
            JournalEntities.IMPORT, JournalProcesses.externalRef(JournalEntities.IMPORT, ref), null, lines, null, null);
        JournalValidator.Totals totals = JournalValidator.totals(lines);
        ctx.put(OUTPUT, new JournalProcesses.JournalOutput(String.valueOf(id), null, JournalEntities.DRAFT,
            totals.debit(), totals.credit(), null, null, null, null));
        if (!Boolean.FALSE.equals(input.submit())) {
            ctx.put(SUBMIT_INPUT, new JournalProcesses.JournalId(java.util.UUID.fromString(String.valueOf(id))));
        }
    }

    private static ImportedEntry input(ProcessContext ctx) {
        return ctx.get(INPUT, ImportedEntry.class);
    }

    private JournalImportProcesses() {}
}
