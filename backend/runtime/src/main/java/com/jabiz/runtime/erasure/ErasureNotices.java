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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the applications' erasers want a user to know before the account is deleted (decision D62 item 7;
 * docs/design/10-security.md section 17.5): reads the data their {@link PersonalDataEraser#loads()} declare (rows of a
 * dataset whose user field is the user, at most {@link ErasureSubject#MAX_ROWS} each; more is an error, never cut
 * short), then asks every eraser. The text of a notice is the message {@code erasure.notice.<code>}.
 */
@Component
public class ErasureNotices {

    /** Prefix of a notice's message, and of the violation code a request that did not acknowledge it gets. */
    public static final String MESSAGE_PREFIX = "erasure.notice.";

    /** A notice as shown: its code, to acknowledge, and its text in the request's language. */
    public record View(String code, String message) {}

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
        return load(userId).map(data -> {
            ErasureSubject subject = new ErasureSubject(userId, data);
            Map<String, ErasureNotice> notices = new LinkedHashMap<>();
            for (PersonalDataEraser eraser : erasers.all()) {
                eraser.notices(subject).forEach(notice -> notices.putIfAbsent(notice.code(), notice));
            }
            return List.copyOf(notices.values());
        });
    }

    /** The notices as the user reads them. */
    public List<View> views(List<ErasureNotice> notices, Locale locale) {
        return notices.stream().map(notice -> new View(notice.code(), messages.message(violation(notice), locale)))
            .toList();
    }

    /** The violation a request gets for a notice it did not acknowledge: its text is the notice's. */
    public static Violation violation(ErasureNotice notice) {
        return new Violation("acknowledgedNotices", MESSAGE_PREFIX + notice.code(), notice.code(), notice.params());
    }

    private Mono<Map<String, List<Map<String, Object>>>> load(String userId) {
        Map<String, ErasureLoad> loads = new LinkedHashMap<>();
        erasers.all().forEach(eraser -> eraser.loads().forEach(load -> loads.putIfAbsent(load.name(), load)));
        return Flux.fromIterable(loads.values())
            .concatMap(load -> rows(load, userId).map(rows -> Map.entry(load.name(), rows)))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
            .map(map -> (Map<String, List<Map<String, Object>>>) map);
    }

    private Mono<List<Map<String, Object>>> rows(ErasureLoad load, String userId) {
        DatasetDefinition dataset = datasets.findById(load.dataset())
            .orElseThrow(() -> new IllegalStateException("Unknown dataset " + load.dataset()));
        EntityDefinition def = definitions.getOrThrow(dataset.targetEntityType());
        // The id as text, converted to the field's type by the query compiler; one past the limit, so that more rows
        // than allowed are noticed instead of cut off.
        EntityQuery query = EntityQuery.builder().where(new QueryPredicate.Eq(load.userField(), userId))
            .limit(ErasureSubject.MAX_ROWS + 1).build();
        return entities.queryAll(dataset, def, query).map(EntityInstance::attributes).collectList()
            .flatMap(rows -> rows.size() > ErasureSubject.MAX_ROWS
                ? Mono.error(new IllegalStateException("Load " + load.name() + " found more than "
                    + ErasureSubject.MAX_ROWS + " rows"))
                : Mono.just(List.copyOf(rows)));
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
