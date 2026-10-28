package com.jabiz.runtime.erasure;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.mail.MailEntities;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.retention.DeletionGuard;
import com.jabiz.runtime.security.RefreshTokenStore;
import com.jabiz.runtime.security.RevokeSessionsStep;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.security.erasure.ErasedEntity;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The erasure of an account (docs/design/10-security.md section 17; decision D62 item 3), in an ordinary transaction:
 * the user is disabled for good and marked erased, its name, addresses, language and password are cleared by a new
 * version, its links to providers, second factor, roles and mail preferences are tombstoned, its sessions end, and the
 * applications' erasers run in the order declared. The erasure is recorded in {@code sys_erasure}; the purge
 * ({@link ErasurePurge}) then overwrites what the history and the append-only records still hold. A legal hold or a
 * retention period on the user, or on a row an eraser deletes, refuses it (422), and the request waits.
 */
@Configuration
public class AccountErasure {

    public static final String NAME = "ACCOUNT_ERASE";

    /**
     * @param userId      the user to erase
     * @param requestedBy who asked for it: the user, or the administrator who arranged it; the system when unknown
     */
    public record EraseInput(@NotNull UUID userId, String requestedBy) {}

    /** @param erasureId the erasure, which the purge takes up */
    public record EraseOutput(String erasureId, String userId) {}

    static final String USER_ID = "user_id";
    static final String USER = "user";
    static final String REQUESTED_BY = "requested_by";
    static final String IDENTITIES = "identities";
    static final String FACTORS = "factors";
    static final String ROLES = "roles";
    static final String PREFERENCES = "preferences";
    static final String ERASURE_ID = "erasure_id";

    /** The user's personal fields that a version clears now; the purge overwrites them in every version. */
    static final List<String> USER_FIELDS = List.of("displayName", "email", "locale", "passwordHash",
        "verifiedEmail", "registeredEmail", "emailVerifiedAt");

    /** Most rows of one kind the erasure reads; more is an error, never a shortened list. */
    private static final int ROWS = 100_000;

    public static final ProcessDefinition<EraseInput, EraseOutput, ProcessContext> ERASE =
        ProcessDefinition.define(NAME, 1, EraseInput.class, EraseOutput.class, ProcessContext.class, pb -> pb
            .description("Erases an account: disables the user for good, clears its personal data and ends its"
                + " sessions; the purge then overwrites the history.")
            .permissions(ErasurePermissions.ERASE)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(USER_ID, input.userId().toString());
                ctx.put(REQUESTED_BY, input.requestedBy() == null || input.requestedBy().isBlank()
                    ? start.request().actorId() : input.requestedBy());
                return ctx;
            })
            .outputMapper(ctx -> new EraseOutput((String) ctx.get(ERASURE_ID), (String) ctx.get(USER_ID)))
            .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
            .step("Load the user's links, factors, roles and preferences", QueryEntities.all(
                QueryEntities.query(SecurityEntities.USER_IDENTITY_DATASET, AccountErasure::ofUser, IDENTITIES),
                QueryEntities.query(SecurityEntities.USER_MFA_DATASET, AccountErasure::ofUser, FACTORS),
                QueryEntities.query(SecurityEntities.USER_ROLE_DATASET, AccountErasure::ofUser, ROLES),
                QueryEntities.query(SecurityEntities.USER_MAIL_PREFERENCE_DATASET, AccountErasure::ofUser,
                    PREFERENCES)))
            .step("Erase the account", EraseAccountStep.class, new EraseAccountStep.Metadata())
            .step("End the user's sessions", RevokeSessionsStep.class,
                new RevokeSessionsStep.Metadata(USER, RefreshTokenStore.REASON_ERASED))
            // The purge runs after the commit, on the maintenance connection, as the consumer of the event.
            .step("Announce it", PublishEvent.of(ErasurePurge.ERASED_EVENT, ctx -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("erasureId", ctx.get(ERASURE_ID));
                payload.put("userId", ctx.get(USER_ID));
                return payload;
            })));

    /** The user's rows; read to one past the processes' maximum, so that more rows refuse rather than shorten. */
    static EntityQuery ofUser(ProcessContext ctx) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("userId", ctx.get(USER_ID))).limit(Integer.MAX_VALUE)
            .build();
    }

    @Bean
    ProcessDefinition<EraseInput, EraseOutput, ProcessContext> accountEraseProcess() {
        return ERASE;
    }

    /**
     * The step that registers the erasure's changes: the user's version, the tombstones, the applications' rows, and
     * the {@code sys_erasure} row.
     */
    @Component
    public static class EraseAccountStep implements StepHandler<EraseAccountStep.Metadata, ProcessContext> {

        public record Metadata() {}

        private final PersonalDataErasers erasers;
        private final EntityDefinitionRegistry entities;
        private final DatasetRegistry datasets;
        private final DatasetEntityManager entityManager;
        private final DeletionGuard guard;
        private final StorageAdapterRegistry storages;
        private final EntityIdGenerator ids;
        private final String poolRef;

        public EraseAccountStep(PersonalDataErasers erasers, EntityDefinitionRegistry entities,
            DatasetRegistry datasets, DatasetEntityManager entityManager, DeletionGuard guard,
            StorageAdapterRegistry storages, EntityIdGenerator ids,
            @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
            this.erasers = erasers;
            this.entities = entities;
            this.datasets = datasets;
            this.entityManager = entityManager;
            this.guard = guard;
            this.storages = storages;
            this.ids = ids;
            this.poolRef = poolRef;
        }

        @Override
        public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
            EntityInstance user = ctx.get(USER, EntityInstance.class);
            if (user.get("erasedAt") != null) {
                ctx.reject(new Violation(null, PlatformErrorCodes.ACCOUNT_ERASED, "User " + user.id()
                    + " was erased already"));
                return Mono.empty();
            }
            EntityDefinition userDef = entities.getOrThrow(SecurityEntities.USER);
            return guard.refusal(storages.getEngine(poolRef), userDef, user.id(), user.attributes())
                .map(violation -> {
                    ctx.reject(violation);
                    return false;
                })
                .defaultIfEmpty(true)
                .flatMap(free -> free ? erase(ctx, user) : Mono.empty());
        }

        private Mono<Void> erase(ProcessContext ctx, EntityInstance user) {
            Map<String, Object> cleared = new LinkedHashMap<>();
            cleared.put("enabled", false);
            cleared.put("erasedAt", ctx.opTime());
            USER_FIELDS.forEach(field -> cleared.put(field, null));
            ctx.changes().update(SecurityEntities.USER, user.id(), user.version(), cleared);
            for (String rows : List.of(IDENTITIES, FACTORS, ROLES, PREFERENCES)) {
                for (EntityInstance row : instances(ctx, rows)) {
                    ctx.changes().delete(row.entityType(), row.id(), row.version());
                }
            }
            return optOuts(user)
                .doOnNext(optOut -> ctx.changes().delete(optOut.entityType(), optOut.id(), optOut.version()))
                .thenMany(Flux.fromIterable(erasers.entities()))
                .concatMap(erased -> applicationRows(erased, user.id())
                    .doOnNext(row -> register(ctx, erased, row)))
                .then(Mono.defer(() -> record(ctx, user)));
        }

        /** The opt-outs of mail of the user's addresses (an ordinary table, kept by address): removed. */
        private Flux<EntityInstance> optOuts(EntityInstance user) {
            List<Object> addresses = new java.util.ArrayList<>();
            for (String field : List.of("email", "verifiedEmail", "registeredEmail")) {
                Object address = user.get(field);
                if (address != null && !addresses.contains(address.toString().toLowerCase(java.util.Locale.ROOT))) {
                    addresses.add(address.toString().toLowerCase(java.util.Locale.ROOT));
                }
            }
            if (addresses.isEmpty()) {
                return Flux.empty();
            }
            DatasetDefinition dataset = datasets.findForEntity(MailEntities.ADDRESS_OPT_OUT).orElseThrow();
            return entityManager.queryAll(dataset, entities.getOrThrow(MailEntities.ADDRESS_OPT_OUT),
                EntityQuery.builder().where(new QueryPredicate.In("address", addresses)).limit(ROWS).build());
        }

        /** The rows of a declared entity that hold the user's id, through its default dataset (no scope applies). */
        private Flux<EntityInstance> applicationRows(ErasedEntity erased, Object userId) {
            if (erased.action() == ErasedEntity.RowAction.KEEP) {
                return Flux.empty();
            }
            EntityDefinition def = entities.getOrThrow(erased.entity());
            DatasetDefinition dataset = datasets.findForEntity(erased.entity()).orElseThrow();
            EntityQuery query = EntityQuery.builder()
                .where(new QueryPredicate.Eq(erased.userField(), String.valueOf(userId))).limit(ROWS + 1).build();
            return entityManager.queryAll(dataset, def, query).collectList().flatMapMany(rows -> rows.size() > ROWS
                ? Flux.error(new IllegalStateException("More than " + ROWS + " rows of " + erased.entity()
                    + " belong to the user: the erasure does not leave part of them"))
                : Flux.fromIterable(rows));
        }

        private static void register(ProcessContext ctx, ErasedEntity erased, EntityInstance row) {
            if (erased.action() == ErasedEntity.RowAction.DELETE) {
                ctx.changes().delete(row.entityType(), row.id(), row.version());
            } else {
                Map<String, Object> cleared = new LinkedHashMap<>();
                erased.fields().forEach(field -> cleared.put(field, null));
                ctx.changes().update(row.entityType(), row.id(), row.version(), cleared);
            }
        }

        private Mono<Void> record(ProcessContext ctx, EntityInstance user) {
            // A UUIDv7 like every new key (the platform's generator, which does not block).
            String erasureId = String.valueOf(ids.next(entities.getOrThrow(SecurityEntities.USER)));
            ctx.put(ERASURE_ID, erasureId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("erasure_id", UUID.fromString(erasureId));
            row.put("user_id", UUID.fromString(String.valueOf(user.id())));
            row.put("erased_time", ctx.opTime());
            row.put("requested_by", String.valueOf(ctx.get(REQUESTED_BY)));
            row.put("process_seq_id", ctx.processSeqId());
            return storages.getEngine(poolRef).insert("sys_erasure", row);
        }

        @SuppressWarnings("unchecked")
        private static List<EntityInstance> instances(ProcessContext ctx, String key) {
            Object value = ctx.get(key);
            return value == null ? List.of() : (List<EntityInstance>) value;
        }
    }
}
