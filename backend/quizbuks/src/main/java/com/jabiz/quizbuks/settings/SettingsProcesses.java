package com.jabiz.quizbuks.settings;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saving one's own settings ({@value #SAVE}, Q57): takers and sponsors alike ({@code qb.account.read}, which both
 * hold), for themselves only. No verified address is needed: nothing here reaches anyone else.
 */
public final class SettingsProcesses {

    public static final String SAVE = "QB_SETTINGS_SAVE";

    /** The settings to change; a setting left out stays as it is. */
    public record SaveInput(@NotNull Boolean soundOn) {
    }

    /** The settings after the save. */
    public record SettingsOutput(boolean soundOn) {
    }

    private static final String INPUT = "input";
    private static final String CURRENT = "current";
    private static final String OUTPUT = "output";

    public static ProcessDefinition<SaveInput, SettingsOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, SettingsOutput.class, ProcessContext.class, pb -> {
            pb.description("Saves one's own settings of the app or the sponsor console (the sound effects).")
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
        boolean soundOff = !input.soundOn();
        EntityInstance current = first(ctx);
        if (current == null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", ctx.request().actorId());
            row.put("soundOff", soundOff);
            ctx.changes().insert(QbSettings.SETTINGS, row);
        } else if (!Boolean.valueOf(soundOff).equals(current.get("soundOff"))) {
            ctx.changes().update(QbSettings.SETTINGS, current.id(), current.version(), Map.of("soundOff", soundOff));
        }
        ctx.put(OUTPUT, new SettingsOutput(!soundOff));
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
