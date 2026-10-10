package com.jabiz.runtime.temporal;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;

import java.util.List;

/**
 * A platform rule over every write of a temporal entity, whichever path it takes: the dataset API, the generic
 * entity processes, the change sets of processes, cancellations and reverts all append through
 * {@link VersionAppender}, which asks every guard before it writes anything. Platform code only; guards are
 * synchronous and must not perform I/O. The first user is the controlled business parameter (decision D40).
 */
public interface TemporalWriteGuard {

    /**
     * @param timeline  the versions of the instance as stored now (empty for an insertion)
     * @param versions  the versions the write would insert, the written one first
     * @param operation the operation writing them
     * @param grant     what the platform attached to this one write to let it pass
     *                  ({@code ChangeSet.Change#grant()}); null for ordinary writes
     * @return why the write is refused (422); empty to let it pass
     */
    List<Violation> check(EntityDefinition def, Timeline timeline, List<PlannedVersion> versions,
        Operation operation, Object grant);
}
