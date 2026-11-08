package com.jabiz.quizbuks.topic;

import com.jabiz.entity.Violation;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of topics (F-S9, docs/quizbuks/plans/impl/F-S9.md), in one place and pure Java:
 * <ul>
 *   <li>a <b>word</b> of the platform's word list ({@link QbTopics}) is named by its code; a quiz, a publication and
 *       a taker's liked topics hold codes, comma-separated in the order chosen;</li>
 *   <li>a <b>custom word</b> is the sponsor's own text, kept as a JSON array; it is for search only (F-S6), never for
 *       strict mode, statistics or matching;</li>
 *   <li>a publication inherits its version's topics and adds or removes some ({@link Changes});</li>
 *   <li>a word merged into another counts as that other wherever words are compared ({@link #resolve}); a word
 *       deactivated or merged stays where it was chosen, but is not chosen anew ({@link #selectable}).</li>
 * </ul>
 * The template {@code qb.app.feed} writes {@link #strictAllows} in SQL (the comparison test {@code TopicIT} holds the
 * two together); F-S13's push will apply the same condition.
 */
public final class TopicRules {

    /** A word's code: lower-case letters and digits, words joined by single hyphens. */
    public static final Pattern CODE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    public static final int CODE_MIN_LENGTH = 2;
    public static final int CODE_LENGTH = 40;
    /** The most characters of a word's display name in one language. */
    public static final int NAME_LENGTH = 40;
    /** The ceiling of {@code qb.topic.max-per-quiz}, whatever the parameter says: the columns are sized for it. */
    public static final int HARD_MAX = 30;
    /** The ceiling of {@code qb.topic.custom-max-length}. */
    public static final int CUSTOM_HARD_MAX_LENGTH = 60;
    /** The most topics a taker likes. */
    public static final int MAX_LIKED = 50;
    /** The parameters' declared values ({@code QbParams}), used before the setup made them. */
    public static final int DEFAULT_MAX_PER_QUIZ = 10;
    public static final int DEFAULT_CUSTOM_MAX_LENGTH = 30;

    /** Column lengths: codes of a quiz or card (a switch adds a version's to a publication's additions). */
    public static final int CODES_LENGTH = 2 * HARD_MAX * (CODE_LENGTH + 1);
    /** A JSON array of custom words (each at most the ceiling, escaped). */
    public static final int CUSTOM_LENGTH = 2 * HARD_MAX * (CUSTOM_HARD_MAX_LENGTH * 2 + 3);
    /** A publication's changes: four lists. */
    public static final int CHANGES_LENGTH = CODES_LENGTH + CUSTOM_LENGTH + 64;
    /** A taker's liked codes. */
    public static final int LIKED_LENGTH = MAX_LIKED * (CODE_LENGTH + 1);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, List<String>>> CHANGES = new TypeReference<>() {};
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private TopicRules() {
    }

    // ---- codes and custom words as stored -----------------------------------------------------------------------

    /** Whether a text is a code a new word may have. */
    public static boolean validCode(String code) {
        return code != null && code.length() >= CODE_MIN_LENGTH && code.length() <= CODE_LENGTH
            && CODE.matcher(code).matches();
    }

    /** The codes of a column (comma-separated), in order, each once; none for null or blank. */
    public static List<String> codes(Object column) {
        if (column == null) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String part : column.toString().split(",")) {
            String code = part.strip();
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }

    /** The column of codes: comma-separated, each once; null when there are none. */
    public static String joinCodes(Collection<String> codes) {
        Set<String> unique = new LinkedHashSet<>();
        if (codes != null) {
            codes.stream().filter(Objects::nonNull).map(String::strip).filter(code -> !code.isEmpty())
                .forEach(unique::add);
        }
        return unique.isEmpty() ? null : String.join(",", unique);
    }

    /** The custom words of a column (a JSON array); none for null, blank or what is not one. */
    public static List<String> custom(Object column) {
        if (column == null || column.toString().isBlank()) {
            return List.of();
        }
        try {
            List<String> words = JSON.readValue(column.toString(), LIST);
            return words == null ? List.of() : uniqueWords(words);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The column of custom words: a JSON array, each once (ignoring case); null when there are none. */
    public static String customJson(Collection<String> words) {
        List<String> unique = uniqueWords(words == null ? List.of() : words);
        return unique.isEmpty() ? null : JSON.writeValueAsString(unique);
    }

    /**
     * A custom word as kept: Unicode NFC, blanks around it dropped and runs of blanks inside made one space; null when
     * nothing is left.
     */
    public static String normalized(String word) {
        if (word == null) {
            return null;
        }
        String text = SPACES.matcher(Normalizer.normalize(word, Normalizer.Form.NFC).strip()).replaceAll(" ");
        return text.isEmpty() ? null : text;
    }

    /** How a custom word is compared: two words differing in case only are one. */
    public static String fold(String word) {
        return word == null ? null : word.toLowerCase(Locale.ROOT);
    }

    private static List<String> uniqueWords(Collection<String> words) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String word : words) {
            String text = normalized(word);
            if (text != null) {
                unique.putIfAbsent(fold(text), text);
            }
        }
        return List.copyOf(unique.values());
    }

    // ---- the topics of a version, a publication's changes and its card --------------------------------------------

    /** Topics: the words' codes and the custom words. */
    public record Topics(List<String> codes, List<String> custom) {
        public static final Topics NONE = new Topics(List.of(), List.of());

        public Topics {
            codes = List.copyOf(codes == null ? List.of() : codes);
            custom = List.copyOf(custom == null ? List.of() : custom);
        }

        /** The topics two columns hold. */
        public static Topics of(Object codes, Object custom) {
            return new Topics(TopicRules.codes(codes), TopicRules.custom(custom));
        }

        public int size() {
            return codes.size() + custom.size();
        }

        /** The codes column. */
        public String codesColumn() {
            return joinCodes(codes);
        }

        /** The custom words column. */
        public String customColumn() {
            return customJson(custom);
        }
    }

    /**
     * What a publication adds to and removes from its version's topics. Kept as a JSON object of its non-empty
     * lists ({@code add}, {@code remove}, {@code customAdd}, {@code customRemove}); none when it changes nothing.
     */
    public record Changes(List<String> add, List<String> remove, List<String> customAdd, List<String> customRemove) {
        public static final Changes NONE = new Changes(List.of(), List.of(), List.of(), List.of());

        public Changes {
            add = List.copyOf(add == null ? List.of() : codes(String.join(",", add)));
            remove = List.copyOf(remove == null ? List.of() : codes(String.join(",", remove)));
            customAdd = uniqueWords(customAdd == null ? List.of() : customAdd);
            customRemove = uniqueWords(customRemove == null ? List.of() : customRemove);
        }

        public boolean isEmpty() {
            return add.isEmpty() && remove.isEmpty() && customAdd.isEmpty() && customRemove.isEmpty();
        }

        /** The column: null when nothing changes. */
        public String json() {
            if (isEmpty()) {
                return null;
            }
            Map<String, List<String>> map = new LinkedHashMap<>();
            putIfAny(map, "add", add);
            putIfAny(map, "remove", remove);
            putIfAny(map, "customAdd", customAdd);
            putIfAny(map, "customRemove", customRemove);
            return JSON.writeValueAsString(map);
        }

        private static void putIfAny(Map<String, List<String>> map, String key, List<String> list) {
            if (!list.isEmpty()) {
                map.put(key, list);
            }
        }

        /** The changes a column holds; none for null or what cannot be read. */
        public static Changes of(Object column) {
            if (column == null || column.toString().isBlank()) {
                return NONE;
            }
            try {
                Map<String, List<String>> map = JSON.readValue(column.toString(), CHANGES);
                return map == null ? NONE : new Changes(map.get("add"), map.get("remove"), map.get("customAdd"),
                    map.get("customRemove"));
            } catch (RuntimeException e) {
                return NONE;
            }
        }

        /**
         * The same changes as they bear on {@code version}: only additions it lacks and removals it has (so that
         * what is stored says what the sponsor did to this version, and nothing twice).
         */
        public Changes against(Topics version) {
            Set<String> codes = new HashSet<>(version.codes());
            Set<String> custom = new HashSet<>();
            version.custom().forEach(word -> custom.add(fold(word)));
            return new Changes(add.stream().filter(code -> !codes.contains(code)).toList(),
                remove.stream().filter(codes::contains).toList(),
                customAdd.stream().filter(word -> !custom.contains(fold(word))).toList(),
                customRemove.stream().filter(word -> custom.contains(fold(word))).toList());
        }
    }

    /**
     * A publication's topics: its version's, then what it adds, without what it removes (a word removed and added is
     * added: the addition is the later act). The order is the version's, then the additions'.
     */
    public static Topics effective(Topics version, Changes changes) {
        Set<String> codes = new LinkedHashSet<>(version.codes());
        changes.remove().forEach(codes::remove);
        codes.addAll(changes.add());
        Map<String, String> custom = new LinkedHashMap<>();
        version.custom().forEach(word -> custom.put(fold(word), word));
        changes.customRemove().forEach(word -> custom.remove(fold(word)));
        changes.customAdd().forEach(word -> custom.putIfAbsent(fold(word), word));
        return new Topics(new ArrayList<>(codes), new ArrayList<>(custom.values()));
    }

    // ---- the word list ---------------------------------------------------------------------------------------------

    /** A word of the list as the rules need it: whether it is active and what it was merged into (none: itself). */
    public record Word(String code, boolean active, String mergedInto) {}

    /** The word a code stands for now: what it was merged into, else itself (merges are one step: the merge says so). */
    public static String resolve(String code, Map<String, Word> words) {
        Word word = words.get(code);
        return word == null || word.mergedInto() == null ? code : word.mergedInto();
    }

    /** Whether a word may be chosen anew: on the list, active and not merged into another. */
    public static boolean selectable(Word word) {
        return word != null && word.active() && word.mergedInto() == null;
    }

    /**
     * Strict mode (F-S9): whether a publication with these words is for a taker who likes {@code liked} and turned
     * strict mode on. Only words of the list count (a code not on it, a custom word, never does), each as the word it
     * stands for now; without strict mode every publication is.
     */
    public static boolean strictAllows(boolean strict, Collection<String> liked, Collection<String> cardCodes,
        Map<String, Word> words) {
        if (!strict) {
            return true;
        }
        Set<String> wanted = new HashSet<>();
        for (String code : liked) {
            if (words.containsKey(code)) {
                wanted.add(resolve(code, words));
            }
        }
        for (String code : cardCodes) {
            if (words.containsKey(code) && wanted.contains(resolve(code, words))) {
                return true;
            }
        }
        return false;
    }

    // ---- checking what is chosen ----------------------------------------------------------------------------------

    /** {@code qb.topic.max-per-quiz} as it counts: at least 1, at most {@link #HARD_MAX}. */
    public static int maxPerQuiz(Integer parameter) {
        return parameter == null ? DEFAULT_MAX_PER_QUIZ : Math.max(1, Math.min(HARD_MAX, parameter));
    }

    /** {@code qb.topic.custom-max-length} as it counts: at least 1, at most {@link #CUSTOM_HARD_MAX_LENGTH}. */
    public static int customMaxLength(Integer parameter) {
        return parameter == null ? DEFAULT_CUSTOM_MAX_LENGTH
            : Math.max(1, Math.min(CUSTOM_HARD_MAX_LENGTH, parameter));
    }

    /**
     * The problems of choosing {@code chosen} words where {@code before} were chosen: a word chosen anew must be
     * selectable; one kept may be deactivated or merged since (it was chosen while it could be).
     */
    public static List<Violation> choiceProblems(String field, Collection<String> chosen, Collection<String> before,
        Map<String, Word> words) {
        List<Violation> problems = new ArrayList<>();
        Set<String> kept = new HashSet<>(before);
        for (String code : chosen) {
            if (!kept.contains(code) && !selectable(words.get(code))) {
                problems.add(new Violation(field, TopicCodes.NOT_SELECTABLE, "Topic " + code + " is not on the word "
                    + "list, or is no longer chosen", Map.of("code", code)));
            }
        }
        return problems;
    }

    /** The problems of custom words given (before they are normalized): blank, too long or with control characters. */
    public static List<Violation> customProblems(String field, Collection<String> words, int maxLength) {
        List<Violation> problems = new ArrayList<>();
        for (String word : words) {
            String text = normalized(word);
            if (text == null || CONTROL.matcher(text).find()) {
                problems.add(new Violation(field, TopicCodes.CUSTOM_INVALID, "A custom topic is blank or holds "
                    + "control characters", Map.of()));
            } else if (text.codePointCount(0, text.length()) > maxLength) {
                problems.add(new Violation(field, TopicCodes.CUSTOM_LENGTH, "A custom topic has more than " + maxLength
                    + " characters", Map.of("max", maxLength, "word", text)));
            }
        }
        return problems;
    }

    /** The problem of having more topics than the parameter allows, if so. */
    public static List<Violation> countProblems(String field, Topics topics, int max) {
        return topics.size() > max ? List.of(new Violation(field, TopicCodes.TOO_MANY, "More than " + max
            + " topics", Map.of("max", max, "count", topics.size()))) : List.of();
    }
}
