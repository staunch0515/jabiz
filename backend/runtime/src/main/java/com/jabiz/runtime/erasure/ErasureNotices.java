package com.jabiz.runtime.erasure;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.security.erasure.ErasureLoad;
import com.jabiz.security.erasure.ErasureNotice;
import com.jabiz.security.erasure.ErasureSubject;
import com.jabiz.security.erasure.PersonalDataEraser;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the applications' erasers want a user to know before the account is deleted (decision D62 item 7;
 * docs/design/10-security.md section 17.5): reads the data their {@link PersonalDataEraser#loads()} declare (of a
 * dataset's rows whose user field is the user, with the load's fixed conditions: the rows, at most
 * {@link ErasureSubject#MAX_ROWS} each, more is an error, never cut short; or their count; or a field's total), then
 * asks every eraser. The text of a notice is the message {@code erasure.notice.<code>}.
 */
@Component
public class ErasureNotices {

    /** Prefix of a notice's message, and of the violation code a request that did not acknowledge it gets. */
    public static final String MESSAGE_PREFIX = "erasure.notice.";

    /** A notice as shown: its code, to acknowledge, and its text in the request's language. */
    public record NoticeView(String code, String message) {}

    private final PersonalDataErasers erasers;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry definitions;
    private final MessageCatalog messages;

    public ErasureNotices(PersonalDataErasers erasers, DatasetEntityManager entities, DatasetRegistry datasets,
        EntityDefinitionRegistry definitions, MessageCatalog messages) {
        this.erasers = erasers;
        this.entities = entities;
        this.datasets = datasets;
        this.definitions = definitions;
        this.messages = messages;
    }

    /** Every eraser's notices about the user, eraser by eraser; a code given twice counts once. */
    public Mono<List<ErasureNotice>> of(String userId) {
        if (erasers.all().isEmpty()) {
            return Mono.just(List.of());
        }
        return load(userId).map(subject -> {
            Map<String, ErasureNotice> notices = new LinkedHashMap<>();
            for (PersonalDataEraser eraser : erasers.all()) {
                eraser.notices(subject).forEach(notice -> notices.putIfAbsent(notice.code(), notice));
            }
            return List.copyOf(notices.values());
        });
    }

    /** The notices as the user reads them. */
    public List<NoticeView> views(List<ErasureNotice> notices, Locale locale) {
        return notices.stream().map(notice -> new NoticeView(notice.code(), messages.message(violation(notice), locale)))
            .toList();
    }

    /** The violation a request gets for a notice it did not acknowledge: its text is the notice's. */
    public static Violation violation(ErasureNotice notice) {
        return new Violation("acknowledgedNotices", MESSAGE_PREFIX + notice.code(), notice.code(), notice.params());
    }

    /** What the loads read, one statement each (phase 17zz22: rows, a count or a total). */
    private Mono<ErasureSubject> load(String userId) {
        Map<String, ErasureLoad> loads = new LinkedHashMap<>();
        erasers.all().forEach(eraser -> eraser.loads().forEach(load -> loads.putIfAbsent(load.name(), load)));
        // The maps are made for each subscription: a retry starts empty.
        return Mono.defer(() -> {
            Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
            Map<String, Long> counts = new LinkedHashMap<>();
            Map<String, BigDecimal> sums = new LinkedHashMap<>();
            return Flux.fromIterable(loads.values())
                .concatMap(load -> read(load, userId, rows, counts, sums))
                .then(Mono.fromSupplier(() -> new ErasureSubject(userId, rows, counts, sums)));
        });
    }

    private Mono<Void> read(ErasureLoad load, String userId, Map<String, List<Map<String, Object>>> rows,
        Map<String, Long> counts, Map<String, BigDecimal> sums) {
        DatasetDefinition dataset = datasets.findById(load.dataset())
            .orElseThrow(() -> new IllegalStateException("Unknown dataset " + load.dataset()));
        EntityDefinition def = definitions.getOrThrow(dataset.targetEntityType());
        return switch (load.kind()) {
            // One past the limit, so that more rows than allowed are noticed instead of cut off.
            case ROWS -> entities.queryAll(dataset, def, EntityQuery.builder().where(condition(load, userId))
                    .limit(ErasureSubject.MAX_ROWS + 1).build())
                .map(EntityInstance::attributes).collectList()
                .flatMap(found -> found.size() > ErasureSubject.MAX_ROWS
                    ? Mono.error(new IllegalStateException("Load " + load.name() + " found more than "
                        + ErasureSubject.MAX_ROWS + " rows"))
                    : Mono.fromRunnable(() -> rows.put(load.name(), List.copyOf(found))));
            // A count and a total ignore paging: the query's limit is the builder's default and has no effect.
            case COUNT -> entities.count(dataset, def, EntityQuery.builder().where(condition(load, userId)).build())
                .doOnNext(count -> counts.put(load.name(), count)).then();
            case SUM -> entities.sum(dataset, def, EntityQuery.builder().where(condition(load, userId)).build(),
                    load.field())
                .doOnNext(sum -> sums.put(load.name(), sum)).then();
        };
    }

    /**
     * The user's rows, with the load's fixed conditions: the user id as text and the conditions' constants, converted
     * to the fields' types by the query compiler (one value an equality, several an {@code IN}). No order: none is
     * needed to count, add up or show a few rows.
     */
    public static QueryPredicate condition(ErasureLoad load, String userId) {
        List<QueryPredicate> all = new ArrayList<>();
        all.add(new QueryPredicate.Eq(load.userField(), userId));
        for (ErasureLoad.Condition condition : load.conditions()) {
            all.add(condition.values().size() == 1
                ? new QueryPredicate.Eq(condition.field(), condition.values().getFirst())
                : new QueryPredicate.In(condition.field(), condition.values()));
        }
        return all.size() == 1 ? all.getFirst() : new QueryPredicate.And(all);
    }

    /** The codes of the notices that {@code acknowledged} does not name. */
    public static List<ErasureNotice> unacknowledged(List<ErasureNotice> notices, List<String> acknowledged) {
        List<ErasureNotice> missing = new ArrayList<>();
        for (ErasureNotice notice : notices) {
            if (acknowledged == null || !acknowledged.contains(notice.code())) {
                missing.add(notice);
            }
        }
        return missing;
    }
}
