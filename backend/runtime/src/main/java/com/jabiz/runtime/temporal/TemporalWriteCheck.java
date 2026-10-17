package com.jabiz.runtime.temporal;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * As {@link TemporalWriteGuard}, for a platform rule that needs the database: asked by {@link VersionAppender} for
 * every write of a temporal entity, whichever path it takes (the dataset API, the generic entity processes, the change
 * sets of processes, imports, cancellations and reverts), after the guards and before anything is written, in the
 * writing transaction. The protected grants of security (decision D48) are the first user. Platform code only.
 */
public interface TemporalWriteCheck {

    /**
     * @param timeline  the versions of the instance as stored now (empty for an insertion)
     * @param versions  the versions the write would insert, the written one first
     * @param grant     what the platform attached to this one write ({@code ChangeSet.Change#grant()}); null for
     *                  ordinary writes
     * @return why the write is refused (422); empty to let it pass. May fail instead (for example with 403)
     */
    Mono<List<Violation>> check(StorageEngine engine, EntityDefinition def, UUID id, Timeline timeline,
        List<PlannedVersion> versions, Operation operation, Object grant);
}
