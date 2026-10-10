package com.jabiz.quizbuks.country;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The countries {@code QB_SETUP} brings in, read from {@value CountryData#RESOURCE} once at startup (never on a
 * request). Every problem of the file is a startup problem of category {@value #CATEGORY}, reported with all the
 * others at once by startup and {@code platformCheck}; {@code QB_SETUP} only ever sees the rows without problems.
 */
@Component
public class CountryCatalog implements PlatformCheck {

    public static final String CATEGORY = "QUIZBUKS";

    private final String resource;
    private final CountryData.Result data;

    public CountryCatalog() {
        this(CountryData.RESOURCE);
    }

    CountryCatalog(String resource) {
        this.resource = resource;
        this.data = read(resource);
    }

    /** The countries read without problems, in file order. */
    public List<CountryData.Country> countries() {
        return data.countries();
    }

    @Override
    public List<CheckProblem> check() {
        return data.problems().stream()
            .map(problem -> CheckProblem.error(CATEGORY, resource + ":" + problem.line(),
                problem.message()))
            .toList();
    }

    private static CountryData.Result read(String resource) {
        try (InputStream in = CountryCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return new CountryData.Result(List.of(), List.of(new CountryData.Problem(0, "the file is missing")));
            }
            return CountryData.parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                .toList());
        } catch (IOException e) {
            return new CountryData.Result(List.of(), List.of(new CountryData.Problem(0,
                "the file cannot be read: " + e.getMessage())));
        }
    }
}
