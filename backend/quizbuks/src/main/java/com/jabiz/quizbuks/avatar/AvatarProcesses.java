package com.jabiz.quizbuks.avatar;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.FileEntities;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Choosing one's own avatar ({@value #SET}, Q114, docs/quizbuks/plans/impl/Q114.md section 3.3): the initial, one of
 * {@link AvatarPresets#CODES}, or (Q114b) a photo the caller uploaded, for oneself only. Each choice is a whole one,
 * so the last save wins, as for the settings: there is nothing to merge, and no version to compare.
 *
 * <p>A photo the save replaces or removes is deleted at once (the reference is cleared and saved first, then
 * {@code FILE_DELETE}): only this row refers to it, and the taker expects it gone. A photo is newly chosen only while
 * {@code qb.avatar.photo.enabled} is on (section 8 item 4: the privacy policy that mentions it must be in effect
 * first); one already chosen keeps showing and can be removed whatever the switch says.
 */
public final class AvatarProcesses {

    public static final String SET = "QB_AVATAR_SET";

    /** What the avatar is: the initial (the default, no row needed), a preset or a photo. */
    public enum Kind { INITIAL, PRESET, PHOTO }

    /**
     * The avatar chosen.
     *
     * @param kind   what it is
     * @param preset the preset's code, given exactly when {@code kind} is {@code PRESET}
     * @param photo  a file of the policy {@code qb.taker-avatar} the caller uploaded, given exactly when {@code kind}
     *               is {@code PHOTO}
     */
    public record SetInput(@NotNull Kind kind, @Size(max = 10) String preset, UUID photo) {

        /** A choice without a photo (Q114a's shape). */
        public SetInput(Kind kind, String preset) {
            this(kind, preset, null);
        }
    }

    /** The avatar after the save. */
    public record AvatarOutput(Kind kind, String preset, UUID photo) {
    }

    private static final String INPUT = "input";
    private static final String CURRENT = "current";
    private static final String FILE = "file";
    private static final String PARAMS = "params";
    private static final String OLD_PHOTO = "oldPhoto";
    private static final String OUTPUT = "output";

    public static ProcessDefinition<SetInput, AvatarOutput, ProcessContext> set() {
        return ProcessDefinition.define(SET, 1, SetInput.class, AvatarOutput.class, ProcessContext.class, pb -> {
            pb.description("Chooses the caller's own avatar: the initial, one of the preset drawings or a photo they "
                    + "uploaded. Only the caller sees it; a photo replaced or removed is deleted.")
                .permissions(QbPermissions.ME)
                .requiresVerifiedEmail()
                .contextFactory(AvatarProcesses::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, AvatarOutput.class));
            CallerChecks.refuseBanned(pb);
            pb.step("Load the switch", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                QbParams.AVATAR_PHOTO_ENABLED));
            pb.step("Load the photo", QueryEntities.<ProcessContext>of(FileEntities.DATASET, ctx -> {
                List<Object> ids = new ArrayList<>();
                UUID photo = ctx.get(INPUT, SetInput.class).photo();
                if (photo != null) {
                    ids.add(photo);
                }
                return EntityQuery.builder().where(new QueryPredicate.In(FileEntities.FILE_ID, ids)).limit(1).build();
            }, FILE));
            pb.step("Lock the avatar", HoldLock.<ProcessContext>exclusive(
                ctx -> QbAvatars.lockName(ctx.request().actorId())));
            pb.step("Load the avatar", QueryEntities.<ProcessContext>of(QbAvatars.DEFAULT_DATASET,
                ctx -> mine(ctx.request().actorId()), CURRENT));
            pb.compute("Choose", (metadata, ctx) -> write(ctx, ctx.get(INPUT, SetInput.class)));
            // Root CLAUDE.md, files: the reference is cleared and saved first; the platform refuses to delete a file
            // anything still refers to.
            pb.step("Save", SaveChanges.now());
            pb.step("Delete the old photo", CallProcess.<ProcessContext>when(
                ctx -> !ctx.hasViolations() && ctx.contains(OLD_PHOTO), FileProcesses.DELETE, 1,
                ctx -> new FileProcesses.DeleteInput(ctx.get(OLD_PHOTO, UUID.class)), null));
        });
    }

    private static void write(ProcessContext ctx, SetInput input) {
        String preset = input.preset() == null || input.preset().isBlank() ? null : input.preset().strip();
        UUID photo = input.photo();
        EntityInstance current = first(ctx, CURRENT);
        UUID stored = uuid(current == null ? null : current.get("photo"));
        // (an immutable list's contains refuses null: a preset kind without a code is unknown too)
        if (input.kind() == Kind.PRESET ? preset == null || !AvatarPresets.CODES.contains(preset) : preset != null) {
            ctx.reject(new Violation("preset", AvatarCodes.PRESET_UNKNOWN, "Not a preset avatar to choose: " + preset,
                Map.of("presets", AvatarPresets.CODES)));
            return;
        }
        if (!photoAllowed(ctx, input.kind(), photo, stored)) {
            return;
        }
        if (stored != null && !stored.equals(photo)) {
            ctx.put(OLD_PHOTO, stored);
        }
        if (current == null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", ctx.request().actorId());
            row.put("kind", input.kind().name());
            row.put("preset", preset);
            row.put("photo", photo);
            row.put("editedAt", ctx.opTime());
            ctx.changes().insert(QbAvatars.AVATAR, row);
        } else if (!input.kind().name().equals(current.get("kind")) || !Objects.equals(preset, current.get("preset"))
            || !Objects.equals(photo, stored)) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("kind", input.kind().name());
            values.put("preset", preset);
            values.put("photo", photo);
            values.put("editedAt", ctx.opTime());
            ctx.changes().update(QbAvatars.AVATAR, current.id(), current.version(), values);
        }
        ctx.put(OUTPUT, new AvatarOutput(input.kind(), preset, photo));
    }

    /**
     * Whether the photo given goes with the kind and may be used: none unless the kind is a photo; for a photo, a file
     * of the photos' policy the caller uploaded, newly chosen only with the switch on (the one already chosen may be
     * saved again).
     */
    private static boolean photoAllowed(ProcessContext ctx, Kind kind, UUID photo, UUID stored) {
        if (kind != Kind.PHOTO) {
            if (photo == null) {
                return true;
            }
            return invalid(ctx, photo, "A photo goes only with the kind PHOTO");
        }
        if (photo == null) {
            return invalid(ctx, null, "The kind PHOTO needs a photo");
        }
        EntityInstance file = first(ctx, FILE);
        if (file == null || !QbAvatars.PHOTO_POLICY.equals(file.get(FileEntities.POLICY))) {
            return invalid(ctx, photo, "Not a photo uploaded for an avatar: " + photo);
        }
        Object uploader = file.get(FileEntities.UPLOADED_BY);
        if (uploader == null || !uploader.toString().equals(String.valueOf((Object) ctx.request().actorId()))) {
            ctx.reject(new Violation("photo", AccountCodes.FILE_NOT_OWN, "File " + photo + " was not uploaded by you",
                Map.of("fileId", photo.toString())));
            return false;
        }
        if (!photo.equals(stored) && !Boolean.TRUE.equals(ctx.get(PARAMS, ParamValues.class)
            .find(QbParams.AVATAR_PHOTO_ENABLED, Boolean.class).orElse(null))) {
            ctx.reject(new Violation("photo", AvatarCodes.PHOTO_DISABLED, "Photos cannot be chosen as avatars now",
                Map.of()));
            return false;
        }
        return true;
    }

    private static boolean invalid(ProcessContext ctx, UUID photo, String message) {
        ctx.reject(new Violation("photo", AvatarCodes.PHOTO_INVALID, message,
            photo == null ? Map.of() : Map.of("fileId", photo.toString())));
        return false;
    }

    private static EntityQuery mine(Object userId) {
        List<Object> ids = new ArrayList<>();
        if (userId != null) {
            ids.add(userId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("userId", ids)).limit(1).build();
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
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
