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

    /**
     * A set of regions in its one written form: GLOBAL first, then the others in the order of {@link #ALL}, each at
     * most once, joined by commas. Checked on the server and, as a portable pattern, by the client too.
     */
    public static final Pattern CANONICAL = Pattern.compile(GLOBAL + ALL.stream().skip(1)
        .map(region -> "(," + region + ")?").collect(Collectors.joining()));

    /**
     * The regions a publication is aimed at, in their one written form (docs/quizbuks/plans/Q4-publishing.md,
     * requirement 3): at least one region, each at most once, in the order of {@link #ALL}, joined by commas.
     * {@link #GLOBAL} is not implied: {@code GLOBAL} alone means everyone, {@code JP,ASIA} only those two regions.
     * Written as one alternative per first region, so the pattern stays portable to the client.
     */
    public static final Pattern TARGETS = Pattern.compile(java.util.stream.IntStream.range(0, ALL.size())
        .mapToObj(first -> ALL.get(first) + ALL.stream().skip(first + 1L).map(region -> "(," + region + ")?")
            .collect(Collectors.joining()))
        .collect(Collectors.joining("|", "(", ")")));

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

    /**
     * The written form of the regions a publication is aimed at ({@link #TARGETS}); {@link #GLOBAL} only when given.
     *
     * @throws IllegalArgumentException when there is no region, or naming a code that is not a region
     */
    public static String targets(Collection<String> regions) {
        Set<String> wanted = new LinkedHashSet<>(regions);
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("No region");
        }
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

    /**
     * Whether a taker whose country is in {@code countryRegions} is among those a publication is aimed at
     * ({@code publicationRegions}): the two written sets share a region (docs/quizbuks/plans/Q5-answering.md, D-Q5-6).
     * Every country is in {@link #GLOBAL}, so a publication aimed at {@code GLOBAL} reaches everyone. The feed template
     * writes the same condition as {@code string_to_array(..) && string_to_array(..)}.
     */
    public static boolean overlaps(String countryRegions, String publicationRegions) {
        List<String> mine = parse(countryRegions);
        return parse(publicationRegions).stream().anyMatch(mine::contains);
    }

    private Regions() {}
}
