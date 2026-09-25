package com.jabiz.runtime.dictionary;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.DictItem;
import com.jabiz.dictionary.DictionaryProvider;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Resolves dictionaries (docs/design/02-metamodel.md section 5) from, in this order:
 * <ol>
 *   <li>business {@link DictionaryProvider} beans (including {@link StaticDictionary} beans), called on
 *       {@code boundedElastic} because they are synchronous and may block;</li>
 *   <li>the fixed values of {@code Code} fields (labels are the codes);</li>
 *   <li>{@link SqlDictionary} beans;</li>
 *   <li>the platform's temporal entity {@code SysDictItem} (table {@code sys_dict_item_version}), read at the
 *       time of the clock.</li>
 * </ol>
 * Entries are cached per dictionary, in every supported language at once. The cache of a dictionary is evicted
 * by {@link #invalidate} (driven by {@link DictionaryChangeListener}); SQL dictionaries also expire after their TTL,
 * database dictionaries when their next scheduled version takes effect.
 */
@Component
public class DictionaryRegistry {

    /** Channel on which dictionary changes are announced; the payload is the dictionary URN, or {@code *}. */
    public static final String CHANNEL = "jabiz_dict_changed";

    private static final Duration FOREVER = Duration.ofMillis(Long.MAX_VALUE);

    private final List<DictionaryProvider> providers;
    private final Map<String, StaticDictionary> implicit = new LinkedHashMap<>();
    private final Map<String, SqlDictionary> sqlDictionaries = new LinkedHashMap<>();
    private final DatabaseClient db;
    private final ObjectProvider<AdvancedQueryExecutor> queries;
    private final DatasetRegistry datasets;
    private final MessageCatalog messages;
    private final Clock clock;
    private final Map<String, Mono<Loaded>> cache = new ConcurrentHashMap<>();

    /** Entries of a dictionary in every language; {@code expiresAt} (clock time) is null when they do not expire. */
    private record Loaded(Map<Locale, List<DictItem>> items, Instant expiresAt) {
        static Loaded forever(Map<Locale, List<DictItem>> items) {
            return new Loaded(items, null);
        }

        boolean expiredAt(Instant now) {
            return expiresAt != null && !now.isBefore(expiresAt);
        }
    }

    public DictionaryRegistry(
        ObjectProvider<DictionaryProvider> providers,
        ObjectProvider<SqlDictionary> sqlDictionaries,
        EntityDefinitionRegistry entities,
        DatasetRegistry datasets,
        DatabaseClient db,
        ObjectProvider<AdvancedQueryExecutor> queries,
        MessageCatalog messages,
        Clock clock
    ) {
        this.providers = providers.orderedStream().toList();
        sqlDictionaries.orderedStream().forEach(dict -> {
            if (this.sqlDictionaries.putIfAbsent(dict.urn(), dict) != null) {
                throw new IllegalStateException("SQL dictionary " + dict.urn() + " is declared twice");
            }
        });
        for (EntityDefinition entity : entities.all()) {
            for (var field : entity.fields.values()) {
                if (field.kind() instanceof SemanticKind.Code code && !code.allowedValues().isEmpty()) {
                    implicit.merge(code.dictUrn(), StaticDictionary.ofCodes(code.dictUrn(), code.allowedValues()),
                        (a, b) -> a);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        for (SqlDictionary dict : this.sqlDictionaries.values()) {
            if (datasets.findById(dict.datasetId()).isEmpty()) {
                problems.add("SQL dictionary " + dict.urn() + " refers to unknown dataset " + dict.datasetId());
            }
            for (String entity : dict.query().participatingEntities()) {
                if (!entities.contains(entity)) {
                    problems.add("SQL dictionary " + dict.urn() + " refers to unregistered entity " + entity);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid SQL dictionaries:\n - " + String.join("\n - ", problems));
        }
        this.datasets = datasets;
        this.db = db;
        this.queries = queries;
        this.messages = messages;
        this.clock = clock;
    }

    /** Entries of the dictionary labelled in the supported language closest to {@code locale}. */
    public Mono<List<DictItem>> items(String dictUrn, Locale locale) {
        Locale language = messages.supported(locale);
        return load(dictUrn).map(all -> all.getOrDefault(language, List.of()));
    }

    /** Codes of the dictionary that are accepted as input. */
    public Mono<Set<String>> enabledCodes(String dictUrn) {
        return load(dictUrn).map(all -> all.getOrDefault(messages.defaultLocale(), List.of()).stream()
            .filter(DictItem::enabled)
            .map(DictItem::code)
            .collect(Collectors.toUnmodifiableSet()));
    }

    /** Enabled codes of several dictionaries at once. */
    public Mono<Map<String, Set<String>>> enabledCodes(Collection<String> dictUrns) {
        return Flux.fromIterable(new LinkedHashSet<>(dictUrns))
            .concatMap(urn -> enabledCodes(urn).map(codes -> Map.entry(urn, codes)))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /** Forgets the cached entries of a dictionary; {@code *} forgets all. */
    public void invalidate(String dictUrn) {
        if ("*".equals(dictUrn)) {
            cache.clear();
        } else {
            cache.remove(dictUrn);
        }
    }

    /** Number of dictionaries currently cached (for monitoring). */
    public int cachedDictionaries() {
        return cache.size();
    }

    /** Where the dictionary comes from, or empty when only the database table could provide it. */
    public Optional<String> declaredSource(String dictUrn) {
        if (providers.stream().anyMatch(p -> p.supports(dictUrn))) {
            return Optional.of("provider");
        }
        if (implicit.containsKey(dictUrn)) {
            return Optional.of("static");
        }
        if (sqlDictionaries.containsKey(dictUrn)) {
            return Optional.of("sql");
        }
        return Optional.empty();
    }

    /** Dictionaries that have items in {@code sys_dict_item_version}. */
    public Flux<String> databaseDictionaries() {
        return db.sql("SELECT DISTINCT dict_urn FROM sys_dict_item_version")
            .map((row, meta) -> row.get("dict_urn", String.class))
            .all();
    }

    private Mono<Map<Locale, List<DictItem>>> load(String dictUrn) {
        Mono<Loaded> cached = cache.get(dictUrn);
        if (cached != null) {
            return cached.flatMap(loaded -> {
                if (loaded.expiredAt(clock.instant())) {
                    // A scheduled version has taken effect since the entries were read.
                    cache.remove(dictUrn, cached);
                    return load(dictUrn);
                }
                return Mono.just(loaded.items());
            });
        }
        if (declaredSource(dictUrn).isEmpty()) {
            // Only the table can know this URN. The URN may come straight from a request, so it is cached only
            // once the table actually has entries; otherwise made-up URNs would grow the cache without bound.
            return fromTable(dictUrn).doOnNext(loaded -> {
                if (loaded.items().values().stream().anyMatch(items -> !items.isEmpty())) {
                    cache.putIfAbsent(dictUrn, Mono.just(loaded));
                }
            }).map(Loaded::items);
        }
        return cache.computeIfAbsent(dictUrn, urn -> {
            Mono<Loaded> loading = Mono.defer(() -> source(urn));
            SqlDictionary sql = providers.stream().noneMatch(p -> p.supports(urn)) && !implicit.containsKey(urn)
                ? sqlDictionaries.get(urn) : null;
            // A failed load is not cached, so the next call retries.
            Duration ttl = sql != null ? sql.ttl() : FOREVER;
            return loading.cache(value -> ttl, error -> Duration.ZERO, () -> Duration.ZERO);
        }).map(Loaded::items);
    }

    private Mono<Loaded> source(String urn) {
        Optional<DictionaryProvider> provider = providers.stream().filter(p -> p.supports(urn)).findFirst();
        if (provider.isPresent()) {
            return fromProvider(provider.get(), urn).subscribeOn(Schedulers.boundedElastic()).map(Loaded::forever);
        }
        if (implicit.containsKey(urn)) {
            return fromProvider(implicit.get(urn), urn).map(Loaded::forever);
        }
        if (sqlDictionaries.containsKey(urn)) {
            return fromSql(sqlDictionaries.get(urn)).map(Loaded::forever);
        }
        return fromTable(urn);
    }

    private Mono<Map<Locale, List<DictItem>>> fromProvider(DictionaryProvider provider, String urn) {
        return Mono.fromCallable(() -> {
            Map<Locale, List<DictItem>> all = new LinkedHashMap<>();
            for (Locale locale : messages.supportedLocales()) {
                all.put(locale, List.copyOf(provider.items(urn, locale)));
            }
            return all;
        });
    }

    private Mono<Map<Locale, List<DictItem>>> fromSql(SqlDictionary dict) {
        DatasetDefinition dataset = datasets.findById(dict.datasetId()).orElseThrow(() -> new IllegalStateException(
            "SQL dictionary " + dict.urn() + " refers to unknown dataset " + dict.datasetId()));
        AdvancedQueryExecutor executor = queries.getObject();
        RequestContext system = RequestContext.system(messages.defaultLocale(), "dictionary-" + dict.urn());
        return Flux.fromIterable(messages.supportedLocales())
            .concatMap(locale -> {
                Map<String, Object> params = dict.localized()
                    ? Map.of(SqlDictionary.LOCALE_PARAMETER, locale.getLanguage()) : Map.of();
                return executor.execute(dataset, dict.query(), params)
                    .map(DictionaryRegistry::toItem)
                    .collectList()
                    .map(items -> Map.entry(locale, sorted(items)));
            })
            .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
            .contextWrite(ctx -> RequestContexts.put(ctx, system));
    }

    private static DictItem toItem(SemanticRow row) {
        Object sortOrder = row.getRaw("sortOrder");
        Object enabled = row.getRaw("enabled");
        return new DictItem(String.valueOf(row.getRaw("code")), (String) row.getRaw("label"),
            sortOrder == null ? 0 : ((Number) sortOrder).intValue(),
            enabled == null || Boolean.TRUE.equals(enabled));
    }

    /**
     * Items of the dictionary in {@code sys_dict_item_version}: the versions in effect at the time of the clock,
     * without tombstones. The label of each language falls back to the default language; without either the code is
     * shown. The entries expire when the next scheduled version of the dictionary takes effect.
     */
    private Mono<Loaded> fromTable(String urn) {
        List<Locale> locales = messages.supportedLocales();
        String fallback = messages.defaultLocale().getLanguage();
        Instant now = clock.instant();
        StringBuilder sql = new StringBuilder("SELECT item_code, sort_order, enabled");
        for (int i = 0; i < locales.size(); i++) {
            sql.append(", COALESCE(labels ->> :lang").append(i).append(", labels ->> :fallback) AS label_").append(i);
        }
        // dict_urn never changes within an item, so it may restrict the versions before the current one is chosen.
        sql.append(" FROM (SELECT DISTINCT ON (dict_item_id) * FROM sys_dict_item_version")
            .append(" WHERE dict_urn = :urn AND effect_start_time <= :asOf")
            .append(" ORDER BY dict_item_id, effect_start_time DESC, version_no DESC) v")
            .append(" WHERE NOT is_deleted ORDER BY sort_order, item_code");
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString())
            .bind("urn", urn).bind("fallback", fallback).bind("asOf", now);
        for (int i = 0; i < locales.size(); i++) {
            spec = spec.bind("lang" + i, locales.get(i).getLanguage());
        }
        Mono<Map<Locale, List<DictItem>>> items = spec
            .map((row, meta) -> {
                String code = row.get("item_code", String.class);
                Integer sortOrder = row.get("sort_order", Integer.class);
                boolean enabled = Boolean.TRUE.equals(row.get("enabled", Boolean.class));
                List<DictItem> perLocale = new ArrayList<>(locales.size());
                for (int i = 0; i < locales.size(); i++) {
                    perLocale.add(new DictItem(code, row.get("label_" + i, String.class),
                        sortOrder == null ? 0 : sortOrder, enabled));
                }
                return perLocale;
            })
            .all()
            .collectList()
            .map(rows -> {
                Map<Locale, List<DictItem>> all = new LinkedHashMap<>();
                for (int i = 0; i < locales.size(); i++) {
                    int index = i;
                    all.put(locales.get(i), rows.stream().map(r -> r.get(index)).toList());
                }
                return all;
            });
        Mono<Optional<Instant>> nextChange = db.sql("SELECT min(effect_start_time) AS next FROM sys_dict_item_version "
                + "WHERE dict_urn = :urn AND effect_start_time > :asOf")
            .bind("urn", urn).bind("asOf", now)
            .map((row, meta) -> Optional.ofNullable(row.get("next", OffsetDateTime.class)).map(OffsetDateTime::toInstant))
            .one()
            .defaultIfEmpty(Optional.empty());
        return items.zipWith(nextChange, (all, next) -> new Loaded(all, next.orElse(null)));
    }

    private static List<DictItem> sorted(List<DictItem> items) {
        return items.stream().sorted(Comparator.comparingInt(DictItem::sortOrder)).toList();
    }
}
