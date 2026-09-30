package com.jabiz.runtime.export;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.report.ReportPermissions;
import com.jabiz.runtime.report.ReportScopes;
import com.jabiz.runtime.retention.RetentionPermissions;
import com.jabiz.runtime.security.Permissions;
import io.micrometer.common.KeyValues;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * {@code POST /api/exports/data} (docs/design/21-audit-retention.md section 4): the open-format export as a ZIP. Needs
 * {@value RetentionPermissions#EXPORT}, the read permission of every dataset exported and, for the issued reports,
 * {@value ReportPermissions#ARCHIVE_READ} (each report then also its template's permissions and scope, as in the
 * archive). No row limit: the export is for whole datasets.
 */
@RestController
@RequestMapping("/api/exports")
class ExportController {

    /** Most datasets one export takes. */
    static final int MAX_DATASETS = 100;

    /**
     * @param datasets    the datasets to export
     * @param asOf        the effective time to read temporal entities at; default the start of the export
     * @param knownAt     the recorded time to read them as of; default the start of the export
     * @param reports     whether to add the archived PDFs of the reports issued in {@code [reportsFrom, reportsTo)}
     */
    record ExportRequest(List<String> datasets, Instant asOf, Instant knownAt, Boolean reports, Instant reportsFrom,
        Instant reportsTo) {}

    private final DataExporter exporter;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final ReportScopes scopes;
    private final PlatformObservations observations;
    private final Clock clock;
    private final boolean development;

    ExportController(DataExporter exporter, DatasetRegistry datasets, EntityDefinitionRegistry entities,
        ReportScopes scopes, PlatformObservations observations, Clock clock, Environment environment) {
        this.exporter = exporter;
        this.datasets = datasets;
        this.entities = entities;
        this.scopes = scopes;
        this.observations = observations;
        this.clock = clock;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @PostMapping(value = "/data", produces = "application/zip")
    Mono<ResponseEntity<Flux<DataBuffer>>> export(@RequestBody ExportRequest body) {
        return RequestContexts.current().flatMap(request -> {
            Permissions.requireAll(request, List.of(RetentionPermissions.EXPORT), development, "Exporting data");
            List<String> ids = body.datasets() == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(body.datasets()));
            List<Violation> problems = new ArrayList<>();
            if (ids.isEmpty() || ids.size() > MAX_DATASETS) {
                problems.add(new Violation("datasets", PlatformErrorCodes.INVALID_VALUE,
                    "Name between 1 and " + MAX_DATASETS + " datasets"));
            }
            if (body.reportsFrom() != null && body.reportsTo() != null
                && !body.reportsFrom().isBefore(body.reportsTo())) {
                problems.add(new Violation("reportsTo", PlatformErrorCodes.INVALID_VALUE,
                    "reportsTo must be after reportsFrom"));
            }
            if (!problems.isEmpty()) {
                throw new ValidationException(problems);
            }
            List<DatasetDefinition> chosen = ids.stream().map(id -> {
                DatasetDefinition dataset = datasets.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("Unknown dataset: " + id));
                Permissions.requireDeclared(request, dataset.permissions().read(), development,
                    "Exporting dataset " + id);
                return dataset;
            }).toList();
            // Another point in time needs time travel; plain entities have no other time and ignore it.
            if (body.asOf() != null || body.knownAt() != null) {
                List<Violation> refused = chosen.stream()
                    .filter(dataset -> entities.find(dataset.targetEntityType()).map(def -> def.temporal)
                        .orElse(false) && !dataset.policy().allowTimeTravel())
                    .map(dataset -> new Violation("asOf", PlatformErrorCodes.TIME_TRAVEL_NOT_ALLOWED,
                        "Dataset " + dataset.resourceId() + " shows the current state only"))
                    .toList();
                if (!refused.isEmpty()) {
                    throw new ValidationException(refused);
                }
            }
            boolean reports = Boolean.TRUE.equals(body.reports());
            if (reports) {
                Permissions.requireAll(request, List.of(ReportPermissions.ARCHIVE_READ), development,
                    "Exporting issued reports");
            }
            DataExporter.Plan plan = new DataExporter.Plan(chosen, body.asOf(), body.knownAt(), clock.instant(),
                reports,
                body.reportsFrom(), body.reportsTo(), run -> Permissions.allowsAll(request, run.permissions(),
                    development) && scopes.matches(run.scope(), request));
            String name = "jabiz-export-" + clock.instant().truncatedTo(ChronoUnit.SECONDS).toString()
                .replace(":", "") + ".zip";
            return observations.mono(PlatformObservations.DATA_EXPORT, "export data", KeyValues.empty(),
                    exporter.export(plan, request))
                .map(path -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build()
                        .toString())
                    .body(stream(path)));
        });
    }

    /** The file, read off the event loop and deleted once sent (or abandoned). */
    private static Flux<DataBuffer> stream(Path path) {
        return Flux.defer(() -> DataBufferUtils.read(path, DefaultDataBufferFactory.sharedInstance, 64 * 1024))
            .subscribeOn(Schedulers.boundedElastic())
            .doFinally(signal -> Schedulers.boundedElastic().schedule(() -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temporary file; the system cleans its directory.
                }
            }));
    }
}
