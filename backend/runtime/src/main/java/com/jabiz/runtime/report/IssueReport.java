package com.jabiz.runtime.report;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.report.ReportDocument;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.security.Permissions;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The step of {@code REPORT_ISSUE} (docs/design/19-reports.md section 5). The caller needs the template's own
 * permissions besides the process's. The parts of the point in time nobody asked for - in the input or through the
 * template's {@code timeSlice} parameters - are pinned to the issue time and archived, so that running the report
 * again at the same point reads the same versions.
 */
@Component
public class IssueReport implements StepHandler<NoMetadata, ProcessContext> {

    private final SqlTemplateRegistry templates;
    private final AdvancedQueryExecutor executor;
    private final ReportExporter exporter;
    private final ReportRuns runs;
    private final ReportSettings settings;
    private final EntityDefinitionRegistry entities;
    private final EntityIdGenerator ids;
    private final ReportScopes scopes;
    private final boolean development;

    public IssueReport(SqlTemplateRegistry templates, AdvancedQueryExecutor executor, ReportExporter exporter,
        ReportRuns runs, ReportSettings settings, EntityDefinitionRegistry entities, EntityIdGenerator ids,
        ReportScopes scopes, Environment environment) {
        this.scopes = scopes;
        this.templates = templates;
        this.executor = executor;
        this.exporter = exporter;
        this.runs = runs;
        this.settings = settings;
        this.entities = entities;
        this.ids = ids;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            ReportProcesses.IssueInput input = ctx.get(ReportProcesses.INPUT, ReportProcesses.IssueInput.class);
            AdvancedQueryDefinition query = templates.find(input.templateId())
                .orElseThrow(() -> new EntityNotFoundException("Unknown query: " + input.templateId()));
            Permissions.requireAll(ctx.request(), query.permissions(), development,
                "Issuing report " + query.queryId());
            Instant issued = ctx.opTime();
            Map<String, Object> params = new LinkedHashMap<>(input.params() == null ? Map.of() : input.params());
            AdvancedQueryExecutor.At asked = new AdvancedQueryExecutor.At(input.asOf(), input.knownAt());
            // Whatever is not asked for is read as at the issue time, and pinned there for verification.
            AdvancedQueryExecutor.At readAt = asked.pin(issued, issued);
            return supersedable(input, query)
                // Masked fields stay masked in the archive, whoever issues it (10 section 13.1).
                .then(executor.all(query, params, readAt, null, List.of(), settings.maxRows() + 1, true))
                .flatMap(page -> {
                    if (page.items().size() > settings.maxRows()) {
                        return Mono.error(new BusinessRuleViolationException(new Violation(null,
                            PlatformErrorCodes.REPORT_TOO_LARGE, "Report " + query.queryId() + " has more than "
                            + settings.maxRows() + " rows", Map.of("limit", settings.maxRows()))));
                    }
                    AdvancedQueryDefinition prepared = templates.prepare(query);
                    ReportDocument document = exporter.document(prepared, params,
                        ReportExporter.asOfAsked(query, params, asked), page, issued, ctx.request().locale());
                    // A run is no entity of the metamodel: the generator is asked for a plain UUIDv7.
                    UUID runId = UUID.fromString(String.valueOf(ids.next(null)));
                    ReportRun run = new ReportRun(runId, query.queryId(), query.version(), templates.source(query),
                        query.permissions(), scopes.of(query, ctx.request()), document.title(), document.company(), document.period(),
                        ctx.request().locale().getLanguage(), params, document.parameters(), document.asOf(),
                        page.slice().asOf(), page.slice().knownAt() != null ? page.slice().knownAt() : issued,
                        document.landscape(), document.columns(), document.rows(), document.rows().size(),
                        ArchivedValues.hash(document.columns(), document.rows()), recomputable(query),
                        ctx.request().actorId(), issued, ctx.processSeqId(), null);
                    Mono<Void> supersede = input.supersedes() == null ? Mono.empty()
                        : runs.supersede(UUID.fromString(input.supersedes()), runId, issued, ctx.processSeqId());
                    return runs.insert(run).then(supersede).then(Mono.fromRunnable(() -> ctx.put(
                        ReportProcesses.OUTPUT, new ReportProcesses.IssueOutput(runId.toString(), run.templateVersion(),
                            run.contentHash(), run.rowCount(), run.recomputable()))));
                });
        });
    }

    /** The run to supersede exists, is of the same template and has not been superseded yet. */
    private Mono<Void> supersedable(ReportProcesses.IssueInput input, AdvancedQueryDefinition query) {
        if (input.supersedes() == null) {
            return Mono.empty();
        }
        UUID earlier;
        try {
            earlier = UUID.fromString(input.supersedes());
        } catch (IllegalArgumentException e) {
            return Mono.error(new EntityNotFoundException("Unknown report run: " + input.supersedes()));
        }
        return runs.find(earlier)
            .switchIfEmpty(Mono.error(new EntityNotFoundException("Unknown report run: " + input.supersedes())))
            .flatMap(run -> {
                if (!run.templateId().equals(query.queryId())) {
                    return Mono.error(new BusinessRuleViolationException(new Violation("supersedes",
                        PlatformErrorCodes.REPORT_SUPERSEDE_MISMATCH, "Run " + earlier + " is a report of "
                        + run.templateId(), Map.of("template", run.templateId()))));
                }
                if (run.supersededBy() != null) {
                    return Mono.error(new BusinessRuleViolationException(new Violation("supersedes",
                        PlatformErrorCodes.REPORT_ALREADY_SUPERSEDED, "Run " + earlier + " is already superseded",
                        Map.of("run", String.valueOf(run.supersededBy())))));
                }
                return Mono.empty();
            });
    }

    /** Only temporal entities: nothing the template reads is ever changed in place. */
    private boolean recomputable(AdvancedQueryDefinition query) {
        return query.participatingEntities().stream()
            .allMatch(entity -> entities.find(entity).map(def -> def.temporal).orElse(false));
    }
}
