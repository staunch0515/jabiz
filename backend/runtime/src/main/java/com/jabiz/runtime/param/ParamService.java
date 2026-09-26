package com.jabiz.runtime.param;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.param.ParamKinds;
import com.jabiz.param.ParamValues;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads business parameters as they were in effect at a given time (docs/design/04-temporal-append-only.md
 * section 9): {@code get(key, asOf)}. Values are converted by the kind stored with them ({@link ParamKinds}).
 * Business rules pass the time the business event happened as {@code asOf}, not the current time, so that a
 * scheduled change applies to events from its effective time on. Platform code; processes read parameters through
 * the step {@code LoadParams}. Needs the caller's request context, like every read through a dataset.
 */
@Service
public class ParamService {

    private final DatasetRegistry datasets;
    private final DatasetEntityManager entityManager;

    public ParamService(DatasetRegistry datasets, DatasetEntityManager entityManager) {
        this.datasets = datasets;
        this.entityManager = entityManager;
    }

    /**
     * The value of {@code key} in effect at {@code asOf}.
     *
     * @return the value; an error {@code PARAM_NOT_FOUND} (422) when none is in effect then
     */
    public Mono<Object> get(String key, Instant asOf) {
        return load(List.of(key), asOf).map(values -> values.values().get(key));
    }

    /**
     * The values of {@code keys} in effect at {@code asOf}.
     *
     * @return all of them; an error {@code PARAM_NOT_FOUND} (422) naming every key without a value then
     */
    public Mono<ParamValues> load(Collection<String> keys, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf must not be null");
        Set<String> wanted = new LinkedHashSet<>(keys);
        if (wanted.isEmpty()) {
            return Mono.just(new ParamValues(asOf, Map.of()));
        }
        DatasetDefinition dataset = datasets.findById(ParamEntities.DATASET).orElseThrow();
        EntityQuery query = EntityQuery.builder()
            .where(new QueryPredicate.In(ParamEntities.KEY, List.copyOf(wanted)))
            .limit(wanted.size())
            .build();
        return entityManager.query(dataset, ParamEntities.SYS_PARAM, query, asOf, null)
            .collectList()
            .map(found -> values(found, wanted, asOf));
    }

    private static ParamValues values(List<EntityInstance> found, Set<String> wanted, Instant asOf) {
        Map<String, Object> byKey = new LinkedHashMap<>();
        for (EntityInstance param : found) {
            byKey.put(param.get(ParamEntities.KEY), value(param));
        }
        List<Violation> missing = new ArrayList<>();
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String key : wanted) {
            if (byKey.containsKey(key)) {
                ordered.put(key, byKey.get(key));
            } else {
                missing.add(new Violation(null, PlatformErrorCodes.PARAM_NOT_FOUND,
                    "Parameter " + key + " has no value in effect at " + asOf,
                    Map.of("key", key, "asOf", asOf.toString())));
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessRuleViolationException(missing);
        }
        return new ParamValues(asOf, ordered);
    }

    @SuppressWarnings("unchecked")
    static Object value(EntityInstance param) {
        Map<String, ?> kind = param.get(ParamEntities.KIND);
        String text = param.get(ParamEntities.VALUE);
        return ParamKinds.value(ParamKinds.parse(kind), text);
    }
}
