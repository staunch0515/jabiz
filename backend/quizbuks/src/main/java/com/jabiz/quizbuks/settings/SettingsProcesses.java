package com.jabiz.quizbuks.settings;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Saving one's own settings ({@value #SAVE}, Q57, Q59a): takers and sponsors alike ({@code qb.account.read}, which
 * both hold), for themselves only. No verified address is needed: nothing here reaches anyone else.
 */
public final class SettingsProcesses {

    public static final String SAVE = "QB_SETTINGS_SAVE";

    /**
     * The settings to change; a setting left out (null) stays as it is.
     *
     * @param soundOn          the sound effects (Q57)
     * @param contentLanguages the languages of the quizzes shown (Q59a), each once; empty: the interface language
     *                         only, whichever it is
     */
    public record SaveInput(Boolean soundOn,
        @Size(max = 3) List<@Pattern(regexp = "ja|zh|en") String> contentLanguages) {

        public SaveInput(Boolean soundOn) {
            this(soundOn, null);
        }
    }

    /** The settings after the save; no content languages: the interface language. */
    public record SettingsOutput(boolean soundOn, List<String> contentLanguages) {
    }

    private static final String INPUT = "input";
    private static final String CURRENT = "current";
    private static final String OUTPUT = "output";

    public static ProcessDefinition<SaveInput, SettingsOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, SettingsOutput.class, ProcessContext.class, pb -> {
            pb.description("Saves one's own settings of the app or the sponsor console (the sound effects, the "
                    + "languages of the quizzes shown).")
                .permissions(QbPermissions.ACCOUNT_READ)
                .contextFactory(SettingsProcesses::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, SettingsOutput.class));
            pb.step("Lock the settings", HoldLock.<ProcessContext>exclusive(
                ctx -> QbSettings.lockName(ctx.request().actorId())));
            pb.step("Load the settings", QueryEntities.<ProcessContext>of(QbSettings.DEFAULT_DATASET,
                ctx -> mine(ctx.request().actorId()), CURRENT));
            pb.compute("Save", (metadata, ctx) -> write(ctx, ctx.get(INPUT, SaveInput.class)));
        });
    }

    private static void write(ProcessContext ctx, SaveInput input) {
        EntityInstance current = first(ctx);
        boolean soundOff = input.soundOn() != null ? !input.soundOn()
            : current != null && Boolean.TRUE.equals(current.get("soundOff"));
        String languages = input.contentLanguages() != null ? languages(input.contentLanguages())
            : current == null ? null : (String) current.get("contentLanguages");
        if (current == null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", ctx.request().actorId());
            row.put("soundOff", soundOff);
            row.put("contentLanguages", languages);
            ctx.changes().insert(QbSettings.SETTINGS, row);
        } else if (!Boolean.valueOf(soundOff).equals(current.get("soundOff"))
            || !Objects.equals(languages, current.get("contentLanguages"))) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("soundOff", soundOff);
            values.put("contentLanguages", languages);
            ctx.changes().update(QbSettings.SETTINGS, current.id(), current.version(), values);
        }
        ctx.put(OUTPUT, new SettingsOutput(!soundOff, languages == null ? List.of()
            : Arrays.asList(languages.split(","))));
    }

    /** The languages as stored: in the platform's order, each once; none as null. */
    static String languages(List<String> chosen) {
        List<String> ordered = QbContent.LANGUAGES.stream().filter(chosen::contains).toList();
        return ordered.isEmpty() ? null : String.join(",", ordered);
    }

    private static EntityQuery mine(Object userId) {
        List<Object> ids = new ArrayList<>();
        if (userId != null) {
            ids.add(userId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("userId", ids)).limit(1).build();
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance first(ProcessContext ctx) {
        List<EntityInstance> rows = ctx.contains(CURRENT) ? (List<EntityInstance>) ctx.get(CURRENT) : null;
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private static ProcessContext start(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private SettingsProcesses() {
    }
}
