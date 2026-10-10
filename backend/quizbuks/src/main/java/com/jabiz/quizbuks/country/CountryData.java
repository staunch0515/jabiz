package com.jabiz.quizbuks.country;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The countries {@code QB_SETUP} brings in: every ISO 3166-1 country with its name in English, Chinese and Japanese
 * and the regions it belongs to, from {@value #RESOURCE}. The file lists only the regions beyond {@link Regions#GLOBAL}
 * ({@code ;}-separated); every country is in GLOBAL.
 */
public final class CountryData {

    public static final String RESOURCE = "quizbuks/countries.csv";

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

    private static final List<Country> COUNTRIES = load();

    /** All countries, in the order of their codes. */
    public static List<Country> all() {
        return COUNTRIES;
    }

    private static List<Country> load() {
        // Read once when the class loads (at startup), never on a request.
        try (InputStream in = CountryData.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads the lines of the file: a header {@code code,en,zh,ja,regions}, then one country per line.
     *
     * @throws IllegalArgumentException naming the line of the first problem
     */
    static List<Country> parse(List<String> lines) {
        if (lines.isEmpty() || !List.of("code", "en", "zh", "ja", "regions").equals(cells(lines.getFirst()))) {
            throw new IllegalArgumentException("The header must be code,en,zh,ja,regions");
        }
        List<Country> countries = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = cells(line);
            int lineNo = i + 1;
            if (cells.size() != 5) {
                throw new IllegalArgumentException("Line " + lineNo + ": 5 cells expected, found " + cells.size());
            }
            String code = cells.get(0);
            if (!code.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("Line " + lineNo + ": '" + code + "' is not a country code");
            }
            if (!codes.add(code)) {
                throw new IllegalArgumentException("Line " + lineNo + ": " + code + " appears twice");
            }
            for (int c = 1; c <= 3; c++) {
                if (cells.get(c).isBlank()) {
                    throw new IllegalArgumentException("Line " + lineNo + ": " + code + " has no name in "
                        + List.of("en", "zh", "ja").get(c - 1));
                }
            }
            String regions;
            try {
                regions = Regions.withGlobal(cells.get(4).isBlank() ? List.of() : List.of(cells.get(4).split(";")));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Line " + lineNo + ": " + e.getMessage(), e);
            }
            countries.add(new Country(code, Map.of("en", cells.get(1), "zh", cells.get(2), "ja", cells.get(3)),
                regions));
        }
        return List.copyOf(countries);
    }

    /** The cells of one CSV line; a cell in double quotes may hold commas and doubled quotes. */
    static List<String> cells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (ch == ',') {
                cells.add(cell.toString().strip());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unclosed quote in: " + line);
        }
        cells.add(cell.toString().strip());
        return cells;
    }

    private CountryData() {}
}
