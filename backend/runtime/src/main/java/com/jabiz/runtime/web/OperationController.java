package com.jabiz.runtime.web;

import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.RevertService;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.operation.OperationRecord;
import com.jabiz.runtime.operation.OperationRecorder;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.temporal.TemporalPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operation details and reverts (docs/design/06-process.md section 8; decision D2). Both need their permission:
 * {@value TemporalPermissions#OPERATION_READ} to read, {@value TemporalPermissions#REVERT} to revert.
 */
@RestController
@RequestMapping("/api/processes/executions/{processSeqId}")
class OperationController {

    record RevertRequest(String reason) {}

    private final OperationRecorder operations;
    private final RevertService reverts;

    OperationController(OperationRecorder operations, RevertService reverts) {
        this.operations = operations;
        this.reverts = reverts;
    }

    /** The operation, the versions it wrote and its direct sub-operations. */
    @GetMapping
    Mono<Map<String, Object>> detail(@PathVariable long processSeqId) {
        return RequestContexts.current().flatMap(request -> {
            if (!request.permissions().contains(TemporalPermissions.OPERATION_READ)) {
                return Mono.error(new PermissionDeniedException(TemporalPermissions.OPERATION_READ,
                    "Reading operations needs permission " + TemporalPermissions.OPERATION_READ));
            }
            return describe(reverts.engine(), processSeqId);
        });
    }

    /** Reverts the operation and its sub-operations; answers with the revert operation. */
    @PostMapping("/revert")
    Mono<Map<String, Object>> revert(@PathVariable long processSeqId, @RequestBody(required = false) RevertRequest body) {
        return reverts.revert(processSeqId, body == null ? null : body.reason())
            .flatMap(revert -> describe(reverts.engine(), revert.processSeqId()));
    }

    private Mono<Map<String, Object>> describe(StorageEngine engine, long processSeqId) {
        return operations.find(engine, processSeqId)
            .switchIfEmpty(Mono.error(() -> new EntityNotFoundException("Operation " + processSeqId + " not found")))
            .flatMap(record -> operations.items(engine, processSeqId).collectList()
                .zipWith(operations.children(engine, processSeqId).map(OperationRecord::processSeqId).collectList())
                .map(parts -> {
                    Map<String, Object> json = new LinkedHashMap<>();
                    json.put("processSeqId", record.processSeqId());
                    json.put("parentSeqId", record.parentSeqId());
                    json.put("revertsSeqId", record.revertsSeqId());
                    json.put("processName", record.processName());
                    json.put("processVersion", record.processVersion());
                    json.put("actorId", record.actorId());
                    json.put("tenantId", record.tenantId());
                    json.put("requestId", record.requestId());
                    json.put("reason", record.reason());
                    json.put("opTime", record.opTime());
                    json.put("items", OperationRecorder.describe(parts.getT1()));
                    json.put("children", parts.getT2());
                    return json;
                }));
    }
}
