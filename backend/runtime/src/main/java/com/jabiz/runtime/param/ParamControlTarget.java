package com.jabiz.runtime.param;

import com.jabiz.entity.EntityValidator;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.ValidationContext;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.param.ParamKinds;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ControlChanges;
import com.jabiz.runtime.approval.ControlTarget;
import com.jabiz.runtime.dataset.DatasetRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Controlled business parameters as targets of controlled changes (decision D40, docs/design/18 section 3.5). A
 * change names the parameter by {@code values.paramKey} and sets its {@code value} and {@code description}; a change
 * that creates the parameter gives its {@code valueKind} as well. A deletion cancels the value scheduled at the
 * change's effective time. Only controlled keys take this way.
 *
 * <p>A change is based on the version in effect when it takes effect. The proposal records which parameter that is
 * (none: the change creates it); the publication refuses the change when that is no longer so
 * ({@code CONTROL_TARGET_CHANGED}) and checks everything again with the same rules: the key is controlled, the time
 * lies ahead, a cancelled value is scheduled ({@code NOT_SCHEDULED}), the value fits the parameter's kind
 * ({@code PARAM_VALUE_INVALID}) and the fields pass the entity's validation. Its one write carries a
 * {@link ParamWriteGrant}, the only way past {@link ControlledParamGuard}.
 */
@Component
public class ParamControlTarget implements ControlTarget {

    /** The fields a change of a parameter may give. */
    static final Set<String> WRITABLE = Set.of(ParamEntities.KEY, ParamEntities.KIND, ParamEntities.VALUE,
        ParamEntities.DESCRIPTION);

    private static final ParamKindSupport KINDS = new ParamKindSupport();

    /**
     * @param param     the version in effect when the change takes effect
     * @param scheduled for a cancellation: whether something takes effect exactly then
     */
    record Loaded(EntityInstance param, boolean scheduled) {}

    private final LoadParamVersion versions;
    private final DatasetRegistry datasets;
    private final DatasetEntityManager entityManager;
    private final ControlledParamRegistry controlled;

    ParamControlTarget(LoadParamVersion versions, DatasetRegistry datasets, DatasetEntityManager entityManager,
        ControlledParamRegistry controlled) {
        this.versions = versions;
        this.datasets = datasets;
        this.entityManager = entityManager;
        this.controlled = controlled;
    }

    @Override
    public Set<String> entities() {
        return Set.of(ParamEntities.ENTITY);
    }

    @Override
    public String dataset(String entity) {
        return ParamEntities.DATASET;
    }

    @Override
    public Mono<Object> load(Request request, ProcessContext ctx) {
        return Mono.defer(() -> {
            String key = key(request.values());
            if (key == null) {
                return Mono.empty();
            }
            Instant effective = request.effectiveTime();
            boolean cancels = request.delete() && effective != null && effective.isAfter(ctx.opTime());
            return versions.find(key, ParamProcesses.basedOn(effective, ctx.opTime()))
                .flatMap(param -> cancels
                    ? entityManager.hasChangeAt(datasets.findById(ParamEntities.DATASET).orElseThrow(),
                        ParamEntities.SYS_PARAM, UUID.fromString(String.valueOf(param.id())), effective)
                        .map(scheduled -> new Loaded(param, scheduled))
                    : Mono.just(new Loaded(param, false)))
                .map(Object.class::cast);
        });
    }

    @Override
    public Proposal propose(Request request, Object loaded, ProcessContext ctx) {
        if (request.targetId() != null) {
            ctx.reject(ControlChanges.invalid("targetId", "a parameter is named by values.paramKey, not by its id"));
            return null;
        }
        Loaded based = (Loaded) loaded;
        Map<String, Object> stored = check(request, based, ctx);
        return ctx.hasViolations() ? null : new Proposal(idOf(based), stored);
    }

    @Override
    public Object publish(Request request, Object loaded, ProcessContext ctx, ChangeSet.Target writes) {
        Loaded based = (Loaded) loaded;
        if (!Objects.equals(idOf(based), request.targetId())) {
            String key = String.valueOf(key(request.values()));
            ctx.reject(new Violation("changeId", PlatformErrorCodes.CONTROL_TARGET_CHANGED, request.targetId() == null
                ? "Parameter " + key + " was created after the change that creates it was proposed"
                : "Parameter " + key + " is not the one the change was proposed for", Map.of("key", key)));
            return null;
        }
        Map<String, Object> stored = check(request, based, ctx);
        if (ctx.hasViolations()) {
            return null;
        }
        ChangeSet.Target granted = writes.granted(
            new ParamWriteGrant((String) stored.get(ParamEntities.KEY), ctx.processSeqId()));
        if (request.delete()) {
            granted.cancelScheduled(ParamEntities.ENTITY, based.param().id(), based.param().version());
            return based.param().id();
        }
        if (based == null) {
            return granted.insert(ParamEntities.ENTITY, writes(stored, true));
        }
        granted.update(ParamEntities.ENTITY, based.param().id(), based.param().version(), writes(stored, false));
        return based.param().id();
    }

    /** The key a change names, or null. */
    static String key(Map<String, ?> values) {
        return values == null || !(values.get(ParamEntities.KEY) instanceof String key) ? null : key;
    }

    private static String idOf(Loaded based) {
        return based == null ? null : String.valueOf(based.param().id());
    }

    /**
     * Checks a change, rejecting what is wrong with it in {@code ctx}.
     *
     * @return the values to store with the change: the key, the canonical value and the other given fields
     */
    private Map<String, Object> check(Request request, Loaded based, ProcessContext ctx) {
        Map<String, Object> given = request.values();
        Map<String, Object> stored = new LinkedHashMap<>();
        new TreeSet<>(given.keySet()).stream().filter(field -> !WRITABLE.contains(field))
            .forEach(field -> ctx.reject(invalid("field '" + field + "' cannot be set")));
        String key = key(given);
        if (key == null) {
            ctx.reject(invalid("the parameter is named by values.paramKey"));
            return stored;
        }
        stored.put(ParamEntities.KEY, key);
        if (!controlled.controls(key)) {
            ctx.reject(invalid("parameter " + key + " is not controlled; it is changed by PARAM_SET or"
                + " PARAM_SCHEDULE"));
        }
        Instant effective = request.effectiveTime();
        if (effective != null && !effective.isAfter(ctx.opTime())) {
            ctx.reject(ParamProcesses.notFuture(effective));
        }
        if (request.delete()) {
            checkCancellation(given, based, effective, key, ctx);
            return stored;
        }
        EntityInstance target = based == null ? null : based.param();
        Map<String, ?> kindSpec = null;
        if (target == null) {
            if (given.get(ParamEntities.KIND) instanceof Map<?, ?> spec) {
                kindSpec = kindSpec(spec, ctx);
                if (kindSpec != null) {
                    stored.put(ParamEntities.KIND, kindSpec);
                }
            } else {
                ctx.reject(invalid("parameter " + key + " does not exist; a change that creates it gives its"
                    + " valueKind"));
            }
            if (!given.containsKey(ParamEntities.VALUE)) {
                ctx.reject(invalid("a change that creates parameter " + key + " gives its value"));
            }
        } else {
            kindSpec = target.get(ParamEntities.KIND);
            if (given.containsKey(ParamEntities.KIND) && !sameKind(given.get(ParamEntities.KIND), kindSpec)) {
                ctx.reject(invalid("the valueKind of parameter " + key + " cannot change"));
            }
            if (!given.containsKey(ParamEntities.VALUE) && !given.containsKey(ParamEntities.DESCRIPTION)) {
                ctx.reject(invalid("no values"));
            }
        }
        if (given.containsKey(ParamEntities.VALUE) && kindSpec != null) {
            String value = ParamProcesses.canonical(ctx, ParamKinds.parse(kindSpec), given.get(ParamEntities.VALUE),
                String.valueOf(kindSpec.get("type")));
            if (value != null) {
                stored.put(ParamEntities.VALUE, value);
            }
        }
        if (given.containsKey(ParamEntities.DESCRIPTION)) {
            stored.put(ParamEntities.DESCRIPTION, given.get(ParamEntities.DESCRIPTION));
        }
        // The validation the write itself gets, so that an approved change can always be published.
        ValidationContext validation = new ValidationContext(Clock.fixed(ctx.opTime(), ZoneOffset.UTC),
            ctx.request());
        EntityValidator.check(ParamEntities.SYS_PARAM, writes(stored, target == null), validation, false)
            .violations().forEach(ctx::reject);
        return stored;
    }

    private static void checkCancellation(Map<String, Object> given, Loaded based, Instant effective, String key,
        ProcessContext ctx) {
        if (effective == null) {
            ctx.reject(invalid("a deletion cancels the value scheduled at its effectiveTime; give it"));
        }
        if (given.size() > 1) {
            ctx.reject(invalid("a deletion gives no values but paramKey"));
        }
        if (based == null) {
            ctx.reject(invalid("parameter " + key + " does not exist"));
        } else if (effective != null && effective.isAfter(ctx.opTime()) && !based.scheduled()) {
            ctx.reject(new Violation("effectiveTime", PlatformErrorCodes.NOT_SCHEDULED,
                "Parameter " + key + " has no value scheduled at " + effective,
                Map.of("time", effective.toString())));
        }
    }

    /** The fields a published change writes: all of them for a new parameter, else the value and description. */
    static Map<String, Object> writes(Map<String, Object> stored, boolean create) {
        Map<String, Object> writes = new LinkedHashMap<>(stored);
        if (!create) {
            writes.remove(ParamEntities.KEY);
            writes.remove(ParamEntities.KIND);
        }
        return writes;
    }

    /** The kind as stored ({@link ParamKindSupport}); null after rejecting it. */
    @SuppressWarnings("unchecked")
    private static Map<String, ?> kindSpec(Object spec, ProcessContext ctx) {
        try {
            return (Map<String, ?>) KINDS.coerce(Map.of(), spec, true);
        } catch (IllegalArgumentException e) {
            ctx.reject(invalid("valueKind: " + e.getMessage()));
            return null;
        }
    }

    /**
     * Whether two kinds written as data are the same kind: both are brought into the stored form and read, so the
     * order of their entries and numbers written as text do not matter.
     */
    @SuppressWarnings("unchecked")
    static boolean sameKind(Object given, Object stored) {
        try {
            SemanticKind a = ParamKinds.parse((Map<String, ?>) KINDS.coerce(Map.of(), given, true));
            SemanticKind b = ParamKinds.parse((Map<String, ?>) KINDS.coerce(Map.of(), stored, true));
            return a.equals(b);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            return false;
        }
    }

    private static Violation invalid(String detail) {
        return ControlChanges.invalid("values", detail);
    }
}
