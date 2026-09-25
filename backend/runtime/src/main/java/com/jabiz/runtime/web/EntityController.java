package com.jabiz.runtime.web;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.query.EntityQuery;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.entity.AddEntityInput;
import com.jabiz.runtime.process.entity.AddProcessDefinition;
import com.jabiz.runtime.process.entity.DeleteEntityInput;
import com.jabiz.runtime.process.entity.DeleteProcessDefinition;
import com.jabiz.runtime.process.entity.UpdateEntityInput;
import com.jabiz.runtime.process.entity.UpdateProcessDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Entity-independent HTTP API. Writes run the generic add, update and delete processes; the
 * list endpoint reads through the dataset that serves the entity type.
 *
 * Every entity type that has a dataset and a version field is writable through this API.
 */
@RestController
@RequestMapping("/api/entities/{entityType}")
class EntityController {

    /** Body of a partial update: the version the caller last read and the fields to change. */
    record UpdateRequest(Long version, Map<String, Object> attributes) {}

    private final ProcessExecutor processes;
    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final DatasetEntityManager entityManager;

    EntityController(
        ProcessExecutor processes,
        EntityDefinitionRegistry entities,
        DatasetRegistry datasets,
        DatasetEntityManager entityManager
    ) {
        this.processes = processes;
        this.entities = entities;
        this.datasets = datasets;
        this.entityManager = entityManager;
    }

    /** Lists instances; {@code sort} is a field name, prefixed with '-' for descending order. */
    @GetMapping
    Flux<EntityInstance> list(
        @PathVariable String entityType,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(required = false) String sort
    ) {
        return Flux.defer(() -> {
            EntityDefinition definition = entities.find(entityType).orElseThrow(
                () -> new EntityNotFoundException("Unregistered entity type: " + entityType));
            DatasetDefinition dataset = datasets.findForEntity(entityType).orElseThrow(
                () -> new EntityNotFoundException("No dataset serves entity type: " + entityType));
            if (limit <= 0) {
                throw invalid("limit", "limit must be positive");
            }
            if (offset < 0) {
                throw invalid("offset", "offset must not be negative");
            }

            EntityQuery.Builder query = EntityQuery.builder().limit(limit).offset(offset);
            if (sort != null && !sort.isBlank()) {
                boolean ascending = !sort.startsWith("-");
                String field = ascending ? sort : sort.substring(1);
                if (definition.findField(field).isEmpty()) {
                    throw invalid("sort", "Entity '" + entityType + "' has no field '" + field + "'");
                }
                query.orderBy(field, ascending);
            }
            return entityManager.query(dataset, definition, query.build());
        });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mono<EntityInstance> add(@PathVariable String entityType, @RequestBody Map<String, Object> attributes) {
        return Mono.defer(() -> processes.execute(
            AddProcessDefinition.DEFINITION, new AddEntityInput(entityType, attributes)));
    }

    @PatchMapping("/{id}")
    Mono<EntityInstance> update(
        @PathVariable String entityType, @PathVariable String id, @RequestBody UpdateRequest request
    ) {
        return Mono.defer(() -> processes.execute(
            UpdateProcessDefinition.DEFINITION,
            new UpdateEntityInput(entityType, id, request.version(), request.attributes())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> delete(
        @PathVariable String entityType, @PathVariable String id, @RequestParam(required = false) Long version
    ) {
        return Mono.defer(() -> processes.execute(
                DeleteProcessDefinition.DEFINITION, new DeleteEntityInput(entityType, id, version)))
            .then();
    }

    private static ValidationException invalid(String field, String message) {
        return new ValidationException(List.of(new Violation(field, "INVALID_VALUE", message)));
    }
}
