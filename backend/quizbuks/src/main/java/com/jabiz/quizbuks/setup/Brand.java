package com.jabiz.quizbuks.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * The brand this build is (docs/quizbuks/plans/Q54-brand-profile.md, Q55b): the brand profile QB_BRAND chose,
 * written into {@code quizbuks/brand.properties} when the resources were packaged. A brand is deployed on its own, so
 * the jar knows its brand; texts name it through the messages ({@code app.name}), code through {@link #current()}.
 */
public record Brand(String name, String supportEmail, String siteUrl) {

    private static final Brand CURRENT = load();

    /** The brand of this build. */
    public static Brand current() {
        return CURRENT;
    }

    private static Brand load() {
        Properties properties = new Properties();
        try (InputStream in = Brand.class.getResourceAsStream("/quizbuks/brand.properties")) {
            if (in == null) {
                throw new IllegalStateException("quizbuks/brand.properties is missing from the classpath");
            }
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Brand brand = new Brand(properties.getProperty("name", ""), properties.getProperty("supportEmail", ""),
            properties.getProperty("siteUrl", ""));
        if (brand.name.isBlank() || brand.name.contains("@QB_BRAND_")) {
            // Run from sources the build did not process: the brand's name was never written in.
            throw new IllegalStateException("quizbuks/brand.properties was not filled in from the brand profile");
        }
        return brand;
    }
}
