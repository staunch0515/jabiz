package com.jabiz.runtime.security;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.secret.SealSecret;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The tokens that revoke a user's authorization at a provider when its link goes (decision D67 item 5; docs/design/
 * 10-security.md section 12): Apple requires an app to revoke it when the account is deleted. The refresh token of
 * an Apple sign-in or link is kept sealed ({@code JABIZ_SEAL_KEY}) under the link it came with
 * ({@value #KEEP}). Whatever removes the link - the account's erasure, the user unlinking it, an administrator - the
 * job {@value #JOB} finds tokens whose link is gone and revokes each ({@value #REVOKE}, one transaction per token):
 * revoked, the token is deleted; refused or unreachable, it is tried again later, with growing pauses, at most
 * {@value #MAX_ATTEMPTS} times, and then an administrator gets a task. A newer link of the same account at the provider
 * replaces an older link's token, which is then not revoked: the authorization is the account's, one per app.
 *
 * <p>The provider is called inside the item's transaction (5 seconds at most): the items run one after another in the
 * background, so at most one connection waits on a provider at a time.
 */
@Configuration
public class IdentityTokens {

    public static final String TOKEN = "SecIdentityToken";
    public static final String DATASET = "urn:jabiz:dataset:platform:SecIdentityToken";
    public static final String KEEP = "SEC_IDENTITY_TOKEN_KEEP";
    public static final String REVOKE = "SEC_IDENTITY_TOKEN_REVOKE";
    public static final String JOB = "jabiz.identity-token-revocation";
    static final String DUE = "jabiz.identity_tokens_to_revoke";
    /** Declared by {@value #KEEP}, granted to no role: it runs only as a sub-process of the sign-in and the link. */
    public static final String KEEP_PERMISSION = "security.identity-token.keep";
    /** Declared by {@value #REVOKE}, granted to no role: only the system runs it, for the job. */
    public static final String REVOKE_PERMISSION = "security.identity-token.revoke";
    /** After this many failed attempts a token is no longer tried; it stays for an administrator to look into. */
    public static final int MAX_ATTEMPTS = 12;
    static final Duration FIRST_PAUSE = Duration.ofMinutes(15);
    static final Duration LONGEST_PAUSE = Duration.ofDays(1);

    public static final EntityDefinition ENTITY = EntityDefinition.define(TOKEN, eb -> {
        eb.physicalTable("sec_identity_token");
        eb.primaryKey("identityId");
        // The link's key, not a reference: the token outlives its link (it is revoked after the link goes), and a
        // reference would stop the link's removal.
        eb.field("identityId", f -> f.physicalColumn("identity_id").immutable(true).required(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:user-identity"));
        eb.field("provider", f -> f.physicalColumn("provider").immutable(true).required(true).asText(40));
        // Which account at the provider, hashed: a newer link of the same account replaces the older one's token.
        eb.field("subjectHash", f -> f.physicalColumn("subject_hash").immutable(true).required(true).asText(64));
        eb.field("sealedToken", f -> f.physicalColumn("sealed_token").required(true).asText(4000).sensitive());
        eb.field("updatedAt", f -> f.physicalColumn("updated_at").required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("revokeAttempts", f -> f.physicalColumn("revoke_attempts").required(true).asNumeric(9, 0));
        eb.field("nextAttemptAt", f -> f.physicalColumn("next_attempt_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").immutable(true).asVersion());
        eb.listView("default", lv -> lv
            .columns("identityId", "provider", "updatedAt", "revokeAttempts", "nextAttemptAt")
            .filters("identityId", "provider", "revokeAttempts")
            .sorts("updatedAt", "nextAttemptAt")
            .defaultSort("updatedAt", false));
    });

    /**
     * @param identityId   the link the token came with
     * @param subject      the provider's subject of the link
     * @param refreshToken the provider's refresh token, as its token endpoint gave it
     */
    public record KeepInput(@NotNull UUID identityId, @NotBlank String provider, @NotBlank String subject,
        @Sensitive String refreshToken) {
        @Override
        public String toString() {
            return "KeepInput[identityId=" + identityId + ", provider=" + provider + ", refreshToken=***]";
        }
    }

    public record KeepOutput(UUID identityId) {}

    public record RevokeInput(@NotNull UUID identityId) {}

    /** {@code REVOKED}, {@code FAILED} (tried again later) or {@code SKIPPED} (no token, or its link is back). */
    public record RevokeOutput(UUID identityId, String outcome) {}

    // Context keys.
    static final String INPUT = "input";
    static final String SEALED = "sealed_token";
    static final String KEPT = "kept";
    static final String TOKENS = "tokens";
    static final String LINKS = "links";
    static final String OUTCOME = "outcome";
    static final String GAVE_UP = "gave_up";
    static final String TASK_TYPE = "identity-token-revocation";
    static final String TASK_TITLE = "task.identity-token-revocation";

    @Bean
    EntityDefinition secIdentityTokenEntity() {
        return ENTITY;
    }

    @Bean
    DatasetDefinition secIdentityTokenDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        // Read by those who manage links (the token itself is sensitive: never returned); written by the processes only.
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(TOKEN)
            .asDefault()
            .permissions(SecurityPermissions.IDENTITY_WRITE, SecurityPermissions.IDENTITY_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** Keeps (or replaces) the token of a link, sealed for that link. */
    @Bean
    ProcessDefinition<KeepInput, KeepOutput, ProcessContext> secIdentityTokenKeepProcess() {
        return ProcessDefinition.define(KEEP, 1, KeepInput.class, KeepOutput.class, ProcessContext.class, pb -> pb
            .description("Keeps the refresh token of a provider link, sealed, to revoke the authorization later.")
            .permissions(KEEP_PERMISSION)
            .subProcessOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new KeepOutput(keepInput(ctx).identityId()))
            .step("Seal the token", SealSecret.<ProcessContext>of(ctx -> keepInput(ctx).refreshToken(),
                ctx -> context(keepInput(ctx).identityId()), SEALED))
            // This link's token, and those of older links of the same account at the provider: revoking one of those
            // would revoke the account's authorization, the newer link's included (Apple's is one per app).
            .step("Load the kept tokens", QueryEntities.<ProcessContext>of(DATASET,
                ctx -> Rbac.all(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("provider", keepInput(ctx).provider()),
                    new QueryPredicate.Eq("subjectHash", subjectHash(keepInput(ctx))))), "identityId"), KEPT))
            .compute("Keep it", (metadata, ctx) -> keep(ctx)));
    }

    /** Revokes the token of a link that is gone; see the class comment. */
    @Bean
    ProcessDefinition<RevokeInput, RevokeOutput, ProcessContext> secIdentityTokenRevokeProcess() {
        return ProcessDefinition.define(REVOKE, 1, RevokeInput.class, RevokeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Revokes, at the provider, the authorization of a link that no longer exists.")
                .permissions(REVOKE_PERMISSION)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new RevokeOutput(revokeInput(ctx).identityId(),
                    String.valueOf(ctx.get(OUTCOME))))
                // Read again: the selection only proposed the item (a link may be back, a token revoked already).
                .step("Load the token and its link", QueryEntities.all(
                    QueryEntities.<ProcessContext>query(DATASET, ctx -> Rbac.all(
                        new QueryPredicate.Eq("identityId", revokeInput(ctx).identityId()), "identityId"), TOKENS),
                    QueryEntities.<ProcessContext>query(SecurityEntities.USER_IDENTITY_DATASET, ctx -> Rbac.all(
                        new QueryPredicate.Eq("userIdentityId", revokeInput(ctx).identityId()), "userIdentityId"),
                        LINKS)))
                .step("Revoke it at the provider", RevokeIdentityToken.spec())
                .compute("Delete it, or try again later", (metadata, ctx) -> settle(ctx))
                // Given up: an administrator revokes it at the provider (its console), then deletes the token.
                .step("Ask an administrator", CreateTask.<ProcessContext>when(ctx -> ctx.get(GAVE_UP) != null,
                    ctx -> TaskSpec.forPermission(TASK_TYPE, TASK_TITLE, Map.of("provider",
                            String.valueOf(ctx.get(GAVE_UP)), "attempts", String.valueOf(MAX_ATTEMPTS)),
                            SecurityPermissions.IDENTITY_WRITE)
                        .about(TOKEN, revokeInput(ctx).identityId())
                        .source("identity-token:" + revokeInput(ctx).identityId()))));
    }

    /** Every quarter of an hour unless {@code jabiz.security.oidc.revocation.cron}: the tokens whose link is gone. */
    @Bean
    JobDefinition<RevokeInput> identityTokenRevocationJob(
        @Value("${jabiz.security.oidc.revocation.cron:0 */15 * * * *}") String cron,
        @Qualifier("secIdentityTokenRevokeProcess")
        ProcessDefinition<RevokeInput, RevokeOutput, ProcessContext> revoke) {
        return JobDefinition.forEach(JOB, cron, ZoneOffset.UTC,
            JobItems.template(DUE, scheduledTime -> Map.of("dueBy", scheduledTime, "maxAttempts", MAX_ATTEMPTS),
                "identityId"),
            revoke, (scheduledTime, row) -> new RevokeInput(Rows.uuid(row.get("identityId"))));
    }

    /**
     * An Apple provider keeps its tokens sealed: without {@code jabiz.security.seal.key} ({@code JABIZ_SEAL_KEY}) its
     * sign-ins could not keep them, and deleted accounts could not be revoked at Apple (decision D67 item 5).
     */
    @Bean
    com.jabiz.runtime.check.PlatformCheck identityTokenChecks(OidcProviders providers,
        com.jabiz.runtime.security.secret.SecretSealer sealer) {
        return () -> sealer.configured() ? List.of() : providers.all().stream().filter(OidcProvider::isApple)
            .map(provider -> com.jabiz.runtime.check.CheckProblem.error(SessionChecks.CATEGORY,
                OidcProviders.PROPERTY + " (" + provider.id() + ")", "kind apple needs jabiz.security.seal.key "
                    + "(JABIZ_SEAL_KEY): Apple's tokens are kept sealed, to revoke the authorization when an account "
                    + "is deleted"))
            .toList();
    }

    /** The provider's subject as kept: SHA-256 of provider and subject, enough to tell one account from another. */
    static String subjectHash(KeepInput input) {
        return com.jabiz.runtime.security.secret.SingleUseSecrets.sha256Hex(input.provider() + "\n" + input.subject());
    }

    /** What a token is sealed for: its link, so that a sealed value cannot be moved to another row. */
    static String context(UUID identityId) {
        return "identity-token:" + identityId;
    }

    /** The pause before attempt {@code attempts + 1}: a quarter of an hour, doubling, at most a day. */
    static Duration pause(int attempts) {
        Duration pause = FIRST_PAUSE.multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 10));
        return pause.compareTo(LONGEST_PAUSE) > 0 ? LONGEST_PAUSE : pause;
    }

    private static void keep(ProcessContext ctx) {
        KeepInput input = keepInput(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sealedToken", ctx.get(SEALED));
        values.put("updatedAt", ctx.opTime());
        values.put("revokeAttempts", 0);
        values.put("nextAttemptAt", null);
        EntityInstance own = null;
        for (EntityInstance kept : list(ctx, KEPT)) {
            if (input.identityId().equals(Rows.uuid(kept.id()))) {
                own = kept;
            } else {
                // An older link of the same account: its authorization is the one this link has now.
                ctx.changes().delete(TOKEN, kept.id(), kept.version());
            }
        }
        if (own == null) {
            values.put("identityId", input.identityId());
            values.put("provider", input.provider());
            values.put("subjectHash", subjectHash(input));
            ctx.changes().insert(TOKEN, values);
        } else {
            ctx.changes().update(TOKEN, own.id(), own.version(), values);
        }
    }

    private static void settle(ProcessContext ctx) {
        String outcome = String.valueOf(ctx.get(OUTCOME));
        List<EntityInstance> tokens = list(ctx, TOKENS);
        if (tokens.isEmpty()) {
            return;
        }
        EntityInstance token = tokens.getFirst();
        if (RevokeIdentityToken.REVOKED.equals(outcome)) {
            ctx.changes().delete(TOKEN, token.id(), token.version());
        } else if (RevokeIdentityToken.FAILED.equals(outcome)) {
            int attempts = ((Number) token.get("revokeAttempts")).intValue() + 1;
            Instant next = ctx.opTime().plus(pause(attempts));
            ctx.changes().update(TOKEN, token.id(), token.version(),
                Map.of("revokeAttempts", attempts, "nextAttemptAt", next));
            if (attempts >= MAX_ATTEMPTS) {
                ctx.put(GAVE_UP, token.get("provider"));
            }
        }
    }

    static KeepInput keepInput(ProcessContext ctx) {
        return (KeepInput) ctx.get(INPUT);
    }

    static RevokeInput revokeInput(ProcessContext ctx) {
        return (RevokeInput) ctx.get(INPUT);
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object rows = ctx.get(key);
        return rows instanceof List<?> found ? (List<EntityInstance>) found : List.of();
    }
}
