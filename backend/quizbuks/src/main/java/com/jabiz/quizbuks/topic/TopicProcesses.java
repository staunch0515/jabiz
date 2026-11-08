package com.jabiz.quizbuks.topic;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Headquarters keeps the word list of topics (F-S9; docs/quizbuks/operations.md §7.26) with these processes
 * ({@code qb.admin.topic.write}, the content administrators), through the platform's write path and its audit:
 * <ul>
 *   <li>{@value #ADD}: a new word with its code and its name in every language;</li>
 *   <li>{@value #RENAME}: other names (the code never changes);</li>
 *   <li>{@value #DEACTIVATE} / {@value #REACTIVATE}: no longer offered, or offered again; where it was chosen it
 *       stays;</li>
 *   <li>{@value #MERGE}: the word counts as another from now on (strict mode, statistics, its name), and is no longer
 *       offered; the words merged into it before move along, so a merge is always one step.</li>
 * </ul>
 * A word's lock ({@code qb.topic:<code>}) orders its changes; a merge takes both words', in the codes' order.
 */
public final class TopicProcesses {

    public static final String ADD = "QB_TOPIC_ADD";
    public static final String RENAME = "QB_TOPIC_RENAME";
    public static final String DEACTIVATE = "QB_TOPIC_DEACTIVATE";
    public static final String REACTIVATE = "QB_TOPIC_REACTIVATE";
    public static final String MERGE = "QB_TOPIC_MERGE";

    /** A new word: its code (lower-case letters, digits and hyphens) and its name in each language. */
    public record AddInput(@NotBlank @Size(max = TopicRules.CODE_LENGTH) String topicId,
        @NotBlank @Size(max = TopicRules.NAME_LENGTH) String nameZh,
        @NotBlank @Size(max = TopicRules.NAME_LENGTH) String nameJa,
        @NotBlank @Size(max = TopicRules.NAME_LENGTH) String nameEn) {}

    public record RenameInput(@NotBlank @Size(max = TopicRules.CODE_LENGTH) String topicId,
        @NotBlank @Size(max = TopicRules.NAME_LENGTH) String nameZh,
        @NotBlank @Size(max = TopicRules.NAME_LENGTH) String nameJa,
        @NotBlank @Size(max = TopicRules.NAME_LENGTH) String nameEn) {}

    public record TopicRef(@NotBlank @Size(max = TopicRules.CODE_LENGTH) String topicId) {}

    /** @param into the word it counts as from now on */
    public record MergeInput(@NotBlank @Size(max = TopicRules.CODE_LENGTH) String topicId,
        @NotBlank @Size(max = TopicRules.CODE_LENGTH) String into) {}

    /** A word after the change. */
    public record TopicOutput(String topicId, Map<String, String> name, boolean active, String mergedInto) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String CODE = "topic.code";
    static final String FOUND = "topic.found";
    static final String INTO = "topic.into";
    static final String MERGED_INTO_IT = "topic.mergedIntoIt";

    private TopicProcesses() {
    }

    public static ProcessDefinition<AddInput, TopicOutput, ProcessContext> add() {
        return ProcessDefinition.define(ADD, 1, AddInput.class, TopicOutput.class, ProcessContext.class,
            pb -> loading(pb.description("Adds a word to the word list of topics, with its name in every language."),
                AddInput::topicId)
                .compute("Add the word", (metadata, ctx) -> {
                    AddInput input = ctx.get(INPUT, AddInput.class);
                    String code = ctx.get(CODE, String.class);
                    if (!TopicRules.validCode(code)) {
                        ctx.reject(new Violation("topicId", TopicCodes.CODE_FORMAT, "Not a code: " + code,
                            Map.of("code", code)));
                        return;
                    }
                    if (first(ctx, FOUND) != null) {
                        ctx.reject(new Violation("topicId", TopicCodes.CODE_TAKEN, "The code is taken: " + code,
                            Map.of("code", code)));
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("topicId", code);
                    values.put("name", names(input.nameZh(), input.nameJa(), input.nameEn()));
                    values.put("active", true);
                    values.put("mergedInto", null);
                    values.put("createdAt", ctx.opTime());
                    values.put("changedAt", ctx.opTime());
                    ctx.changes().insert(QbTopics.TOPIC, values);
                    ctx.put(OUTPUT, output(code, values));
                }));
    }

    public static ProcessDefinition<RenameInput, TopicOutput, ProcessContext> rename() {
        return ProcessDefinition.define(RENAME, 1, RenameInput.class, TopicOutput.class, ProcessContext.class,
            pb -> loading(pb.description("Gives a word of the word list of topics other names.")
                .actsOn(QbTopics.TOPIC, "topicId"), RenameInput::topicId)
                .compute("Rename the word", (metadata, ctx) -> {
                    RenameInput input = ctx.get(INPUT, RenameInput.class);
                    EntityInstance word = loaded(ctx);
                    if (!notMerged(ctx, word)) {
                        return;
                    }

                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("name", names(input.nameZh(), input.nameJa(), input.nameEn()));
                    values.put("changedAt", ctx.opTime());
                    ctx.changes().update(QbTopics.TOPIC, word.id(), word.version(), values);
                    ctx.put(OUTPUT, output(word, values));
                }));
    }

    public static ProcessDefinition<TopicRef, TopicOutput, ProcessContext> deactivate() {
        return activeSet(DEACTIVATE, false, "Stops offering a word of the word list of topics; where it was chosen it "
            + "stays.");
    }

    public static ProcessDefinition<TopicRef, TopicOutput, ProcessContext> reactivate() {
        return activeSet(REACTIVATE, true, "Offers a deactivated word of the word list of topics again.");
    }

    private static ProcessDefinition<TopicRef, TopicOutput, ProcessContext> activeSet(String name, boolean active,
        String description) {
        return ProcessDefinition.define(name, 1, TopicRef.class, TopicOutput.class, ProcessContext.class,
            pb -> loading(pb.description(description)
                .actsOn(QbTopics.TOPIC, "topicId", a -> a.whenField("active", String.valueOf(!active))),
                TopicRef::topicId)
                .compute(active ? "Offer the word again" : "Stop offering the word", (metadata, ctx) -> {
                    EntityInstance word = loaded(ctx);
                    if (!notMerged(ctx, word)) {
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    if (!Boolean.valueOf(active).equals(word.get("active"))) {
                        values.put("active", active);
                        values.put("changedAt", ctx.opTime());
                        ctx.changes().update(QbTopics.TOPIC, word.id(), word.version(), values);
                    }
                    ctx.put(OUTPUT, output(word, values));
                }));
    }

    public static ProcessDefinition<MergeInput, TopicOutput, ProcessContext> merge() {
        return ProcessDefinition.define(MERGE, 1, MergeInput.class, TopicOutput.class, ProcessContext.class, pb -> {
            pb.description("Merges a word of the word list of topics into another: it counts as that one from now on.")
                .actsOn(QbTopics.TOPIC, "topicId")
                .permissions(QbPermissions.ADMIN_TOPIC_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = start(start, input, input.topicId());
                    ctx.put(INTO, input.into().strip());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, TopicOutput.class))
                // Both words' locks, in the codes' order, so that two merges of the same pair never wait on each other.
                .step("Lock the first word", HoldLock.<ProcessContext>exclusive(ctx -> QbTopics.lockName(
                    min(ctx.get(CODE, String.class), ctx.get(INTO, String.class)))))
                .step("Lock the second word", HoldLock.<ProcessContext>exclusive(ctx -> {
                    String code = ctx.get(CODE, String.class);
                    String into = ctx.get(INTO, String.class);
                    return code.equals(into) ? null : QbTopics.lockName(max(code, into));
                }))
                .step("Load the words", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTopics.DEFAULT_DATASET, ctx -> byCode(ctx.get(CODE, String.class)), FOUND),
                    QueryEntities.query(QbTopics.DEFAULT_DATASET, ctx -> byCode(ctx.get(INTO, String.class)), INTO
                        + ".row"),
                    QueryEntities.query(QbTopics.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Eq("mergedInto", ctx.get(CODE, String.class)))
                        .limit(Integer.MAX_VALUE).build(), MERGED_INTO_IT)))
                .compute("Merge the word", (metadata, ctx) -> merge(ctx));
        });
    }

    static void merge(ProcessContext ctx) {
        EntityInstance word = loaded(ctx);
        if (!notMerged(ctx, word)) {
            return;
        }
        EntityInstance into = first(ctx, INTO + ".row");
        if (into == null || into.id().toString().equals(word.id().toString())
            || !TopicRules.selectable(wordOf(into))) {
            ctx.reject(new Violation("into", TopicCodes.MERGE_TARGET, "A word is merged into another active word, not "
                + "into itself", Map.of("into", ctx.get(INTO, String.class))));
            return;
        }
        String target = into.id().toString();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("active", false);
        values.put("mergedInto", target);
        values.put("changedAt", ctx.opTime());
        ctx.changes().update(QbTopics.TOPIC, word.id(), word.version(), values);
        // Those merged into it count as the new one now: a word stands for one other at most.
        for (EntityInstance before : list(ctx, MERGED_INTO_IT)) {
            Map<String, Object> moved = new LinkedHashMap<>();
            moved.put("mergedInto", target);
            moved.put("changedAt", ctx.opTime());
            ctx.changes().update(QbTopics.TOPIC, before.id(), before.version(), moved);
        }
        ctx.put(OUTPUT, output(word, values));
    }

    /** The steps before a change of one word: the permission, its lock and the word as it is. */
    private static <I> ProcessDefinitionBuilder<I, TopicOutput, ProcessContext> loading(
        ProcessDefinitionBuilder<I, TopicOutput, ProcessContext> pb, java.util.function.Function<I, String> code) {
        return pb.permissions(QbPermissions.ADMIN_TOPIC_WRITE)
            .requiresVerifiedEmail()
            .contextFactory((start, input) -> start(start, input, code.apply(input)))
            .outputMapper(ctx -> ctx.get(OUTPUT, TopicOutput.class))
            .step("Lock the word", HoldLock.<ProcessContext>exclusive(ctx -> QbTopics.lockName(ctx.get(CODE))))
            .step("Load the word", QueryEntities.<ProcessContext>of(QbTopics.DEFAULT_DATASET,
                ctx -> byCode(ctx.get(CODE, String.class)), FOUND));
    }

    private static ProcessContext start(ProcessStart start, Object input, String code) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(CODE, code.strip());
        return ctx;
    }

    private static boolean notMerged(ProcessContext ctx, EntityInstance word) {
        if (word.get("mergedInto") != null) {
            ctx.reject(new Violation("topicId", TopicCodes.MERGED, "The word was merged into "
                + word.get("mergedInto"), Map.of("into", String.valueOf((Object) word.get("mergedInto")))));
            return false;
        }
        return true;
    }

    /** The names given, in the platform's languages; blanks around each dropped. */
    static Map<String, String> names(String zh, String ja, String en) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("zh", zh.strip());
        names.put("ja", ja.strip());
        names.put("en", en.strip());
        return Map.copyOf(names);
    }

    /** A row as the rules see it. */
    public static TopicRules.Word wordOf(EntityInstance row) {
        Object merged = row.get("mergedInto");
        return new TopicRules.Word(row.id().toString(), Boolean.TRUE.equals(row.get("active")),
            merged == null ? null : merged.toString());
    }

    /** The words of rows, by code. */
    public static Map<String, TopicRules.Word> words(List<EntityInstance> rows) {
        Map<String, TopicRules.Word> words = new LinkedHashMap<>();
        rows.forEach(row -> words.put(row.id().toString(), wordOf(row)));
        return words;
    }

    /** A query of the words with these codes (none: no statement). */
    public static EntityQuery byCodes(List<String> codes) {
        return EntityQuery.builder().where(new QueryPredicate.In("topicId", new ArrayList<Object>(codes)))
            .limit(Math.max(1, codes.size())).build();
    }

    private static EntityQuery byCode(String code) {
        return byCodes(code == null ? List.of() : List.of(code));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> nameOf(Object value) {
        return value instanceof Map<?, ?> map ? Map.copyOf((Map<String, String>) map) : Map.of();
    }

    private static TopicOutput output(String code, Map<String, Object> values) {
        return new TopicOutput(code, nameOf(values.get("name")), Boolean.TRUE.equals(values.get("active")),
            (String) values.get("mergedInto"));
    }

    private static TopicOutput output(EntityInstance word, Map<String, Object> changed) {
        Map<String, Object> values = new LinkedHashMap<>(word.attributes());
        values.putAll(changed);
        return output(word.id().toString(), values);
    }

    private static EntityInstance loaded(ProcessContext ctx) {
        EntityInstance word = first(ctx, FOUND);
        if (word == null) {
            throw new EntityNotFoundException(QbTopics.TOPIC + " " + ctx.get(CODE) + " not found");
        }
        return word;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        return ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : List.of();
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = list(ctx, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String min(String a, String b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String max(String a, String b) {
        return a.compareTo(b) <= 0 ? b : a;
    }
}
