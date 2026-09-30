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
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
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

/**
 * The step of {@code IMPORT_RUN}: reads the file (off the request threads), prepares the rows, and runs each unit -
 * a row, or a group of rows - as a sub-process behind a savepoint. A unit that fails is undone on its own and its
 * problems are recorded; later units still run, and see what the earlier successful ones wrote, exactly as a commit
 * would. The report goes to the output; whether anything stays is up to the caller (a preview is a dry run).
 */
@Component
public class RunImport implements StepHandler<NoMetadata, ProcessContext> {

    private final ImportAccess access;
    private final ImportRegistry imports;
    private final ImportFiles files;
    private final ImportSettings settings;
    private final ProcessRegistry processes;
    private final ProcessExecutor executor;
    private final ProcessInputs inputs;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    RunImport(ImportAccess access, ImportRegistry imports, ImportFiles files, ImportSettings settings, ProcessRegistry processes,
        ProcessExecutor executor, ProcessInputs inputs, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.access = access;
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
                .flatMap(read -> read.fileProblem() != null
                    ? Mono.just(rejectedFile(definition, copy, read.fileProblem()))
                    : run(definition, copy, read, params)))
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

    private Mono<ImportReport> run(ImportDefinition<Object> definition, ImportFiles.Copy copy, Read read,
        Object params) {
        ImportPreparation.Plan plan = ImportPreparation.plan(definition, read.converted(), params, Set.of());
        ProcessDefinition<?, ?, ?> target = processes.find(definition.target().process(),
                definition.target().version())
            .orElseThrow(() -> new IllegalStateException("Import " + definition.id() + ": process "
                + definition.target().process() + " v" + definition.target().version() + " is not registered"));
        StorageEngine engine = storages.getEngine(poolRef);
        List<ImportIssue> issues = new ArrayList<>(plan.issues());
        Set<Integer> failedRows = new HashSet<>();
        return Flux.fromIterable(plan.units())
            .concatMap(unit -> engine.inSavepoint(execute(target, unit.input()))
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
        return new ImportReport(definition.id(), definition.version(), copy.fileId().toString(), copy.sha256(), false,
            0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), List.of(), List.of(problem));
    }

    private static ImportReport report(ImportDefinition<?> definition, ImportFiles.Copy copy, Read read,
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
            results.add(new ImportReport.RowResult(record.number(), record.location(), status, values));
        }
        return new ImportReport(definition.id(), definition.version(), copy.fileId().toString(), copy.sha256(), false,
            plan.recordCount(), plan.rowCount(), processed, plan.units().size(), plan.duplicates().size(),
            plan.sources().columns(),
            plan.sources().constants(), plan.totals(), results, List.copyOf(issues));
    }
}
