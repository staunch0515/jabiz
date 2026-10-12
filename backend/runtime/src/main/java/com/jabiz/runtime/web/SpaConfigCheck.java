package com.jabiz.runtime.web;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.security.SignInEntries;
import com.jabiz.runtime.web.JabizWebProperties.Spa;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Startup check of the single-page application configuration (category {@code WEB}, {@link JabizWebProperties}):
 * invalid entries and unknown sign-in entries are errors; differences from the SPAs the build packaged into the jar
 * ({@value #DECLARED}, written by {@code jabizApp { spa(...) }}) are warnings: a prefix configured but not built, built
 * but not configured, or with another entry or PWA mode, whose pages or Service Worker would then not be served as
 * built (decision D43 item 6).
 */
@Component
class SpaConfigCheck implements PlatformCheck {

    static final String CATEGORY = "WEB";

    /** The SPAs the build declared ({@code META-INF/jabiz-app.properties}), as {@code jabiz.web.spa}. */
    static final String DECLARED = "jabiz.build.spa";

    private static final java.util.regex.Pattern ENTRY = java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,39}");

    private final JabizWebProperties properties;
    private final List<Spa> declared;
    private final Predicate<String> entryExists;

    @Autowired
    SpaConfigCheck(JabizWebProperties properties, Environment environment, SignInEntries entries) {
        this(properties, Binder.get(environment).bind(DECLARED, Bindable.listOf(Spa.class)).orElse(List.of()),
            name -> entries.find(name).filter(entry -> entry.name().equals(name)).isPresent());
    }

    /** Without anything declared by the build, and every entry known. */
    SpaConfigCheck(JabizWebProperties properties) {
        this(properties, List.of(), name -> true);
    }

    SpaConfigCheck(JabizWebProperties properties, List<Spa> declared, Predicate<String> entryExists) {
        this.properties = properties;
        this.declared = List.copyOf(declared);
        this.entryExists = entryExists;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>(
            properties.problems().stream().map(text -> CheckProblem.error(CATEGORY, text)).toList());
        List<Spa> configured = properties.spa();
        for (int i = 0; i < configured.size(); i++) {
            Spa spa = configured.get(i);
            // A malformed name is reported by the properties already.
            if (spa.entry() != null && ENTRY.matcher(spa.entry()).matches() && !entryExists.test(spa.entry())) {
                problems.add(CheckProblem.error(CATEGORY, "jabiz.web.spa[" + i + "].entry", "there is no usable "
                    + "sign-in entry " + spa.entry() + " (jabiz.security.entries." + spa.entry() + ")"));
            }
        }
        if (!declared.isEmpty()) {
            problems.addAll(differences(configured));
        }
        return problems;
    }

    /** How the configuration differs from what the build packaged. */
    private List<CheckProblem> differences(List<Spa> configured) {
        List<CheckProblem> problems = new ArrayList<>();
        Map<String, Spa> built = byPath(declared);
        Map<String, Spa> served = byPath(configured);
        built.forEach((path, spa) -> {
            Spa own = served.get(path);
            if (own == null) {
                problems.add(CheckProblem.warning(CATEGORY, "jabiz.web.spa", "the jar contains the SPA " + path
                    + " (jabizApp), but it is not configured: its pages are not served as an SPA"));
                return;
            }
            if (!Objects.equals(own.entry(), spa.entry())) {
                problems.add(CheckProblem.warning(CATEGORY, "jabiz.web.spa (" + path + ")", "entry is "
                    + own.entry() + ", but the SPA was built for " + spa.entry()));
            }
            if (own.pwa() != spa.pwa()) {
                problems.add(CheckProblem.warning(CATEGORY, "jabiz.web.spa (" + path + ")", "pwa is "
                    + own.pwa().value() + ", but the SPA was built with " + spa.pwa().value()
                    + ": its Service Worker is not served as built"));
            }
        });
        served.keySet().stream().filter(path -> !built.containsKey(path)).forEach(path -> problems.add(
            CheckProblem.warning(CATEGORY, "jabiz.web.spa (" + path + ")", "the jar contains no SPA " + path
                + " (jabizApp)")));
        return problems;
    }

    private static Map<String, Spa> byPath(List<Spa> spas) {
        Map<String, Spa> result = new LinkedHashMap<>();
        spas.stream().filter(s -> s.path() != null).forEach(s -> result.putIfAbsent(s.path(), s));
        return result;
    }
}
