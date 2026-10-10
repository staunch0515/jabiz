package com.jabiz.quizbuks.country;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The regions publications are aimed at (docs/quizbuks/02-design.md section 3.1, default answer to Q7). Every country
 * is in {@link #GLOBAL}; Japan, China and the United States are regions of their own; the rest go by continent, and a
 * country may be in several. A set of regions is written as its codes in the order of {@link #ALL}, joined by commas
 * ({@code GLOBAL,JP,ASIA}): the platform has no multi-valued field, and one order makes equal sets equal text.
 */
public final class Regions {

    public static final String GLOBAL = "GLOBAL";
    public static final String JP = "JP";
    public static final String CN = "CN";
    public static final String US = "US";
    public static final String EUROPE = "EUROPE";
    public static final String ASIA = "ASIA";
    public static final String NORTH_AMERICA = "NORTH_AMERICA";
    public static final String SOUTH_AMERICA = "SOUTH_AMERICA";

    /** Every region, in the order sets of them are written in. */
    public static final List<String> ALL = List.of(GLOBAL, JP, CN, US, EUROPE, ASIA, NORTH_AMERICA, SOUTH_AMERICA);

    /** The dictionary of the regions' names. */
    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:region";

    /** A written set of regions: one or more codes joined by commas. */
    public static final Pattern SET = Pattern.compile("(" + String.join("|", ALL) + ")(,(" + String.join("|", ALL)
        + "))*");

    /** Longest written set: every region once. */
    public static final int MAX_LENGTH = String.join(",", ALL).length();

    /**
     * The written form of a set of regions, always with {@link #GLOBAL}.
     *
     * @throws IllegalArgumentException naming a code that is not a region
     */
    public static String withGlobal(Collection<String> regions) {
        Set<String> wanted = new LinkedHashSet<>(regions);
        wanted.add(GLOBAL);
        List<String> unknown = wanted.stream().filter(r -> !ALL.contains(r)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Not regions: " + unknown);
        }
        return ALL.stream().filter(wanted::contains).collect(Collectors.joining(","));
    }

    /** The regions of a written set. */
    public static List<String> parse(String written) {
        if (written == null || written.isBlank()) {
            return List.of();
        }
        return List.of(written.split(","));
    }

    private Regions() {}
}
