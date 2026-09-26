package com.jabiz.runtime.test.scenario;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A replay scenario (docs/design/07-quality.md section 3.1): which processes run in which order with which inputs,
 * how the clock moves between them, what must hold on the way, and which entities the final snapshot covers.
 * Parsing is strict: an unknown key is an error, so a misspelled expectation never passes silently.
 *
 * @param source   where the scenario came from (classpath resource), for messages and the snapshot file
 * @param clock    the business time the replay starts at
 * @param snapshot entities of the snapshot and the time it is taken at; null for no snapshot
 */
public record Scenario(String name, String source, Instant clock, Actor actor, List<Step> steps,
    SnapshotSpec snapshot) {

    public Scenario {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(actor, "actor");
        steps = List.copyOf(steps);
    }

    /** Who runs the processes; permissions {@code ["*"]} grant everything. */
    public record Actor(String id, String tenant, Set<String> roles, Set<String> permissions) {
        public Actor {
            Objects.requireNonNull(id, "actor id");
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }
    }

    /** @param asOf null for the time the replay ends at */
    public record SnapshotSpec(List<String> entities, Instant asOf) {
        public SnapshotSpec {
            entities = List.copyOf(entities);
        }
    }

    /** One step; {@code number} counts from 1 in file order. */
    public sealed interface Step {
        int number();
    }

    /**
     * Runs a process. {@code save} maps variable names to paths in the output ({@code $.a.b[0]});
     * {@code expectOutput} must be contained in the output.
     */
    public record ProcessStep(int number, String process, Map<String, Object> input, Map<String, String> save,
        Object expectOutput) implements Step {}

    /** Moves the clock forward by an ISO-8601 duration ({@code PT2H}) or period ({@code P1D}, {@code P1M}). */
    public record AdvanceClock(int number, String amount) implements Step {}

    public record SetClock(int number, Instant time) implements Step {}

    /**
     * Runs a scheduled job for {@code at} (default: now) as the scheduler would, and checks the outcome
     * ({@code SUCCEEDED} by default; docs/design/11-ledger-events-jobs.md section 4).
     */
    public record RunJob(int number, String job, Instant at, String outcome) implements Step {}

    /** Delivers the pending outbox events to their consumers until nothing is due. */
    public record DeliverEvents(int number) implements Step {}

    /** Checks a SQL template's rows, an entity or a parameter value; see {@link Expectation}. */
    public record Expect(int number, Expectation expectation) implements Step {}

    /** Runs a process that must fail with the given status, and with the rule code (on the field) if given. */
    public record ExpectError(int number, String process, Map<String, Object> input, int status, String ruleCode,
        String field) implements Step {}

    public sealed interface Expectation {}

    /** Rows of a SQL template run with {@code params}: their number, and each row containing {@code values[i]}. */
    public record QueryExpectation(String query, Map<String, Object> params, Integer rows,
        List<Map<String, Object>> values) implements Expectation {}

    /** The entity with {@code id} (at {@code asOf}, for temporal entities) contains {@code fields}. */
    public record EntityExpectation(String entity, Object id, Instant asOf, Map<String, Object> fields)
        implements Expectation {}

    /** The parameter's value in effect at {@code asOf} (default: now). */
    public record ParamExpectation(String param, Instant asOf, Object value) implements Expectation {}

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    /** @throws IllegalArgumentException naming the source and the problem */
    public static Scenario parse(String yaml, String source) {
        Map<String, Object> root;
        try {
            root = YAML.readValue(yaml, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JacksonException e) {
            throw new IllegalArgumentException(source + ": not valid YAML: " + e.getOriginalMessage(), e);
        }
        if (root == null) {
            throw new IllegalArgumentException(source + ": empty scenario");
        }
        try {
            onlyKeys(root, "scenario", Set.of("name", "description", "clock", "actor", "steps", "snapshot"));
            List<Step> steps = new ArrayList<>();
            List<?> rawSteps = list(root.get("steps"), "steps");
            for (int i = 0; i < rawSteps.size(); i++) {
                steps.add(step(i + 1, map(rawSteps.get(i), "step " + (i + 1))));
            }
            return new Scenario(text(root, "name", true), source, instant(root.get("clock"), "clock"),
                actor(map(root.get("actor"), "actor")), steps, snapshot(root.get("snapshot")));
        } catch (IllegalArgumentException | ClassCastException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
    }

    private static Actor actor(Map<String, Object> actor) {
        onlyKeys(actor, "actor", Set.of("id", "tenant", "roles", "permissions"));
        return new Actor(text(actor, "id", true), text(actor, "tenant", false), strings(actor.get("roles")),
            strings(actor.get("permissions")));
    }

    private static SnapshotSpec snapshot(Object raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> snapshot = map(raw, "snapshot");
        onlyKeys(snapshot, "snapshot", Set.of("entities", "asOf"));
        Object asOf = snapshot.get("asOf");
        List<String> entities = list(snapshot.get("entities"), "snapshot.entities").stream()
            .map(String::valueOf).toList();
        return new SnapshotSpec(entities, asOf == null || "end".equals(asOf) ? null : instant(asOf, "snapshot.asOf"));
    }

    private static Step step(int number, Map<String, Object> step) {
        String where = "step " + number;
        Map<String, Object> keys = new LinkedHashMap<>(step);
        keys.remove("note");
        if (keys.containsKey("process")) {
            onlyKeys(keys, where, Set.of("process", "input", "save", "expectOutput"));
            Map<String, String> save = new LinkedHashMap<>();
            if (keys.get("save") != null) {
                map(keys.get("save"), where + ".save").forEach((name, path) -> save.put(name, String.valueOf(path)));
            }
            return new ProcessStep(number, text(keys, "process", true), input(keys.get("input"), where), save,
                keys.get("expectOutput"));
        }
        if (keys.size() != 1) {
            throw new IllegalArgumentException(where + " must have exactly one of process, advanceClock, setClock, "
                + "runJob, deliverEvents, expect, expectError (besides note); found " + keys.keySet());
        }
        String kind = keys.keySet().iterator().next();
        Object body = keys.get(kind);
        return switch (kind) {
            case "advanceClock" -> new AdvanceClock(number, String.valueOf(body));
            case "setClock" -> new SetClock(number, instant(body, where + ".setClock"));
            case "runJob" -> runJob(number, body, where + ".runJob");
            case "deliverEvents" -> {
                if (!Boolean.TRUE.equals(body)) {
                    throw new IllegalArgumentException(where + ".deliverEvents must be true");
                }
                yield new DeliverEvents(number);
            }
            case "expect" -> new Expect(number, expectation(map(body, where + ".expect"), where + ".expect"));
            case "expectError" -> expectError(number, map(body, where + ".expectError"), where + ".expectError");
            default -> throw new IllegalArgumentException(where + ": unknown step '" + kind + "'");
        };
    }

    private static Expectation expectation(Map<String, Object> expect, String where) {
        if (expect.containsKey("query")) {
            onlyKeys(expect, where, Set.of("query", "params", "rows", "values"));
            List<Map<String, Object>> values = new ArrayList<>();
            if (expect.get("values") != null) {
                for (Object row : list(expect.get("values"), where + ".values")) {
                    values.add(map(row, where + ".values[]"));
                }
            }
            Object rows = expect.get("rows");
            return new QueryExpectation(text(expect, "query", true), input(expect.get("params"), where),
                rows == null ? null : ((Number) rows).intValue(), values);
        }
        if (expect.containsKey("entity")) {
            onlyKeys(expect, where, Set.of("entity", "id", "asOf", "fields"));
            Object asOf = expect.get("asOf");
            return new EntityExpectation(text(expect, "entity", true), required(expect, "id", where),
                asOf == null ? null : instant(asOf, where + ".asOf"), map(required(expect, "fields", where), where));
        }
        if (expect.containsKey("param")) {
            onlyKeys(expect, where, Set.of("param", "asOf", "value"));
            Object asOf = expect.get("asOf");
            return new ParamExpectation(text(expect, "param", true),
                asOf == null ? null : instant(asOf, where + ".asOf"), required(expect, "value", where));
        }
        throw new IllegalArgumentException(where + " needs query, entity or param");
    }

    private static RunJob runJob(int number, Object body, String where) {
        if (body instanceof String name) {
            return new RunJob(number, name, null, "SUCCEEDED");
        }
        Map<String, Object> job = map(body, where);
        onlyKeys(job, where, Set.of("job", "at", "outcome"));
        Object at = job.get("at");
        String outcome = text(job, "outcome", false);
        return new RunJob(number, text(job, "job", true), at == null ? null : instant(at, where + ".at"),
            outcome == null ? "SUCCEEDED" : outcome);
    }

    private static ExpectError expectError(int number, Map<String, Object> error, String where) {
        onlyKeys(error, where, Set.of("process", "input", "status", "ruleCode", "field"));
        return new ExpectError(number, text(error, "process", true), input(error.get("input"), where),
            ((Number) required(error, "status", where)).intValue(), text(error, "ruleCode", false),
            text(error, "field", false));
    }

    private static Map<String, Object> input(Object raw, String where) {
        return raw == null ? Map.of() : map(raw, where + ".input");
    }

    private static Object required(Map<String, Object> map, String key, String where) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException(where + " needs '" + key + "'");
        }
        return value;
    }

    private static void onlyKeys(Map<String, Object> map, String where, Set<String> allowed) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(where + ": unknown key '" + key + "' (allowed: "
                    + allowed.stream().sorted().toList() + ")");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw, String where) {
        if (!(raw instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(where + " must be a mapping");
        }
        return (Map<String, Object>) raw;
    }

    private static List<?> list(Object raw, String where) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(where + " must be a list");
        }
        return list;
    }

    private static Set<String> strings(Object raw) {
        return raw == null ? Set.of() : Set.copyOf(list(raw, "list").stream().map(String::valueOf).toList());
    }

    private static String text(Map<String, Object> map, String key, boolean required) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            if (required) {
                throw new IllegalArgumentException("'" + key + "' is required");
            }
            return null;
        }
        return value.toString();
    }

    private static Instant instant(Object raw, String where) {
        if (raw == null) {
            throw new IllegalArgumentException(where + " is required");
        }
        try {
            return Instant.parse(raw.toString());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(where + ": not an ISO-8601 instant: " + raw, e);
        }
    }
}
