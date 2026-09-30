package com.jabiz.runtime.imports;

import com.jabiz.imports.ImportMapping;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.steps.PublishEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * {@code IMPORT_RUN} (docs/design/20-imports.md section 5): reads an uploaded file as an import and hands every row,
 * or group of rows, to the import's process, each behind a savepoint so that one failing row does not hide the next
 * one's problems. Internal: it is started by the import endpoints, which check the import's own permission and the
 * permissions of the process its rows go to. A preview is a dry run of it.
 */
@Configuration
public class ImportProcesses {

    public static final String RUN = "IMPORT_RUN";

    /** Declared by the process; what the caller needs is the import's own permission. */
    public static final String RUN_PERMISSION = ImportPermissions.RUN;

    static final String INPUT = "input";
    static final String OUTPUT = "output";

    /**
     * @param importId the import
     * @param fileId   the uploaded file (under the import's file policy)
     * @param mapping  how the file's columns feed the import's fields; null for the import's defaults
     * @param params   the import's parameters
     * @param commit   whether to keep what the rows did (and record the import) when nothing is wrong; a rejected
     *                 commit keeps only the record of the attempt
     * @param notes    for a commit: what the person importing says about the data (decisions on data quality)
     */
    public record RunInput(@NotBlank String importId, @NotBlank String fileId, ImportMapping mapping,
        Map<String, Object> params, Boolean commit, @Size(max = 4000) String notes) {

        /** Whether to commit; a preview when not given. */
        public boolean committing() {
            return Boolean.TRUE.equals(commit);
        }

        public RunInput(String importId, String fileId, ImportMapping mapping, Map<String, Object> params) {
            this(importId, fileId, mapping, params, false, null);
        }
    }

    public static final String COMMITTED_EVENT = "jabiz.import.committed";

    @Bean
    ProcessDefinition<RunInput, ImportReport, ProcessContext> importRunProcess() {
        return ProcessDefinition.define(RUN, 1, RunInput.class, ImportReport.class, ProcessContext.class, pb -> pb
            .description("Imports a file: every row is checked and handed to the import's process; any problem "
                + "rejects the whole file.")
            .permissions(RUN_PERMISSION)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ImportReport.class))
            .step("Read the file and process its rows", RunImport.class, NoMetadata.INSTANCE)
            .step("Announce the import", PublishEvent.when(ImportProcesses::committed, COMMITTED_EVENT,
                ImportProcesses::committedPayload)));
    }

    private static boolean committed(ProcessContext ctx) {
        ImportReport report = ctx.get(OUTPUT, ImportReport.class);
        return report != null && report.committed();
    }

    private static Map<String, Object> committedPayload(ProcessContext ctx) {
        ImportReport report = ctx.get(OUTPUT, ImportReport.class);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", report.runId());
        payload.put("importId", report.importId());
        payload.put("rows", report.rows());
        payload.put("duplicates", report.duplicates());
        return payload;
    }
}
