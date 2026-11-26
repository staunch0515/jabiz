package com.jabiz.quizbuks.avatar;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Choosing one's own avatar ({@value #SET}, Q114a, docs/quizbuks/plans/impl/Q114.md section 3.3): the initial or one
 * of {@link AvatarPresets#CODES}, for oneself only. Each choice is a whole one, so the last save wins, as for the
 * settings: there is nothing to merge, and no version to compare.
 */
public final class AvatarProcesses {

    public static final String SET = "QB_AVATAR_SET";

    /** What the avatar is: the initial (the default, no row needed) or a preset. Q114b adds the photo. */
    public enum Kind { INITIAL, PRESET }

    /**
     * The avatar chosen.
     *
     * @param kind   what it is
     * @param preset the preset's code, given exactly when {@code kind} is {@code PRESET}
     */
    public record SetInput(@NotNull Kind kind, @Size(max = 10) String preset) {
    }

    /** The avatar after the save. */
    public record AvatarOutput(Kind kind, String preset) {
    }

    private static final String INPUT = "input";
    private static final String CURRENT = "current";
    private static final String OUTPUT = "output";

    public static ProcessDefinition<SetInput, AvatarOutput, ProcessContext> set() {
        return ProcessDefinition.define(SET, 1, SetInput.class, AvatarOutput.class, ProcessContext.class, pb -> {
            pb.description("Chooses the caller's own avatar: the initial or one of the preset drawings. Only the "
                    + "caller sees it.")
                .permissions(QbPermissions.ME)
                .requiresVerifiedEmail()
                .contextFactory(AvatarProcesses::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, AvatarOutput.class));
            CallerChecks.refuseBanned(pb);
            pb.step("Lock the avatar", HoldLock.<ProcessContext>exclusive(
                ctx -> QbAvatars.lockName(ctx.request().actorId())));
            pb.step("Load the avatar", QueryEntities.<ProcessContext>of(QbAvatars.DEFAULT_DATASET,
                ctx -> mine(ctx.request().actorId()), CURRENT));
            pb.compute("Save", (metadata, ctx) -> write(ctx, ctx.get(INPUT, SetInput.class)));
        });
    }

    private static void write(ProcessContext ctx, SetInput input) {
        String preset = input.preset() == null || input.preset().isBlank() ? null : input.preset().strip();
        boolean presetKind = input.kind() == Kind.PRESET;
        // (an immutable list's contains refuses null: a preset kind without a code is unknown too)
        if (presetKind ? preset == null || !AvatarPresets.CODES.contains(preset) : preset != null) {
            ctx.reject(new Violation("preset", AvatarCodes.PRESET_UNKNOWN, "Not a preset avatar to choose: " + preset,
                Map.of("presets", AvatarPresets.CODES)));
            return;
        }
        EntityInstance current = first(ctx);
        if (current == null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", ctx.request().actorId());
            row.put("kind", input.kind().name());
            row.put("preset", preset);
            row.put("editedAt", ctx.opTime());
            ctx.changes().insert(QbAvatars.AVATAR, row);
        } else if (!input.kind().name().equals(current.get("kind")) || !Objects.equals(preset, current.get("preset"))) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("kind", input.kind().name());
            values.put("preset", preset);
            values.put("editedAt", ctx.opTime());
            ctx.changes().update(QbAvatars.AVATAR, current.id(), current.version(), values);
        }
        ctx.put(OUTPUT, new AvatarOutput(input.kind(), preset));
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

    private AvatarProcesses() {
    }
}
