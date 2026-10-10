package com.jabiz.quizbuks.country;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the country file {@value #RESOURCE}: every ISO 3166-1 country with its name in English, Chinese and Japanese
 * and the regions it belongs to. The format has no quoting, so nothing in it can be misread: one country per line,
 * five cells separated by {@code |} ({@code code|en|zh|ja|regions}), the regions beyond {@link Regions#GLOBAL}
 * separated by commas (every country is in GLOBAL). Blank lines and lines starting with {@code #} are skipped; the
 * first other line is the header. Pure: {@link CountryCatalog} loads the file and reports the problems at startup.
 */
public final class CountryData {

    public static final String RESOURCE = "quizbuks/countries.txt";

    static final String HEADER = "code|en|zh|ja|regions";
    private static final List<String> LANGUAGES = List.of("en", "zh", "ja");

    /**
     * One country.
     *
     * @param names   its name by language ({@code en}, {@code zh}, {@code ja})
     * @param regions its regions as written in the country entity ({@link Regions#withGlobal})
     */
    public record Country(String code, Map<String, String> names, String regions) {
        public Country {
            names = Map.copyOf(names);
        }
    }

    /** A problem of the file: the line (from 1) and what is wrong. */
    public record Problem(int line, String message) {}

    /** The countries read without problems, in file order, and every problem of the file. */
    public record Result(List<Country> countries, List<Problem> problems) {
        public Result {
            countries = List.copyOf(countries);
            problems = List.copyOf(problems);
        }
    }

    /** Reads the lines of the file; never throws for its content, every problem is in the result. */
    public static Result parse(List<String> lines) {
        List<Country> countries = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        boolean header = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            int lineNo = i + 1;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!header) {
                header = true;
                if (!HEADER.equals(line.replace(" ", ""))) {
                    problems.add(new Problem(lineNo, "the header must be " + HEADER));
                }
                continue;
            }
            List<String> cells = Arrays.stream(line.split("\\|", -1)).map(String::strip).toList();
            if (cells.size() != 5) {
                problems.add(new Problem(lineNo, "5 cells separated by '|' expected, found " + cells.size()));
                continue;
            }
            List<String> wrong = new ArrayList<>();
            String code = cells.get(0);
            if (!code.matches("[A-Z]{2}")) {
                wrong.add("'" + code + "' is not a country code");
            } else if (!codes.add(code)) {
                wrong.add(code + " appears twice");
            }
            for (int c = 1; c <= 3; c++) {
                if (cells.get(c).isEmpty()) {
                    wrong.add("no name in " + LANGUAGES.get(c - 1));
                } else if (cells.get(c).contains("\"")) {
                    wrong.add("the name in " + LANGUAGES.get(c - 1) + " holds a quote: the format has no quoting");
                }
            }
            List<String> regions = cells.get(4).isEmpty() ? List.of()
                : Arrays.stream(cells.get(4).split(",", -1)).map(String::strip).toList();
            if (regions.contains("")) {
                wrong.add("empty region in '" + cells.get(4) + "'");
            }
            List<String> unknown = regions.stream().filter(r -> !r.isEmpty() && !Regions.ALL.contains(r)).toList();
            if (!unknown.isEmpty()) {
                wrong.add("not regions: " + unknown);
            }
            if (regions.stream().distinct().count() != regions.size()) {
                wrong.add("a region is listed twice in '" + cells.get(4) + "'");
            }
            if (regions.contains(Regions.GLOBAL)) {
                wrong.add("GLOBAL is implied and not listed");
            }
            if (!wrong.isEmpty()) {
                wrong.forEach(message -> problems.add(new Problem(lineNo, message)));
                continue;
            }
            countries.add(new Country(code, Map.of("en", cells.get(1), "zh", cells.get(2), "ja", cells.get(3)),
                Regions.withGlobal(regions)));
        }
        if (!header) {
            problems.add(new Problem(0, "the file is empty; the header must be " + HEADER));
        }
        return new Result(countries, problems);
    }

    private CountryData() {}
}
