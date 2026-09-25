package com.jabiz.app.it;

import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.dictionary.DictItem;
import com.jabiz.runtime.dictionary.DictionaryRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Dictionary registry (docs/design/02-metamodel.md section 5): sources, labels, caching and invalidation. */
class DictionaryRegistryIT extends PostgresIntegrationTest {

    private static final String PORTS = "urn:jabiz:dict:customs_port";
    private static final String UNITS = "urn:jabiz:dict:it_unit";

    @Autowired
    DictionaryRegistry dictionaries;

    @BeforeEach
    void reset() {
        execute("DELETE FROM sys_dict_item WHERE dict_urn = ?", UNITS);
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
            + "(?, 'T', '{}', 3, true)", UNITS, UNITS, UNITS);

        assertThat(items(UNITS, Locale.JAPANESE)).extracting(DictItem::code, DictItem::label, DictItem::enabled)
            .containsExactly(tuple("LB", "Pound", false), tuple("KG", "キログラム", true), tuple("T", "T", true));
        assertThat(items(UNITS, Locale.forLanguageTag("fr"))).extracting(DictItem::label)
            .containsExactly("Pound", "Kilogram", "T");
        // Disabled codes stay readable but are not accepted as input.
        assertThat(enabled(UNITS)).containsExactlyInAnyOrder("KG", "T");
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

    @Test
    void changesOfTheTableEvictTheCacheThroughNotify() {
        assertThat(enabled(PORTS)).containsExactlyInAnyOrder("JPTYO", "JPYOK", "JPOSA");

        execute("INSERT INTO sys_dict_item (dict_urn, item_code, labels) VALUES (?, 'JPNGO', '{\"en\": \"Nagoya\"}')",
            PORTS);
        awaitTrue(() -> enabled(PORTS).contains("JPNGO"));

        execute("UPDATE sys_dict_item SET enabled = false WHERE dict_urn = ? AND item_code = 'JPNGO'", PORTS);
        awaitTrue(() -> !enabled(PORTS).contains("JPNGO"));
        execute("DELETE FROM sys_dict_item WHERE dict_urn = ? AND item_code = 'JPNGO'", PORTS);
    }

    @Test
    void theListenerReconnectsAfterLosingItsConnection() {
        assertThat(enabled(UNITS)).isEmpty();
        List<?> terminated = query("SELECT pg_terminate_backend(pid) AS done FROM pg_stat_activity "
            + "WHERE query = 'LISTEN " + DictionaryRegistry.CHANNEL + "' AND pid <> pg_backend_pid()");
        assertThat(terminated).isNotEmpty();

        // Once reconnected, the listener evicts everything and then follows new notifications again.
        execute("INSERT INTO sys_dict_item (dict_urn, item_code) VALUES (?, 'M')", UNITS);
        awaitTrue(() -> enabled(UNITS).contains("M"));
        execute("INSERT INTO sys_dict_item (dict_urn, item_code) VALUES (?, 'CM')", UNITS);
        awaitTrue(() -> enabled(UNITS).contains("CM"));
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
