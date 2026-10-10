package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What every content process shares (docs/quizbuks/plans/Q3-content.md, 流程 and D-Q3-4): the quiz's lock
 * {@code qb.quiz:<quizId>}, taken before anything of the quiz is read; the quiz read through the sponsor's dataset
 * (another sponsor's quiz, or a removed one, is 404 without saying whether it exists); its parts read whole through
 * the default datasets; the revision check; and the quiz's new version after a change of its content.
 */
final class QuizEditing {

    static final String INPUT = "input";
    static final String QUIZ_ID = "quizId";
    static final String QUIZ = "quiz";
    static final String MATERIALS = "materials";
    static final String IMAGES = "images";
    static final String QUESTIONS = "questions";
    static final String OPTIONS = "options";
    static final String VERSIONS = "versions";
    static final String VERSION_FILES = "versionFiles";
    static final String OUTPUT = "output";

    static String lockName(Object quizId) {
        return quizId == null ? null : "qb.quiz:" + quizId;
    }

    /** Every row of an entity in the quiz (the platform refuses more than its maximum rather than cut it short). */
    static EntityQuery ofQuiz(Object quizId) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("quizId", quizId)).limit(Integer.MAX_VALUE).build();
    }

    /** Takes the quiz's lock and loads it through the sponsor's dataset: 404 unless it is the caller's own. */
    static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the quiz", HoldLock.<ProcessContext>exclusive(ctx -> lockName(ctx.get(QUIZ_ID))))
            .step("Load the quiz", LoadEntity.by(QbContent.sponsorDataset(QbContent.QUIZ), QUIZ_ID, QUIZ));
    }

    /** Loads every row of {@code entity} in the quiz into {@code key}. */
    static <I, O> void loadAll(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String step, String entity,
        String key) {
        pb.step(step, QueryEntities.<ProcessContext>of(QbContent.defaultDataset(entity),
            ctx -> ofQuiz(ctx.get(QUIZ_ID)), key));
    }

    static ProcessContext start(com.jabiz.process.ProcessStart start, Object input, UUID quizId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(QUIZ_ID, quizId);
        return ctx;
    }

    static EntityInstance quiz(ProcessContext ctx) {
        return ctx.get(QUIZ, EntityInstance.class);
    }

    /**
     * Whether the caller saw the quiz as it is: a given {@code baseRevision} other than the current one means
     * another window changed it meanwhile (422 {@code QB_QUIZ_CHANGED}); none means the last writer wins.
     */
    static boolean current(ProcessContext ctx, EntityInstance quiz, Long baseRevision) {
        long revision = number(quiz.get("revision"));
        if (baseRevision != null && baseRevision != revision) {
            ctx.reject(new Violation("baseRevision", ContentCodes.QUIZ_CHANGED, "The quiz was changed meanwhile: "
                + "revision " + revision + ", not " + baseRevision, Map.of("revision", revision,
                "baseRevision", baseRevision)));
            return false;
        }
        return true;
    }

    /**
     * Registers the quiz's new version after a change of its content: the next revision, the time, unversioned
     * changes, and {@code more} (counts and the like). Puts the output.
     */
    static void touch(ProcessContext ctx, EntityInstance quiz, Map<String, Object> more, UUID itemId,
        List<UUID> partIds) {
        long revision = number(quiz.get("revision")) + 1;
        Map<String, Object> values = new LinkedHashMap<>(more);
        values.put("revision", BigDecimal.valueOf(revision));
        values.put("editedAt", ctx.opTime());
        values.put("changedSinceVersion", true);
        ctx.changes().update(QbContent.QUIZ, quiz.id(), quiz.version(), values);
        ctx.put(OUTPUT, new EditOutput(uuid(quiz.id()), revision, ctx.opTime(), itemId, partIds));
    }

    /** The output when nothing changed. */
    static void unchanged(ProcessContext ctx, EntityInstance quiz, UUID itemId, List<UUID> partIds) {
        ctx.put(OUTPUT, new EditOutput(uuid(quiz.id()), number(quiz.get("revision")), instant(quiz.get("editedAt")),
            itemId, partIds));
    }

    /** The draft as a version would keep it: parts in their order, numbered from 1. */
    static QuizSnapshot snapshot(EntityInstance quiz, List<EntityInstance> materials, List<EntityInstance> images,
        List<EntityInstance> questions, List<EntityInstance> options) {
        Map<String, List<EntityInstance>> imagesOf = groups(images, "materialId");
        Map<String, List<EntityInstance>> optionsOf = groups(options, "questionId");
        List<QuizSnapshot.Material> m = new ArrayList<>();
        for (EntityInstance material : ordered(materials)) {
            List<QuizSnapshot.Image> list = new ArrayList<>();
            for (EntityInstance image : ordered(imagesOf.getOrDefault(key(material.id()), List.of()))) {
                list.add(new QuizSnapshot.Image(list.size() + 1, uuid(image.get("image")), image.get("caption")));
            }
            m.add(new QuizSnapshot.Material(m.size() + 1, material.get("kind"), material.get("title"),
                material.get("description"), material.get("body"), material.get("url"), uuid(material.get("pdf")),
                uuid(material.get("audio")), list));
        }
        List<QuizSnapshot.Question> q = new ArrayList<>();
        for (EntityInstance question : ordered(questions)) {
            List<QuizSnapshot.Option> list = new ArrayList<>();
            for (EntityInstance option : ordered(optionsOf.getOrDefault(key(question.id()), List.of()))) {
                list.add(new QuizSnapshot.Option(list.size() + 1, option.get("text"), uuid(option.get("image")),
                    Boolean.TRUE.equals(option.get("correct"))));
            }
            q.add(new QuizSnapshot.Question(q.size() + 1, question.get("stem"), uuid(question.get("image")),
                (int) number(question.get("points")), list));
        }
        Object limit = quiz.get("timeLimitSec");
        return new QuizSnapshot(quiz.get("title"), quiz.get("intro"), uuid(quiz.get("cover")),
            limit == null ? null : (int) number(limit), Boolean.TRUE.equals(quiz.get("aiGenerated")), m, q);
    }

    /**
     * Whether every given key is one of the parts of the item, once (422 {@code QB_CONTENT_NOT_IN_QUIZ} for the
     * others: a key of another question, quiz or sponsor is never touched).
     */
    static boolean knownParts(ProcessContext ctx, List<UUID> given, List<EntityInstance> parts, String list,
        String field) {
        Set<String> known = new HashSet<>();
        parts.forEach(part -> known.add(key(part.id())));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < given.size(); i++) {
            UUID id = given.get(i);
            if (id != null && (!known.contains(key(id)) || !seen.add(key(id)))) {
                ctx.reject(QuizEditing.notInQuiz(list + "[" + i + "]." + field, id));
            }
        }
        return !ctx.hasViolations();
    }

    /**
     * Writes the parts of an item (options of a question, images of a material) as given, in the given order:
     * existing ones left out are deleted, changed ones updated, new ones (no key) inserted with {@code parent}.
     *
     * @param ids  the keys given, null for new parts, in the order of {@code rows}
     * @param keys receives the keys of the parts, in their order
     * @return whether anything changed
     */
    static boolean saveParts(ProcessContext ctx, String entity, List<EntityInstance> existing, List<UUID> ids,
        List<Map<String, Object>> rows, Map<String, Object> parent, List<UUID> keys) {
        boolean changed = false;
        Set<String> kept = new HashSet<>();
        ids.stream().filter(Objects::nonNull).forEach(id -> kept.add(key(id)));
        for (EntityInstance part : existing) {
            if (!kept.contains(key(part.id()))) {
                ctx.changes().delete(entity, part.id(), part.version());
                changed = true;
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            UUID id = ids.get(i);
            if (id == null) {
                Map<String, Object> values = new LinkedHashMap<>(parent);
                values.putAll(rows.get(i));
                keys.add(uuid(ctx.changes().insert(entity, values)));
                changed = true;
            } else {
                EntityInstance part = find(existing, id);
                Map<String, Object> differences = differences(part, rows.get(i));
                if (!differences.isEmpty()) {
                    ctx.changes().update(entity, part.id(), part.version(), differences);
                    changed = true;
                }
                keys.add(id);
            }
        }
        return changed;
    }

    /**
     * Puts the questions or materials of the quiz in the requested order (D-Q3-4): the request lists every one of
     * them once (422 {@code QB_ORDER_MISMATCH} otherwise); those that move get their new place.
     */
    static void reorder(ProcessContext ctx, String entity, List<EntityInstance> rows, List<UUID> requested,
        String field, Long baseRevision) {
        EntityInstance quiz = quiz(ctx);
        if (!current(ctx, quiz, baseRevision)) {
            return;
        }
        Map<String, Integer> places = new LinkedHashMap<>();
        rows.forEach(row -> places.put(key(row.id()), (int) number(row.get("seq"))));
        var moves = Reorder.changes(places, requested.stream().map(QuizEditing::key).toList());
        if (moves.isEmpty()) {
            ctx.reject(new Violation(field, ContentCodes.ORDER_MISMATCH, "The new order must list each of the "
                + rows.size() + " items of the quiz once", Map.of("count", rows.size())));
            return;
        }
        moves.get().forEach((id, seq) -> {
            EntityInstance row = find(rows, id);
            ctx.changes().update(entity, row.id(), row.version(), Map.of("seq", decimal(seq)));
        });
        if (moves.get().isEmpty()) {
            unchanged(ctx, quiz, null, requested);
        } else {
            touch(ctx, quiz, Map.of(), null, requested);
        }
    }

    /** Rows in their order: by {@code seq}, then by key (two rows never share a place for long, D-Q3-4). */
    static List<EntityInstance> ordered(List<EntityInstance> rows) {
        return rows.stream().sorted(Comparator.<EntityInstance>comparingLong(row -> number(row.get("seq")))
            .thenComparing(row -> key(row.id()))).toList();
    }

    static Map<String, List<EntityInstance>> groups(List<EntityInstance> rows, String parent) {
        Map<String, List<EntityInstance>> groups = new HashMap<>();
        rows.forEach(row -> groups.computeIfAbsent(key(row.get(parent)), k -> new ArrayList<>()).add(row));
        return groups;
    }

    /** The next place at the end: one after the last, gaps left by deletions stay. */
    static int nextSeq(List<EntityInstance> rows) {
        return (int) rows.stream().mapToLong(row -> number(row.get("seq"))).max().orElse(0) + 1;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static EntityInstance find(List<EntityInstance> rows, Object id) {
        String wanted = key(id);
        return rows.stream().filter(row -> key(row.id()).equals(wanted)).findFirst().orElse(null);
    }

    static Violation notInQuiz(String field, Object id) {
        return new Violation(field, ContentCodes.CONTENT_NOT_IN_QUIZ, "There is no " + id + " in this quiz",
            Map.of("id", String.valueOf(id)));
    }

    static String key(Object id) {
        return String.valueOf(id);
    }

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    static long number(Object value) {
        return value == null ? 0 : value instanceof BigDecimal d ? d.longValueExact()
            : ((Number) value).longValue();
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof java.time.OffsetDateTime time ? time.toInstant()
            : (Instant) value;
    }

    static BigDecimal decimal(long value) {
        return BigDecimal.valueOf(value);
    }

    /** Empty text is no text. */
    static String text(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Whether writing {@code values} over {@code row} changes anything (numbers compared by value). */
    static Map<String, Object> differences(EntityInstance row, Map<String, Object> values) {
        Map<String, Object> changed = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            if (!same(row.get(field), value)) {
                changed.put(field, value);
            }
        });
        return changed;
    }

    static boolean same(Object stored, Object value) {
        if (stored instanceof BigDecimal a && value instanceof Number b) {
            return a.compareTo(new BigDecimal(b.toString())) == 0;
        }
        if (stored instanceof UUID || value instanceof UUID) {
            return Objects.equals(stored == null ? null : stored.toString(), value == null ? null : value.toString());
        }
        return Objects.equals(stored, value);
    }

    private QuizEditing() {}
}
