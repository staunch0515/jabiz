package com.jabiz.app.it;

import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.dictionary.DictItem;
import com.jabiz.dictionary.DictionaryProvider;
import com.jabiz.entity.SemanticKind;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.dictionary.DictionaryRegistry;
import com.jabiz.runtime.dictionary.SqlDictionary;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Dictionary registry (docs/design/02-metamodel.md section 5): sources, labels, caching and invalidation. */
class DictionaryRegistryIT extends PostgresIntegrationTest {

    private static final String PORTS = "urn:jabiz:dict:customs_port";
    private static final AtomicInteger RUN = new AtomicInteger();

    /** Dictionary items are append-only, so every test works with a dictionary of its own. */
    private String units;

    @Autowired
    DictionaryRegistry dictionaries;

    @Autowired
    EntityDefinitionRegistry entities;

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    MessageCatalog messages;

    @Autowired
    DatasetEntityManager entityManager;

    @BeforeEach
    void reset() {
        units = "urn:jabiz:dict:it_unit_" + RUN.incrementAndGet();
        execute("DELETE FROM it_ticket");
        dictionaries.invalidate("*");
    }

    private List<DictItem> items(String urn, Locale locale) {
        return asTestRequest(dictionaries.items(urn, locale)).block();
    }

    private Set<String> enabled(String urn) {
        return asTestRequest(dictionaries.enabledCodes(urn)).block();
    }

    @Test
    void databaseDictionaryLabelsFallBackToTheDefaultLanguage() {
        execute("INSERT INTO sys_dict_item (dict_urn, item_code, labels, sort_order, enabled) VALUES "
            + "(?, 'KG', '{\"en\": \"Kilogram\", \"ja\": \"キログラム\"}', 2, true), "
            + "(?, 'LB', '{\"en\": \"Pound\"}', 1, false), "
            + "(?, 'T', '{}', 3, true)", units, units, units);

        assertThat(items(units, Locale.JAPANESE)).extracting(DictItem::code, DictItem::label, DictItem::enabled)
            .containsExactly(tuple("LB", "Pound", false), tuple("KG", "キログラム", true), tuple("T", "T", true));
        assertThat(items(units, Locale.forLanguageTag("fr"))).extracting(DictItem::label)
            .containsExactly("Pound", "Kilogram", "T");
        // Disabled codes stay readable but are not accepted as input.
        assertThat(enabled(units)).containsExactlyInAnyOrder("KG", "T");
    }

    @Test
    void businessProvidersAndFixedValuesAreDictionariesToo() {
        assertThat(items(ItFixtures.COLOR_DICTIONARY, Locale.JAPANESE)).extracting(DictItem::label)
            .containsExactly("赤", "青");
        assertThat(enabled(ItFixtures.COLOR_DICTIONARY)).containsExactly("RED");
        // ItTicket.status declares its codes; labels are the codes.
        assertThat(items("urn:jabiz:dict:it_ticket_status", Locale.ENGLISH)).extracting(DictItem::label)
            .containsExactly("OPEN", "IN_PROGRESS", "DONE");
        assertThat(items("urn:jabiz:dict:unknown", Locale.ENGLISH)).isEmpty();
        assertThat(enabled("urn:jabiz:dict:unknown")).isEmpty();
    }

    @Test
    void sqlDictionaryRunsItsTemplatePerLanguage() {
        execute("INSERT INTO it_ticket (f_id, f_title, f_created_at) VALUES ('T-2', 'Second', now()), "
            + "('T-1', 'First', now())");

        assertThat(items(ItFixtures.TICKET_TITLE_DICTIONARY, Locale.JAPANESE))
            .extracting(DictItem::code, DictItem::label)
            .containsExactly(tuple("T-1", "First [ja]"), tuple("T-2", "Second [ja]"));
        assertThat(items(ItFixtures.TICKET_TITLE_DICTIONARY, Locale.CHINESE).getFirst().label()).isEqualTo("First [zh]");
    }

    /** Review finding: URNs from requests must not grow the cache unless the table knows them. */
    @Test
    void unknownDictionariesAreNotCached() {
        int before = dictionaries.cachedDictionaries();
        for (int i = 0; i < 20; i++) {
            assertThat(items("urn:made:up:" + i, Locale.ENGLISH)).isEmpty();
        }
        assertThat(dictionaries.cachedDictionaries()).isEqualTo(before);

        execute("INSERT INTO sys_dict_item (dict_urn, item_code) VALUES (?, 'KG')", units);
        assertThat(enabled(units)).containsExactly("KG");
        assertThat(dictionaries.cachedDictionaries()).isEqualTo(before + 1);
    }

    /** Review finding: an SQL dictionary naming an unknown dataset or entity fails at startup, not on first use. */
    @Test
    void sqlDictionariesAreCheckedWhenTheRegistryIsBuilt() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("broken", new SqlDictionary("urn:jabiz:dict:broken", "urn:jabiz:dataset:nope",
            AdvancedQueryDefinition.define("broken", q -> q.fromEntities("Ghost")
                .returns("code", new SemanticKind.Text(null, false))
                .returns("label", new SemanticKind.Text(null, false))
                .sqlTemplate("SELECT 1")), null));

        assertThatThrownBy(() -> new DictionaryRegistry(beans.getBeanProvider(DictionaryProvider.class),
            beans.getBeanProvider(SqlDictionary.class), entities, datasets, null,
            beans.getBeanProvider(AdvancedQueryExecutor.class), messages, clock))
            .hasMessageContaining("SQL dictionary urn:jabiz:dict:broken refers to unknown dataset urn:jabiz:dataset:nope")
            .hasMessageContaining("SQL dictionary urn:jabiz:dict:broken refers to unregistered entity Ghost");
    }

    @Test
    void seededPortsAreBaseData() {
        assertThat(enabled(PORTS)).containsExactlyInAnyOrder("JPTYO", "JPYOK", "JPOSA");
        assertThat(items(PORTS, Locale.JAPANESE)).extracting(DictItem::label)
            .containsExactly("東京港", "横浜港", "大阪港");
        // Seeded from SQL: effective and recorded at the epoch, no database clock involved.
        assertThat(query("SELECT DISTINCT extract(epoch FROM created_time) AS t, extract(epoch FROM effect_start_time) AS e "
            + "FROM sys_dict_item_version WHERE dict_urn = ?", PORTS)).singleElement()
            .satisfies(row -> assertThat(row.values()).allSatisfy(v -> assertThat(((Number) v).intValue()).isZero()));
    }

    @Test
    void changesOfTheTableEvictTheCacheThroughNotify() {
        execute("INSERT INTO sys_dict_item (dict_urn, item_code, labels) VALUES (?, 'KG', '{\"en\": \"Kilogram\"}')",
            units);
        assertThat(enabled(units)).containsExactly("KG");

        execute("INSERT INTO sys_dict_item (dict_urn, item_code, labels) VALUES (?, 'LB', '{\"en\": \"Pound\"}')",
            units);
        awaitTrue(() -> enabled(units).contains("LB"));

        // Putting an existing item again appends a corrected version of its base data.
        execute("INSERT INTO sys_dict_item (dict_urn, item_code, labels, enabled) VALUES (?, 'LB', '{}', false)",
            units);
        awaitTrue(() -> !enabled(units).contains("LB"));
        assertThat(query("SELECT version_no FROM sys_dict_item_version WHERE dict_urn = ? AND item_code = 'LB' "
            + "ORDER BY version_no", units)).extracting(row -> row.get("version_no")).containsExactly(1, 2);
    }

    /** Dictionary items are temporal: a scheduled label takes effect with the clock, without an eviction. */
    @Test
    void scheduledChangesOfDatabaseDictionariesTakeEffectOnTime() {
        execute("INSERT INTO sys_dict_item (dict_urn, item_code, labels) VALUES (?, 'KG', '{\"en\": \"Kilogram\"}')",
            units);
        assertThat(items(units, Locale.ENGLISH)).extracting(DictItem::label).containsExactly("Kilogram");
        Map<String, Object> item = query("SELECT dict_item_id, version_no FROM sys_dict_item_version WHERE dict_urn = ?",
            units).getFirst();
        assertThat(dictionaries.isCached(units)).isTrue();

        Instant tomorrow = clock.instant().plus(Duration.ofDays(1));
        asTestRequest(entityManager.commitBatch(datasets.findById("urn:jabiz:dataset:platform:SysDictItem").orElseThrow(),
            List.of(new EntityChange(EntityAction.UPDATE, new EntityInstance(item.get("dict_item_id"), "SysDictItem",
                ((Number) item.get("version_no")).longValue(), null, Map.of("labels", Map.of("en", "Kilo"))), tomorrow))))
            .block();
        // The write is announced; once the entry is evicted it is read again, now knowing when it expires. The
        // entry itself is watched, not the cache size: the notification of the insert above may arrive at any time.
        awaitTrue(() -> !dictionaries.isCached(units));
        assertThat(items(units, Locale.ENGLISH)).extracting(DictItem::label).containsExactly("Kilogram");

        clock.advance(Duration.ofDays(1));
        assertThat(items(units, Locale.ENGLISH)).extracting(DictItem::label).containsExactly("Kilo");
    }

    @Test
    void theViewRefusesUpdatesAndDeletions() {
        execute("INSERT INTO sys_dict_item (dict_urn, item_code) VALUES (?, 'KG')", units);
        assertThatThrownBy(() -> execute("UPDATE sys_dict_item SET enabled = false WHERE dict_urn = ?", units))
            .hasMessageContaining("sys_dict_item");
        assertThatThrownBy(() -> execute("DELETE FROM sys_dict_item WHERE dict_urn = ?", units))
            .hasMessageContaining("sys_dict_item");
        assertThatThrownBy(() -> execute("DELETE FROM sys_dict_item_version WHERE dict_urn = ?", units))
            .hasMessageContaining("append-only");
    }

    @Test
    void theListenerReconnectsAfterLosingItsConnection() {
        assertThat(enabled(units)).isEmpty();
        List<?> terminated = query("SELECT pg_terminate_backend(pid) AS done FROM pg_stat_activity "
            + "WHERE query = 'LISTEN " + DictionaryRegistry.CHANNEL + "' AND pid <> pg_backend_pid()");
        assertThat(terminated).isNotEmpty();

        // Once reconnected, the listener evicts everything and then follows new notifications again.
        execute("INSERT INTO sys_dict_item (dict_urn, item_code) VALUES (?, 'M')", units);
        awaitTrue(() -> enabled(units).contains("M"));
        execute("INSERT INTO sys_dict_item (dict_urn, item_code) VALUES (?, 'CM')", units);
        awaitTrue(() -> enabled(units).contains("CM"));
    }

    private static void awaitTrue(Supplier<Boolean> condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (!condition.get()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 15 s");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
