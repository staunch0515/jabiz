package com.jabiz.quizbuks.firstlaunch;

import com.jabiz.firstlaunch.FirstLaunchConfig;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The QuizBuks sections of the first-launch configuration (F-LAUNCH, docs/quizbuks/plans/impl/F-LAUNCH.md section
 * 1.3), as written: {@code operators}, {@code sponsors} and {@code params}. Reading never fails; what is wrong is
 * reported by {@link QbFirstLaunchChecks} with the place it was found.
 *
 * @param operators the operators, in the order written
 * @param sponsors  the operators' own sponsors, in the order written
 * @param params    the parameters to set, by key, as written (YAML scalars)
 */
public record QbFirstLaunchConfig(List<Operator> operators, List<Sponsor> sponsors, Map<String, Object> params) {

    /** {@code startAt: launch}: the moment the run began. */
    public static final String LAUNCH = "launch";

    /**
     * @param index     its place in {@code operators}
     * @param isDefault {@code default: true}: the operator new sponsors go to
     * @param lead      the account key of its lead, who is also its own sponsor
     * @param members   account keys of the other members, added once each has accepted the invitation
     */
    public record Operator(int index, String key, String name, boolean isDefault, String lead, List<String> members) {}

    /**
     * What the configuration adds to a publication of the package (the package carries no money or times).
     *
     * @param startAt {@link #LAUNCH} or a time with its offset
     */
    public record PublicationExtra(String rewardMethod, String redeemDescription, String startAt, String endAt) {

        /** The start: the run's when {@link #LAUNCH}; null when not a time. */
        public Instant start(Instant launch) {
            return LAUNCH.equals(startAt) ? launch : time(startAt);
        }

        /** The end; null when not a time. */
        public Instant end() {
            return time(endAt);
        }
    }

    /**
     * @param index         its place in {@code sponsors}
     * @param operator      the operator's key; the sponsor is its lead
     * @param packagePath   the package, relative to the configuration's directory
     * @param rewardMethods the reward methods the sponsor may use
     * @param publications  the package's publications' additions, by the publication's name
     */
    public record Sponsor(int index, String operator, String packagePath, List<String> rewardMethods,
        Map<String, PublicationExtra> publications) {}

    public QbFirstLaunchConfig {
        operators = List.copyOf(operators);
        sponsors = List.copyOf(sponsors);
        params = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** Reads the sections from the configuration's main file. */
    public static QbFirstLaunchConfig of(FirstLaunchConfig config) {
        Map<String, Object> document = config.document();
        List<Operator> operators = new ArrayList<>();
        List<Object> written = list(document.get("operators"));
        for (int i = 0; i < written.size(); i++) {
            Map<String, Object> o = map(written.get(i));
            operators.add(new Operator(i, text(o.get("key")), text(o.get("name")),
                Boolean.TRUE.equals(o.get("default")), text(o.get("lead")), texts(o.get("members"))));
        }
        List<Sponsor> sponsors = new ArrayList<>();
        written = list(document.get("sponsors"));
        for (int i = 0; i < written.size(); i++) {
            Map<String, Object> s = map(written.get(i));
            Map<String, PublicationExtra> publications = new LinkedHashMap<>();
            map(s.get("publications")).forEach((name, value) -> {
                Map<String, Object> p = map(value);
                publications.put(name, new PublicationExtra(text(p.get("rewardMethod")),
                    text(p.get("redeemDescription")), text(p.get("startAt")), text(p.get("endAt"))));
            });
            sponsors.add(new Sponsor(i, text(s.get("operator")), text(s.get("package")),
                texts(s.get("rewardMethods")), publications));
        }
        return new QbFirstLaunchConfig(operators, sponsors, map(document.get("params")));
    }

    /** The operator with this key, if any. */
    public java.util.Optional<Operator> operator(String key) {
        return operators.stream().filter(o -> Objects.equals(o.key(), key)).findFirst();
    }

    /** A time with its offset ({@code 2026-12-01T09:00+09:00}), or null. */
    static Instant time(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static List<Object> list(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }

    static String text(Object value) {
        return value == null ? null : String.valueOf(value).strip();
    }

    static List<String> texts(Object value) {
        return list(value).stream().map(QbFirstLaunchConfig::text).filter(Objects::nonNull).toList();
    }
}
