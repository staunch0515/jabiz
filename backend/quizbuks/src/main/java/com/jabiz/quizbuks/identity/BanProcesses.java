package com.jabiz.quizbuks.identity;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.process.steps.CallProcess;
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
 * disabled through the platform), and nobody bans themselves. Banning a sponsor pauses their running publications in
 * the same transaction ({@code QB_PUBLICATION_BAN_PAUSE}, Q10b): only an administrator resumes them, after the unban.
 * The sponsor reads a fixed reason ("account suspended"), not the ban's, which is internal (review L3). The history of
 * {@code QbUserBan} is the record of bans and lifts.
 */
public final class BanProcesses {

    public static final String BAN = "QB_USER_BAN";
    public static final String UNBAN = "QB_USER_UNBAN";

    static final String ASSIGNMENTS = "assignments";
    static final String ROLES = "roles";
    static final String PUBLICATIONS = "publications";

    private static final Set<String> PAUSABLE = Set.of(PublicationStatus.SCHEDULED, PublicationStatus.LIVE,
        PublicationStatus.FUNDS_SHORT, PublicationStatus.PAUSED);

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
            // A banned sponsor's running publications stop at once (Q10b, S-4), in this transaction: each is paused
            // as an administrator's, so the sponsor cannot resume it. Read by the immutable sponsor, filtered here.
            pb.step("Load the sponsor's publications", QueryEntities.<ProcessContext>of(
                QbPublications.defaultDataset(QbPublications.PUBLICATION), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("sponsorId", AccountEditing.userId(ctx)))
                    .limit(Integer.MAX_VALUE).build(), PUBLICATIONS));
            pb.compute("Ban", (metadata, ctx) -> ban(ctx, admins));
            // Lock order: the account's lock (held) before the publications' pairs, in the order of their ids.
            pb.step("Pause the sponsor's publications", CallProcess.forEach(PublicationAdmin.BAN_PAUSE, 1,
                BanProcesses::pauses, null));
        });
    }

    /**
     * The inputs of {@code QB_PUBLICATION_BAN_PAUSE} for the banned sponsor's running publications (scheduled, live,
     * underfunded, or paused by themselves), in the order of their ids; none when the ban was refused. One that ends
     * or goes back to its draft before the pause takes its lock is left as it is (review L2).
     */
    static List<PublicationAdmin.BanPauseInput> pauses(ProcessContext ctx) {
        if (ctx.hasViolations() || !ctx.contains(OUTPUT)) {
            return List.of();
        }
        return list(ctx, PUBLICATIONS).stream()
            .filter(publication -> PAUSABLE.contains(String.valueOf((Object) publication.get("status")))
                && !PublicationStatus.BY_ADMIN.equals(publication.get("pausedBy")))
            .map(publication -> String.valueOf(publication.id()))
            .sorted()
            .map(id -> new PublicationAdmin.BanPauseInput(java.util.UUID.fromString(id)))
            .toList();
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
