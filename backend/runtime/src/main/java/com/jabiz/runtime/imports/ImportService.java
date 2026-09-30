package com.jabiz.runtime.imports;

import com.jabiz.context.RequestContext;
import com.jabiz.imports.ImportCodes;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFileException;
import com.jabiz.imports.ImportFormat;
import com.jabiz.imports.ImportIssue;
import com.jabiz.imports.ImportMapping;
import com.jabiz.imports.ImportParsers;
import com.jabiz.imports.ImportPreparation;
import com.jabiz.imports.ParsedFile;
import com.jabiz.imports.RawRecord;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessInputs;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.ProcessResult;
import io.micrometer.common.KeyValues;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entry to imports (docs/design/20-imports.md section 6): who may import what, reading a file's layout for the
 * mapping step, and previews. Importing needs the import's permission, the permissions of the process its rows go
 * to, and the read permission of its file policy; all are checked before the file is touched (default deny).
 */
@Service
public class ImportService {

    /** Records shown when inspecting a file for the mapping step. */
    static final int SAMPLE_RECORDS = 20;

    /** What a file looks like, for choosing the mapping. */
    public record Inspection(List<String> columns, Map<String, String> header, List<RawRecord> sample,
        int records, Map<String, String> suggested, List<ImportIssue> issues) {}

    private final ImportAccess access;
    private final ImportRuns runs;
    private final ImportRegistry imports;
    private final ImportFiles files;
    private final ImportSettings settings;
    private final ProcessRegistry processes;
    private final ProcessExecutor executor;
    private final ProcessInputs inputs;
    private final PlatformObservations observations;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final JsonMapper json;

    ImportService(ImportAccess access, ImportRuns runs, ImportRegistry imports, ImportFiles files, ImportSettings settings, ProcessRegistry processes,
        ProcessExecutor executor, ProcessInputs inputs,
        PlatformObservations observations, DatasetEntityManager entities,
        DatasetRegistry datasets, JsonMapper json) {
        this.access = access;
        this.runs = runs;
        this.entities = entities;
        this.datasets = datasets;
        this.json = json;
        this.imports = imports;
        this.files = files;
        this.settings = settings;
        this.processes = processes;
        this.executor = executor;
        this.inputs = inputs;
        this.observations = observations;
    }

    /** Whether the caller may run the import. */
    public boolean allowed(ImportDefinition<?> definition, RequestContext context) {
        return access.allowed(definition, context);
    }

    private Mono<ImportDefinition<Object>> authorized(String importId) {
        return RequestContexts.current().map(context -> {
            ImportDefinition<Object> definition = imports.require(importId);
            access.require(definition, context);
            return definition;
        });
    }

    /** The file's columns, first records and the columns the fields would be read from without a mapping. */
    public Mono<Inspection> inspect(String importId, String fileId, ImportFormat.Options options) {
        return authorized(importId).flatMap(definition -> files.copy(fileId, definition).flatMap(copy ->
            Mono.fromCallable(() -> inspect(definition, copy, options == null ? ImportFormat.Options.NONE : options))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> Schedulers.boundedElastic().schedule(() -> ImportFiles.delete(copy.path())))
                .publishOn(Schedulers.parallel())));
    }

    private Inspection inspect(ImportDefinition<?> definition, ImportFiles.Copy copy, ImportFormat.Options options)
        throws java.io.IOException {
        try {
            ImportFormat format = definition.format().adjustable() ? definition.format().adjusted(options)
                : definition.format();
            ParsedFile file = ImportParsers.parse(format, copy.path(), settings.limits(definition));
            ImportMapping mapping = new ImportMapping(Map.of(), Map.of(), options);
            ImportPreparation.Converted converted = ImportPreparation.convert(definition,
                new ParsedFile(file.columns(), file.header(), List.of()), mapping);
            List<ImportIssue> issues = new ArrayList<>(converted.issues().stream()
                .filter(issue -> !issue.code().equals(ImportCodes.EMPTY)).toList());
            return new Inspection(file.columns(), file.header(),
                file.records().subList(0, Math.min(SAMPLE_RECORDS, file.records().size())), file.records().size(),
                converted.sources().columns(), issues);
        } catch (ImportFileException e) {
            return new Inspection(List.of(), Map.of(), List.of(), 0, Map.of(), List.of(new ImportIssue(0,
                e.location(), null, null, e.code(), e.getMessage(), e.params())));
        } catch (IllegalArgumentException e) {
            return new Inspection(List.of(), Map.of(), List.of(), 0, Map.of(), List.of(ImportIssue.ofFile(
                ImportCodes.FILE_INVALID, e.getMessage(), Map.of("detail", e.getMessage()))));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Everything a commit would do, done and rolled back: every row read and checked, every unit run through its
     * process at this moment, nothing kept.
     */
    public Mono<ImportReport> preview(String importId, String fileId, ImportMapping mapping,
        Map<String, Object> params) {
        return authorized(importId).flatMap(definition -> {
            // Parameters are refused (400) before anything runs.
            inputs.convert(definition.paramsType(), params);
            ImportProcesses.RunInput input = new ImportProcesses.RunInput(definition.id(), fileId, mapping, params);
            inputs.validate(input);
            return observations.mono(PlatformObservations.IMPORT, "import preview " + definition.id(),
                KeyValues.of("import", definition.id(), "mode", "preview"),
                executor.run(importProcess(), input, ExecutionOptions.DRY_RUN).map(ProcessResult::output));
        });
    }

    /**
     * Imports the file: every unit is run as in a preview and, when nothing at all is wrong, what they did is kept and
     * the import recorded; otherwise nothing is kept but the record of the rejected attempt. 409 when the file was
     * imported before.
     */
    public Mono<ImportReport> commit(String importId, String fileId, ImportMapping mapping,
        Map<String, Object> params, String notes) {
        return authorized(importId).flatMap(definition -> {
            inputs.convert(definition.paramsType(), params);
            ImportProcesses.RunInput input = new ImportProcesses.RunInput(definition.id(), fileId, mapping, params,
                true, notes);
            // Built here, not read from a request body: checked the same way before anything runs (notes too long, say).
            inputs.validate(input);
            return observations.mono(PlatformObservations.IMPORT, "import commit " + definition.id(),
                KeyValues.of("import", definition.id(), "mode", "commit"),
                executor.run(importProcess(), input, ExecutionOptions.NONE).map(ProcessResult::output));
        });
    }

    /** The runs of imports the caller may run, newest first. */
    public Mono<List<ImportRun>> runs(String importId, int limit) {
        return RequestContexts.current().flatMap(context -> runs.latest(importId)
            .filter(run -> readable(run, context))
            .take(limit)
            .collectList());
    }

    /** The run, if the caller may run its import; 404 otherwise. */
    public Mono<ImportRun> run(String runId) {
        UUID id;
        try {
            id = UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            return Mono.error(new EntityNotFoundException("Unknown import run: " + runId));
        }
        return RequestContexts.current().flatMap(context -> runs.find(id)
            .filter(run -> readable(run, context))
            .switchIfEmpty(Mono.error(() -> new EntityNotFoundException("Unknown import run: " + runId))));
    }

    /** A run of an import that no longer exists is kept but shown to nobody through this API. */
    private boolean readable(ImportRun run, RequestContext context) {
        return imports.find(run.importId()).map(definition -> access.allowed(definition, context)).orElse(false);
    }

    /** A saved mapping. */
    public record SavedMapping(String name, ImportMapping mapping) {}

    /** The saved mappings of the import, by name. */
    public Mono<List<SavedMapping>> mappings(String importId) {
        return authorized(importId).flatMap(definition -> entities.query(
                datasets.findById(ImportMappings.DATASET).orElseThrow(), ImportMappings.SYS_IMPORT_MAPPING,
                ImportMappings.ofImport(definition.id()))
            .map(found -> new SavedMapping(found.get(ImportMappings.NAME),
                json.readValue((String) found.get(ImportMappings.MAPPING), ImportMapping.class)))
            .sort(Comparator.comparing(SavedMapping::name))
            .collectList());
    }

    /**
     * Saves the mapping under the name, replacing the one of that name. The process checks the import's permissions,
     * its mapping permission and the mapping's fields.
     */
    public Mono<ImportMappings.MappingOutput> saveMapping(String importId, String name, ImportMapping mapping) {
        return executor.execute(process(ImportMappings.SAVE), new ImportMappings.SaveInput(importId, name,
            mapping == null ? ImportMapping.DEFAULT : mapping));
    }

    public Mono<ImportMappings.MappingOutput> removeMapping(String importId, String name) {
        return executor.execute(process(ImportMappings.REMOVE), new ImportMappings.RemoveInput(importId, name));
    }

    @SuppressWarnings("unchecked")
    private <I> ProcessDefinition<I, ImportMappings.MappingOutput, ProcessContext> process(
        String name) {
        return (ProcessDefinition<I, ImportMappings.MappingOutput, ProcessContext>)
            processes.find(name, 1).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private ProcessDefinition<ImportProcesses.RunInput, ImportReport, ProcessContext>
    importProcess() {
        return (ProcessDefinition<ImportProcesses.RunInput, ImportReport, ProcessContext>)
            processes.find(ImportProcesses.RUN, 1).orElseThrow();
    }
}
