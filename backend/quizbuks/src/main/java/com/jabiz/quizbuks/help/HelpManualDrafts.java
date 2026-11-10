package com.jabiz.quizbuks.help;

import com.jabiz.approval.ContentHash;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The usage manuals as the repository has them (F-C10b): {@code quizbuks/help/manual-<entry>.<language>.md}, one per
 * entry ({@code app}: the Takers', {@code sponsor}: the Sponsors') and language. They are the first drafts
 * {@code QB_SETUP} creates and what {@code QB_HELP_MANUAL_LOAD} loads into a draft again; the AI reads only what a
 * content administrator published ({@code QbHelpManual.publishedBody}). A pull request that adds or changes a feature
 * changes these files (backend/quizbuks/CLAUDE.md); the startup check {@value HelpManualSourceCheck#LOCATION} then
 * tells the administrators to load and publish it.
 */
public final class HelpManualDrafts {

    static final String RESOURCE = "quizbuks/help/manual-%s.%s.md";

    /**
     * Read once, when the class is loaded at startup ({@link HelpMaterialsConfig}): the processes that use them run on
     * the request path, which never reads files.
     */
    private static final Map<String, Map<String, String>> MANUALS = load();

    private HelpManualDrafts() {}

    /** The repository's manual of the entry, by language (each language there is). */
    public static Map<String, String> of(String entry) {
        return MANUALS.getOrDefault(entry, Map.of());
    }

    private static Map<String, Map<String, String>> load() {
        Map<String, Map<String, String>> manuals = new LinkedHashMap<>();
        for (String entry : QbHelp.ENTRIES) {
            Map<String, String> texts = new LinkedHashMap<>();
            for (String language : QbHelpMaterials.LANGUAGES) {
                String text = read(RESOURCE.formatted(entry, language));
                if (text != null) {
                    texts.put(language, text);
                }
            }
            manuals.put(entry, java.util.Collections.unmodifiableMap(texts));
        }
        return Map.copyOf(manuals);
    }

    /** The hash of a manual's texts (by language, in a fixed order): what {@code sourceHash} records. */
    public static String hash(Map<?, ?> texts) {
        Map<String, Object> sorted = new TreeMap<>();
        if (texts != null) {
            texts.forEach((language, text) -> sorted.put(String.valueOf(language), text));
        }
        return ContentHash.of(Map.of("body", sorted));
    }

    private static String read(String resource) {
        try (InputStream in = HelpManualDrafts.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
