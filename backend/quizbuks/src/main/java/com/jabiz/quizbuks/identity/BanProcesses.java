package com.jabiz.quizbuks.identity;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jabiz.quizbuks.identity.AccountEditing.BANS;
import static com.jabiz.quizbuks.identity.AccountEditing.INPUT;
import static com.jabiz.quizbuks.identity.AccountEditing.OUTPUT;
import static com.jabiz.quizbuks.identity.AccountEditing.USERS;
import static com.jabiz.quizbuks.identity.AccountEditing.first;
import static com.jabiz.quizbuks.identity.AccountEditing.list;

/**
 * Banning and unbanning users (P-02, 5.3-11; docs/quizbuks/plans/Q2-accounts.md, D-Q2-4). A banned user cannot sign
 * in at any entry ({@link QbSignInGuard}) and loses their session at its next refresh; an access token already issued
 * still works until it expires, so valuable actions check the ban again. Administrators are not banned (they are
 * disabled through the platform), and nobody bans themselves. The history of {@code QbUserBan} is the record of bans
 * and lifts.
 */
public final class BanProcesses {

    public static final String BAN = "QB_USER_BAN";
    public static final String UNBAN = "QB_USER_UNBAN";

    static final String ASSIGNMENTS = "assignments";
    static final String ROLES = "roles";

    public record BanInput(@NotBlank String userId, @NotBlank @Size(max = AccountLimits.REASON_LENGTH) String reason) {}

    public record UnbanInput(@NotBlank String userId, @Size(max = AccountLimits.REASON_LENGTH) String note) {}

    public record BanOutput(String userId, boolean banned, long revision) {}

    /**
     * Whether a role is an administrator's: one the administration's entry accepts
     * ({@code jabiz.security.entries.admin.accepted-roles}; {@code *}, the platform's default when the entry is not
     * configured, makes every role an administrator's, so nobody holding a role is banned).
     */
    static boolean administrative(Set<String> adminRoles, String roleCode) {
        return adminRoles.contains("*") || (roleCode != null && adminRoles.contains(roleCode));
    }

    /** @param adminRoles the roles the administration's entry accepts */
    public static ProcessDefinition<BanInput, BanOutput, ProcessContext> ban(Set<String> adminRoles) {
        Set<String> admins = Set.copyOf(adminRoles);
        return ProcessDefinition.define(BAN, 1, BanInput.class, BanOutput.class, ProcessContext.class, pb -> {
            pb.description("Bans a user: they cannot sign in until unbanned, and cannot publish or withdraw meanwhile.")
                .permissions(QbPermissions.ADMIN_USERS_BAN)
                .requiresVerifiedEmail()
                .actsOn(SecurityEntities.USER, "userId")
                .contextFactory((start, input) -> AccountEditing.start(start, input, AccountEditing.canonical(input.userId())))
                .outputMapper(ctx -> ctx.get(OUTPUT, BanOutput.class));
            AccountEditing.lock(pb);
            AccountEditing.loadUser(pb, AccountEditing::userId);
            pb.step("Load the user's roles", QueryEntities.<ProcessContext>of(SecurityEntities.USER_ROLE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("userId",
                    AccountEditing.uuids(AccountEditing.userId(ctx)))).limit(Integer.MAX_VALUE).build(), ASSIGNMENTS));
            pb.step("Load the roles", QueryEntities.<ProcessContext>of(SecurityEntities.ROLE_DATASET, ctx -> {
                List<Object> ids = new ArrayList<>();
                list(ctx, ASSIGNMENTS).forEach(assignment -> ids.add(assignment.get("roleId")));
                return EntityQuery.builder().where(new QueryPredicate.In("roleId", ids)).limit(Integer.MAX_VALUE)
                    .build();
            }, ROLES));
            loadBan(pb);
            pb.compute("Ban", (metadata, ctx) -> ban(ctx, admins));
        });
    }

    static void ban(ProcessContext ctx, Set<String> adminRoles) {
        BanInput input = ctx.get(INPUT, BanInput.class);
        String userId = requireUser(ctx);
        if (userId.equals(ctx.request().actorId())) {
            ctx.reject(new Violation("userId", AccountCodes.BAN_SELF, "You cannot ban yourself", Map.of()));
            return;
        }
        if (list(ctx, ROLES).stream().anyMatch(role -> administrative(adminRoles, role.get("roleCode")))) {
            ctx.reject(new Violation("userId", AccountCodes.BAN_ADMIN, "Administrators are disabled, not banned",
                Map.of()));
            return;
        }
        EntityInstance ban = first(ctx, BANS);
        if (BanRules.banned(ban == null ? null : ban.attributes())) {
            ctx.reject(new Violation("userId", AccountCodes.BAN_STATE, "The user is banned already",
                Map.of("banned", true)));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("banned", true);
        values.put("reason", input.reason());
        values.put("bannedAt", ctx.opTime());
        values.put("bannedBy", ctx.request().actorId());
        values.put("liftedAt", null);
        values.put("liftedBy", null);
        values.put("liftNote", null);
        write(ctx, ban, userId, values);
    }

    public static ProcessDefinition<UnbanInput, BanOutput, ProcessContext> unban() {
        return ProcessDefinition.define(UNBAN, 1, UnbanInput.class, BanOutput.class, ProcessContext.class, pb -> {
            pb.description("Lifts a user's ban: they can sign in again.")
                .permissions(QbPermissions.ADMIN_USERS_BAN)
                .requiresVerifiedEmail()
                .actsOn(SecurityEntities.USER, "userId")
                .contextFactory((start, input) -> AccountEditing.start(start, input, AccountEditing.canonical(input.userId())))
                .outputMapper(ctx -> ctx.get(OUTPUT, BanOutput.class));
            AccountEditing.lock(pb);
            AccountEditing.loadUser(pb, AccountEditing::userId);
            loadBan(pb);
            pb.compute("Lift the ban", (metadata, ctx) -> unban(ctx));
        });
    }

    static void unban(ProcessContext ctx) {
        UnbanInput input = ctx.get(INPUT, UnbanInput.class);
        String userId = requireUser(ctx);
        EntityInstance ban = first(ctx, BANS);
        if (!BanRules.banned(ban == null ? null : ban.attributes())) {
            ctx.reject(new Violation("userId", AccountCodes.BAN_STATE, "The user is not banned",
                Map.of("banned", false)));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("banned", false);
        values.put("liftedAt", ctx.opTime());
        values.put("liftedBy", ctx.request().actorId());
        values.put("liftNote", input.note() == null || input.note().isBlank() ? null : input.note());
        write(ctx, ban, userId, values);
    }

    /** Loads the user's ban (none for an id that names no platform user: nobody else is banned). */
    private static <I, O> void loadBan(com.jabiz.process.ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the ban", QueryEntities.<ProcessContext>of(QbAccounts.defaultDataset(QbAccounts.BAN),
            ctx -> EntityQuery.builder().where(new QueryPredicate.In("userId",
                AccountEditing.uuids(AccountEditing.userId(ctx)))).limit(Integer.MAX_VALUE).build(), BANS));
    }

    /** The user the input names, in its canonical form: 404 unless the platform has them (deleted ones it has not). */
    private static String requireUser(ProcessContext ctx) {
        EntityInstance user = first(ctx, USERS);
        if (user == null) {
            throw new EntityNotFoundException(SecurityEntities.USER + " " + AccountEditing.userId(ctx) + " not found");
        }
        return String.valueOf(user.id());
    }

    private static void write(ProcessContext ctx, EntityInstance ban, String userId, Map<String, Object> values) {
        long revision = ban == null ? 1 : AccountEditing.number(ban.get("revision")) + 1;
        values.put("revision", BigDecimal.valueOf(revision));
        if (ban == null) {
            values.put("userId", userId);
            ctx.changes().insert(QbAccounts.BAN, values);
        } else {
            ctx.changes().update(QbAccounts.BAN, ban.id(), ban.version(), values);
        }
        ctx.put(OUTPUT, new BanOutput(userId, Boolean.TRUE.equals(values.get("banned")), revision));
    }

    private BanProcesses() {}
}
