package com.jabiz.runtime.report;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.steps.PublishEvent;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code REPORT_ISSUE} (docs/design/19-reports.md section 5): runs a report and keeps what it showed - parameters,
 * point in time, template version and text, page header, rows and content hash - so that it can be shown again
 * exactly and checked against the data later. A run may supersede an earlier run of the same template. Announces
 * {@code jabiz.report.issued}.
 */
@Configuration
public class ReportProcesses {

    public static final String ISSUE = "REPORT_ISSUE";
    public static final String ISSUED_EVENT = "jabiz.report.issued";

    static final String INPUT = "input";
    static final String OUTPUT = "output";

    /**
     * @param templateId the SQL template
     * @param params     its parameters
     * @param asOf       the effective time to read at (not for templates declaring {@code timeSlice}); default the
     *                   issue time
     * @param knownAt    the recorded time to read as of (idem); default the issue time, which makes the rows
     *                   reproducible from the data
     * @param supersedes an earlier run of the same template that this one replaces, or null
     */
    public record IssueInput(@NotBlank String templateId, Map<String, Object> params, Instant asOf, Instant knownAt,
        String supersedes) {}

    /**
     * @param recomputable whether the template reads temporal entities only, so that {@code verify} can compare the
     *                     rows with the data
     */
    public record IssueOutput(String runId, String templateVersion, String contentHash, int rowCount,
        boolean recomputable) {}

    @Bean
    ProcessDefinition<IssueInput, IssueOutput, ProcessContext> reportIssueProcess() {
        return ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> pb
            .description("Runs a report and archives what it shows, with its content hash, to be reproduced exactly.")
            .permissions(ReportPermissions.ISSUE)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, IssueOutput.class))
            .step("Run and archive the report", IssueReport.class, NoMetadata.INSTANCE)
            .step("Announce it", PublishEvent.of(ISSUED_EVENT, ReportProcesses::issued)));
    }

    private static Map<String, Object> issued(ProcessContext ctx) {
        IssueInput input = ctx.get(INPUT, IssueInput.class);
        IssueOutput output = ctx.get(OUTPUT, IssueOutput.class);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", output.runId());
        payload.put("templateId", input.templateId());
        payload.put("contentHash", output.contentHash());
        payload.put("supersedes", input.supersedes());
        return payload;
    }
}
