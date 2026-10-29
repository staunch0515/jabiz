package com.jabiz.quizbuks.identity;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.FileEntities;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * What the account processes share (docs/quizbuks/plans/Q2-accounts.md, 流程 and D-Q2-10): the user's account lock,
 * taken before anything of the account is read; the rows of a user (one per entity at most) read through the default
 * datasets by the immutable {@code userId}; the platform's user (for a mail's address), the uploader of a file, the
 * revision check and writing the profile's name.
 */
public final class AccountEditing {

    static final String INPUT = "input";
    static final String USER_ID = "userId";
    static final String PROFILES = "profiles";
    static final String SPONSORS = "sponsors";
    static final String BANS = "bans";
    static final String USERS = "users";
    static final String FILES = "files";
    static final String OUTPUT = "output";

    static ProcessContext start(ProcessStart start, Object input, String userId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(USER_ID, userId);
        return ctx;
    }

    /** The user the process is about (the caller for one's own account). */
    static String userId(ProcessContext ctx) {
        return ctx.get(USER_ID, String.class);
    }

    static <I, O> void lock(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the account", HoldLock.<ProcessContext>exclusive(ctx -> QbAccounts.lockName(userId(ctx))));
    }

    /** Loads into {@code key} the current rows of {@code entity} for the user (one at most). */
    static <I, O> void loadOfUser(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String step, String entity,
        String key) {
        pb.step(step, QueryEntities.<ProcessContext>of(QbAccounts.defaultDataset(entity), ctx -> ofUser(userId(ctx)),
            key));
    }

    static EntityQuery ofUser(String userId) {
        List<Object> ids = new ArrayList<>();
        if (userId != null) {
            ids.add(userId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("userId", ids)).limit(Integer.MAX_VALUE).build();
    }

    /**
     * Loads into {@link #USERS} the platform's user (current version), to know their address. A user id that is not a
     * UUID names no platform user (only tests act as such): nothing is found.
     */
    static <I, O> void loadUser(ProcessDefinitionBuilder<I, O, ProcessContext> pb, Function<ProcessContext, ?> id) {
        pb.step("Load the user", QueryEntities.<ProcessContext>of(SecurityEntities.USER_DATASET, ctx -> EntityQuery
            .builder().where(new QueryPredicate.In("userId", uuids(id.apply(ctx)))).limit(1).build(), USERS));
    }

    public static List<Object> uuids(Object id) {
        List<Object> ids = new ArrayList<>();
        UUID uuid = uuidOrNull(id);
        if (uuid != null) {
            ids.add(uuid);
        }
        return ids;
    }

    /** A user id as the platform writes it (a UUID in lower case); anything else as given. */
    public static String canonical(String id) {
        UUID uuid = uuidOrNull(id);
        return uuid == null ? id : uuid.toString();
    }

    public static UUID uuidOrNull(Object id) {
        if (id == null) {
            return null;
        }
        if (id instanceof UUID uuid) {
            return uuid;
        }
        try {
            String text = id.toString();
            UUID uuid = UUID.fromString(text);
            // UUID.fromString accepts short groups ("1-1-1-1-1"); only the canonical form names a key.
            return uuid.toString().equalsIgnoreCase(text) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Whether the user loaded into {@link #USERS} has an address a mail can go to. */
    static boolean hasAddress(ProcessContext ctx) {
        EntityInstance user = first(ctx, USERS);
        Object email = user == null ? null : user.get("email");
        return email != null && !email.toString().isBlank();
    }

    /** Loads into {@link #FILES} the platform's record of the file {@code file} names (none when it names none). */
    static <I, O> void loadFile(ProcessDefinitionBuilder<I, O, ProcessContext> pb, Function<ProcessContext, UUID> file) {
        pb.step("Load the file", QueryEntities.<ProcessContext>of(FileEntities.DATASET, ctx -> {
            List<Object> ids = new ArrayList<>();
            UUID id = file.apply(ctx);
            if (id != null) {
                ids.add(id);
            }
            return EntityQuery.builder().where(new QueryPredicate.In(FileEntities.FILE_ID, ids)).limit(1).build();
        }, FILES));
    }

    /**
     * Refuses a file newly put into a field that its owner did not upload (422 {@code QB_FILE_NOT_OWN}): nobody shows
     * another person's file by knowing its key. A file the field already holds stays; a file the platform does not
     * know is left to the platform's own check of the file field (400).
     */
    static void requireOwnFile(ProcessContext ctx, String owner, String field, UUID given, Object stored) {
        if (given == null || same(stored, given)) {
            return;
        }
        EntityInstance file = first(ctx, FILES);
        if (file != null && !owner.equals(file.get(FileEntities.UPLOADED_BY))) {
            ctx.reject(new Violation(field, AccountCodes.FILE_NOT_OWN, "File " + given + " was not uploaded by you",
                Map.of("fileId", given.toString())));
        }
    }

    /**
     * Whether the caller saw the row as it is: a given {@code baseRevision} other than the current one is 422
     * {@code code}; none means the last writer wins.
     */
    static boolean current(ProcessContext ctx, EntityInstance row, Long baseRevision, String code) {
        long revision = row == null ? 0 : number(row.get("revision"));
        if (baseRevision != null && baseRevision != revision) {
            ctx.reject(new Violation("baseRevision", code, "Changed meanwhile: revision " + revision + ", not "
                + baseRevision, Map.of("revision", revision, "baseRevision", baseRevision)));
            return false;
        }
        return true;
    }

    /**
     * Writes the user's profile name (M-01, M-03: a sponsor's name is their profile's): a new profile with the name
     * only, or the name changed. Returns the profile's revision afterwards.
     */
    static long writeName(ProcessContext ctx, EntityInstance profile, String userId, String displayName) {
        if (profile == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("userId", userId);
            values.put("displayName", displayName);
            values.put("createdAt", ctx.opTime());
            values.put("editedAt", ctx.opTime());
            values.put("revision", BigDecimal.ONE);
            ctx.changes().insert(QbAccounts.PROFILE, values);
            return 1;
        }
        long revision = number(profile.get("revision"));
        if (same(profile.get("displayName"), displayName)) {
            return revision;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("displayName", displayName);
        values.put("editedAt", ctx.opTime());
        values.put("revision", BigDecimal.valueOf(revision + 1));
        ctx.changes().update(QbAccounts.PROFILE, profile.id(), profile.version(), values);
        return revision + 1;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** The values of {@code values} that differ from {@code row} (numbers by value, times by instant). */
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
        if (stored == null || value == null) {
            return stored == value;
        }
        if (stored instanceof BigDecimal a && value instanceof Number b) {
            return a.compareTo(new BigDecimal(b.toString())) == 0;
        }
        if (stored instanceof UUID || value instanceof UUID) {
            return stored.toString().equals(value.toString());
        }
        if (stored instanceof OffsetDateTime || stored instanceof Instant) {
            return Objects.equals(instant(stored), instant(value));
        }
        return Objects.equals(stored, value);
    }

    public static long number(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).longValueExact();
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private AccountEditing() {}
}
