package com.jabiz.dictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Dictionary declared in code, with labels per language. A label missing for a language falls back to the
 * English one, then to the code.
 */
public final class StaticDictionary implements DictionaryProvider {

    /** One declared entry: code, labels by language, enabled flag. */
    public record Entry(String code, Map<Locale, String> labels, boolean enabled) {
        public Entry {
            Objects.requireNonNull(code, "code must not be null");
            Map<Locale, String> copy = new LinkedHashMap<>();
            labels.forEach((locale, label) -> copy.put(Locale.of(locale.getLanguage()), label));
            labels = Collections.unmodifiableMap(copy);
        }
    }

    private final String urn;
    private final List<Entry> entries;

    private StaticDictionary(String urn, List<Entry> entries) {
        if (urn == null || urn.isBlank()) {
            throw new IllegalArgumentException("dictionary urn must not be blank");
        }
        this.urn = urn;
        this.entries = List.copyOf(entries);
    }

    public static StaticDictionary define(String urn, Consumer<Builder> block) {
        Builder builder = new Builder();
        block.accept(builder);
        return new StaticDictionary(urn, builder.entries);
    }

    /** Dictionary whose labels are the codes themselves, as implied by {@code Code.allowedValues}. */
    public static StaticDictionary ofCodes(String urn, List<String> codes) {
        List<Entry> entries = codes.stream().map(code -> new Entry(code, Map.of(), true)).toList();
        return new StaticDictionary(urn, entries);
    }

    public String urn() {
        return urn;
    }

    public List<Entry> entries() {
        return entries;
    }

    @Override
    public boolean supports(String dictUrn) {
        return urn.equals(dictUrn);
    }

    @Override
    public List<DictItem> items(String dictUrn, Locale locale) {
        if (!supports(dictUrn)) {
            return List.of();
        }
        Locale language = Locale.of(locale.getLanguage());
        List<DictItem> items = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            String label = entry.labels().getOrDefault(language, entry.labels().get(Locale.ENGLISH));
            items.add(new DictItem(entry.code(), label, i, entry.enabled()));
        }
        return items;
    }

    public static final class Builder {
        private final List<Entry> entries = new ArrayList<>();

        /** Adds an enabled entry; labels are given as language, label pairs, for example {@code "en", "Open"}. */
        public Builder item(String code, String... languageLabelPairs) {
            return add(code, true, languageLabelPairs);
        }

        /** Adds a disabled entry, readable but no longer accepted as input. */
        public Builder disabledItem(String code, String... languageLabelPairs) {
            return add(code, false, languageLabelPairs);
        }

        private Builder add(String code, boolean enabled, String... pairs) {
            if (pairs.length % 2 != 0) {
                throw new IllegalArgumentException("labels must be language, label pairs");
            }
            if (entries.stream().anyMatch(e -> e.code().equals(code))) {
                throw new IllegalArgumentException("code '" + code + "' is declared twice");
            }
            Map<Locale, String> labels = new LinkedHashMap<>();
            for (int i = 0; i < pairs.length; i += 2) {
                labels.put(Locale.forLanguageTag(pairs[i]), pairs[i + 1]);
            }
            entries.add(new Entry(code, labels, enabled));
            return this;
        }
    }
}
