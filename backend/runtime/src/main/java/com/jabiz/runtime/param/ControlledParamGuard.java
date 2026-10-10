package com.jabiz.runtime.param;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.temporal.TemporalWriteGuard;
import com.jabiz.temporal.EntityVersion;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Refuses every write of a controlled parameter (decision D40) but the one the publication of an approved
 * controlled change makes, which alone carries a {@link ParamWriteGrant} for that key and operation: the parameter
 * processes, the dataset API, the generic entity processes, other writes of any process (whatever its name, the
 * publishing operation included), cancellations and reverts all end here (422 {@code PARAM_CONTROLLED}). The key is
 * immutable, so the stored versions or the written one name it; a controlled key cannot be created, deleted or
 * brought back either.
 */
@Component
class ControlledParamGuard implements TemporalWriteGuard {

    private final ControlledParamRegistry controlled;

    ControlledParamGuard(ControlledParamRegistry controlled) {
        this.controlled = controlled;
    }

    @Override
    public List<Violation> check(EntityDefinition def, Timeline timeline, List<PlannedVersion> versions,
        Operation operation, Object grant) {
        if (!ParamEntities.ENTITY.equals(def.name)) {
            return List.of();
        }
        Object key = timeline.versions().stream().map(EntityVersion::state)
            .map(state -> state.get(ParamEntities.KEY)).filter(Objects::nonNull).findFirst()
            .orElseGet(() -> versions.isEmpty() ? null : versions.getFirst().state().get(ParamEntities.KEY));
        if (!controlled.controls(key)
            || grant instanceof ParamWriteGrant granted && granted.allows(key, operation.processSeqId())) {
            return List.of();
        }
        return List.of(new Violation("key", PlatformErrorCodes.PARAM_CONTROLLED, "Parameter " + key
            + " changes only through a controlled change that another person publishes (CONTROL_CHANGE_PROPOSE)",
            Map.of("key", String.valueOf(key))));
    }
}
