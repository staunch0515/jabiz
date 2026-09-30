package com.jabiz.runtime.imports;

import com.jabiz.imports.ImportCodes;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFileException;
import com.jabiz.imports.ImportFormat;
import com.jabiz.imports.ImportIssue;
import com.jabiz.imports.ImportMapping;
import com.jabiz.imports.ImportParsers;
import com.jabiz.imports.ImportPreparation;
import com.jabiz.imports.ImportRow;
import com.jabiz.imports.ParsedFile;
import com.jabiz.imports.RawRecord;
import com.jabiz.entity.Violation;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessInputs;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.SensitiveDataMasker;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.web.ProblemStatuses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The step of {@code IMPORT_RUN}: reads the file (off the request threads), prepares the rows, and runs each unit -
 * a row, or a group of rows - as a sub-process behind a savepoint. A unit that fails is undone on its own and its
 * problems are recorded; later units still run, and see what the earlier successful ones wrote, exactly as a commit
 * would. The report goes to the output; whether anything stays is up to the caller (a preview is a dry run).
 */
@Component
public class RunImport implements StepHandler<NoMetadata, ProcessContext> {

    private final ImportAccess access;
    private final ImportRuns runs;
    private final EntityIdGenerator ids;
    private final ImportRegistry imports;
    private final ImportFiles files;
    private final ImportSettings settings;
    private final ProcessRegistry processes;
    private final ProcessExecutor executor;
    private final ProcessInputs inputs;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final SensitiveDataMasker masker;

    RunImport(ImportAccess access, ImportRuns runs, EntityIdGenerator ids, ImportRegistry imports, ImportFiles files, ImportSettings settings, ProcessRegistry processes,
        ProcessExecutor executor, ProcessInputs inputs, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, SensitiveDataMasker masker) {
        this.masker = masker;
        this.access = access;
        this.runs = runs;
        this.ids = ids;
        this.imports = imports;
        this.files = files;
        this.settings = settings;
        this.processes = processes;
        this.executor = executor;
        this.inputs = inputs;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    /** What reading the file gave: the conversion, or why the file cannot be read at all. */
    private record Read(ParsedFile file, ImportPreparation.Converted converted, ImportIssue fileProblem) {}

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        ImportProcesses.RunInput input = ctx.get(ImportProcesses.INPUT, ImportProcesses.RunInput.class);
        ImportDefinition<Object> definition = imports.require(input.importId());
        access.require(definition, ctx.request());
        Object params = inputs.convert(definition.paramsType(), input.params());
        ImportMapping mapping = input.mapping() == null ? ImportMapping.DEFAULT : input.mapping();
        return files.copy(input.fileId(), definition).flatMap(copy ->
            Mono.fromCallable(() -> read(definition, copy.path(), mapping))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> Schedulers.boundedElastic().schedule(() -> ImportFiles.delete(copy.path())))
                .publishOn(Schedulers.parallel())
                // Commits of one file wait for each other, so that the later one sees the earlier and is refused.
                .flatMap(read -> (input.committing() ? runs.lockFile(definition.id(), copy.sha256()) : Mono.<Void>empty())
                    .then(runs.committedRun(definition.id(), copy.sha256())).flatMap(earlier -> {
                    if (earlier.isPresent() && input.committing()) {
                        return Mono.error(new ImportConflictException(ImportCodes.ALREADY_IMPORTED,
                            "File " + copy.fileId() + " was imported as run " + earlier.get(),
                            Map.of("run", earlier.get().toString())));
                    }
                    List<ImportIssue> earlierIssues = earlier.map(run -> List.of(ImportIssue.ofFile(
                        ImportCodes.ALREADY_IMPORTED, "The file was imported as run " + run,
                        Map.<String, Object>of("run", run.toString())))).orElse(List.of());
                    if (read.fileProblem() != null) {
                        ImportReport report = rejectedFile(definition, copy, read.fileProblem());
                        return input.committing() ? record(ctx, input, definition, copy, mapping, report, null)
                            : Mono.just(report);
                    }
                    return runs.knownRefs(definition.id(), read.converted().refs(definition))
                        .flatMap(known -> run(ctx, input, definition, copy, mapping, read, params, known,
                            earlierIssues));
                })))
            .doOnNext(report -> ctx.put(ImportProcesses.OUTPUT, report))
            .then();
    }

    private Read read(ImportDefinition<?> definition, Path path, ImportMapping mapping) throws IOException {
        try {
            ImportFormat format = definition.format().adjustable()
                ? definition.format().adjusted(mapping.options()) : definition.format();
            ParsedFile file = ImportParsers.parse(format, path, settings.limits(definition));
            return new Read(file, ImportPreparation.convert(definition, file, mapping), null);
        } catch (ImportFileException e) {
            return new Read(null, null, new ImportIssue(0, e.location(), null, null, e.code(), e.getMessage(),
                e.params()));
        } catch (IllegalArgumentException e) {
            // an adjustment the layout cannot take, such as an unknown charset
            return new Read(null, null, ImportIssue.ofFile(ImportCodes.FILE_INVALID, e.getMessage(), Map.of("detail", e.getMessage())));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Every problem found, when a commit is rejected: undoes the units and carries the report out. */
    private static final class Rejected extends RuntimeException {
        private final transient ImportReport report;

        Rejected(ImportReport report) {
            super("rejected", null, false, false);
            this.report = report;
        }
    }

    /**
     * Runs the units, each behind a savepoint. For a commit, all of them are behind one more savepoint: when anything
     * is wrong they are all undone, and only the record of the rejected attempt stays. After-commit steps of undone
     * work are dropped with it.
     */
    private Mono<ImportReport> run(ProcessContext ctx, ImportProcesses.RunInput input,
        ImportDefinition<Object> definition, ImportFiles.Copy copy, ImportMapping mapping, Read read, Object params,
        Set<String> known, List<ImportIssue> earlierIssues) {
        ImportPreparation.Plan plan = ImportPreparation.plan(definition, read.converted(), params, known);
        ProcessDefinition<?, ?, ?> target = processes.find(definition.target().process(),
                definition.target().version())
            .orElseThrow(() -> new IllegalStateException("Import " + definition.id() + ": process "
                + definition.target().process() + " v" + definition.target().version() + " is not registered"));
        StorageEngine engine = storages.getEngine(poolRef);
        List<ImportIssue> issues = new ArrayList<>(earlierIssues);
        issues.addAll(plan.issues());
        Set<Integer> failedRows = new HashSet<>();
        Mono<ImportReport> units = Flux.fromIterable(plan.units())
            .concatMap(unit -> engine.inSavepoint(executor.keepingAfterCommitOnSuccess(execute(target, unit.input())))
                .thenReturn(Boolean.TRUE)
                .onErrorResume(error -> {
                    if (ProblemStatuses.status(error) >= 500) {
                        return Mono.error(error);  // a defect, not a problem of the row: the import fails
                    }
                    unit.rows().forEach(row -> failedRows.add(row.number()));
                    issues.addAll(issuesOf(unit, error));
                    return Mono.just(Boolean.FALSE);
                }))
            .filter(Boolean::booleanValue)
            .count()
            .map(processed -> report(definition, copy, read, plan, failedRows, issues, processed.intValue()));
        if (!input.committing()) {
            return units;
        }
        return engine.inSavepoint(executor.keepingAfterCommitOnSuccess(units.flatMap(report -> report.accepted()
                ? Mono.just(report) : Mono.<ImportReport>error(new Rejected(report)))))
            .flatMap(report -> record(ctx, input, definition, copy, mapping, report, plan))
            .onErrorResume(Rejected.class, rejected -> record(ctx, input, definition, copy, mapping,
                rejected.report, null));
    }

    /**
     * Records the import: committed, with the external references of the imported rows, when {@code plan} is given;
     * otherwise rejected.
     */
    private Mono<ImportReport> record(ProcessContext ctx, ImportProcesses.RunInput input,
        ImportDefinition<?> definition, ImportFiles.Copy copy, ImportMapping mapping, ImportReport report,
        ImportPreparation.Plan plan) {
        UUID runId = UUID.fromString(String.valueOf(ids.next(null)));
        boolean committed = plan != null;
        List<ImportIssue> kept = report.issues().size() > ImportRun.MAX_ISSUES
            ? report.issues().subList(0, ImportRun.MAX_ISSUES) : report.issues();
        ImportRun run = new ImportRun(runId, definition.id(), definition.version(),
            committed ? ImportRun.COMMITTED : ImportRun.REJECTED, copy.fileId(), copy.sha256(), mapping,
            input.params(), report.records(), report.rows(), report.units(), report.processed(), report.duplicates(),
            report.issues().size(), report.columns(), report.totals(), kept, input.notes(), ctx.request().actorId(),
            ctx.opTime(), ctx.processSeqId());
        Mono<Void> refs = committed ? Flux.fromIterable(plan.units())
            .flatMapIterable(ImportPreparation.Unit::rows)
            .concatMap(row -> {
                String ref = ImportPreparation.ref(definition, row);
                return ref == null ? Mono.<Void>empty()
                    : runs.insertRef(definition.id(), ref, runId, row.number(), ctx.processSeqId());
            })
            .then() : Mono.empty();
        return runs.insert(run).then(refs)
            .onErrorMap(UniqueKeyViolationException.class, error ->
                ImportRuns.DUPLICATE_CONSTRAINTS.contains(error.constraintName())
                    ? new ImportConflictException(ImportCodes.ALREADY_IMPORTED, "The file, or a row of it, was "
                        + "imported at the same time by another commit", Map.of("run", "-"))
                    : error)
            .thenReturn(report.recorded(runId.toString(), committed));
    }

    @SuppressWarnings("unchecked")
    private <I> Mono<Object> execute(ProcessDefinition<I, ?, ?> target, Object input) {
        return Mono.fromCallable(() -> {
                if (!target.inputType().isInstance(input)) {
                    throw new IllegalStateException("The import builds a " + input.getClass().getName()
                        + " but process " + target.name() + " takes a " + target.inputType().getName());
                }
                inputs.validate(input);
                return (I) input;
            })
            .flatMap(checked -> executor.executeChild((ProcessDefinition<I, Object, ProcessContext>) target, checked));
    }

    private static List<ImportIssue> issuesOf(ImportPreparation.Unit unit, Throwable error) {
        ImportRow first = unit.rows().getFirst();
        List<Violation> violations = ProblemStatuses.violations(error);
        if (violations.isEmpty()) {
            String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return List.of(new ImportIssue(first.number(), first.location(), null, null, ImportCodes.ROW_FAILED,
                detail, Map.of("detail", detail)));
        }
        return violations.stream().map(v -> new ImportIssue(first.number(), first.location(), v.field(), null,
            v.ruleCode(), v.message(), v.params())).toList();
    }

    /** The report of a file that could not be read at all. */
    private static ImportReport rejectedFile(ImportDefinition<?> definition, ImportFiles.Copy copy,
        ImportIssue problem) {
        return new ImportReport(null, definition.id(), definition.version(), copy.fileId().toString(), copy.sha256(),
            false,
            0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), List.of(), List.of(problem));
    }

    private ImportReport report(ImportDefinition<?> definition, ImportFiles.Copy copy, Read read,
        ImportPreparation.Plan plan, Set<Integer> failedRows, List<ImportIssue> issues, int processed) {
        Set<Integer> errorRows = new HashSet<>(failedRows);
        issues.stream().filter(issue -> issue.row() > 0).forEach(issue -> errorRows.add(issue.row()));
        Set<Integer> duplicates = new HashSet<>();
        plan.duplicates().forEach(row -> duplicates.add(row.number()));
        Map<Integer, ImportRow> converted = new LinkedHashMap<>();
        read.converted().rows().forEach(row -> converted.put(row.number(), row));
        List<ImportReport.RowResult> results = new ArrayList<>();
        for (RawRecord record : read.file().records()) {
            ImportRow row = converted.get(record.number());
            Map<String, Object> values = new LinkedHashMap<>();
            if (row != null) {
                values.putAll(row.values());
            } else {
                plan.sources().columns().forEach((field, column) -> values.put(field, record.cells().get(column)));
                values.putAll(plan.sources().constants());
            }
            String status = errorRows.contains(record.number()) ? ImportReport.ERROR
                : duplicates.contains(record.number()) ? ImportReport.DUPLICATE : ImportReport.OK;
            // Reports are kept and shown to others: masked fields as the read APIs show them (10 section 13.1).
            results.add(new ImportReport.RowResult(record.number(), record.location(), status,
                masker.maskByName(values)));
        }
        return new ImportReport(null, definition.id(), definition.version(), copy.fileId().toString(), copy.sha256(),
            false,
            plan.recordCount(), plan.rowCount(), processed, plan.units().size(), plan.duplicates().size(),
            plan.sources().columns(),
            masker.maskByName(plan.sources().constants()), plan.totals(), results, List.copyOf(issues));
    }
}
