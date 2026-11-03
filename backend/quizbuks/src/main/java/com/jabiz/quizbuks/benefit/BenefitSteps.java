package com.jabiz.quizbuks.benefit;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.certification.Certifications.Certification;
import com.jabiz.quizbuks.certification.RoundHolders;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.FileEntities;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** What the sponsored benefits' processes share (Q89a): the opening checks, holdings, bans, files and reads. */
final class BenefitSteps {

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String FILES = "benefitFiles";
    static final String OTHER_BAN = "otherBan";
    /** The key prefix of {@link RoundHolders#loadHoldings} for the benefits' certifications. */
    private static final String HOLDINGS = "benefit.";

    private BenefitSteps() {}

    /** Every benefit process: a sponsor's permission, a verified address, not banned (CLAUDE.md, accounts). */
    static <I, O> void common(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String description) {
        pb.description(description)
            .permissions(QbPermissions.BENEFIT_USE)
            .requiresVerifiedEmail()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            });
        CallerChecks.refuseBanned(pb);
    }

    /** Reads whether the caller holds {@code certification} (their records, the rounds and their passes). */
    static <I, O> void loadHolding(ProcessDefinitionBuilder<I, O, ProcessContext> pb, Certification certification) {
        RoundHolders.loadHoldings(pb, ctx -> List.of(certification), HOLDINGS);
    }

    static boolean holds(ProcessContext ctx, Certification certification) {
        return RoundHolders.heldKeys(ctx, List.of(certification), HOLDINGS).contains(certification.key());
    }

    /** 422 {@code QB_BENEFIT_NOT_CERTIFIED} when the caller does not hold {@code certification}. */
    static void requireHolding(ProcessContext ctx, Certification certification) {
        if (!holds(ctx, certification)) {
            throw new BusinessRuleViolationException(new Violation(null, BenefitCodes.NOT_CERTIFIED,
                "Pass the certification " + certification.key() + " first", Map.of("certification",
                    certification.key())));
        }
    }

    /** The other side's ban (at most one row), into {@link #OTHER_BAN}. */
    static QueryEntities.Metadata<ProcessContext> ban(Function<ProcessContext, Object> userId) {
        return QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> EntityQuery.builder()
            .where(new QueryPredicate.In("userId", AccountEditing.uuids(userId.apply(ctx)))).limit(1).build(),
            OTHER_BAN);
    }

    static void refuseBannedOther(ProcessContext ctx) {
        if (first(ctx, OTHER_BAN) != null) {
            throw refusal(BenefitCodes.UNAVAILABLE, "The other sponsor's account is suspended", Map.of());
        }
    }

    /** The platform's records of {@code files}, into {@link #FILES}. */
    static QueryEntities.Metadata<ProcessContext> files(Function<ProcessContext, List<UUID>> files) {
        return QueryEntities.query(FileEntities.DATASET, ctx -> {
            List<UUID> ids = files.apply(ctx).stream().filter(Objects::nonNull).distinct().toList();
            return EntityQuery.builder().where(new QueryPredicate.In(FileEntities.FILE_ID, new ArrayList<>(ids)))
                .limit(Math.max(1, ids.size())).build();
        }, FILES);
    }

    /**
     * Refuses a file newly put into {@code field} that the caller did not upload (422 {@code QB_FILE_NOT_OWN}): files
     * the row already holds stay. A file the platform does not know is left to the platform's check of the field.
     */
    static void requireOwnFiles(ProcessContext ctx, String field, List<UUID> given, List<Object> stored) {
        List<String> kept = stored.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        for (UUID file : given) {
            if (file == null || kept.contains(file.toString())) {
                continue;
            }
            EntityInstance known = list(ctx, FILES).stream()
                .filter(row -> file.toString().equals(String.valueOf((Object) row.get(FileEntities.FILE_ID))))
                .findFirst().orElse(null);
            if (known != null && !ctx.request().actorId().equals(known.get(FileEntities.UPLOADED_BY))) {
                ctx.reject(new Violation(field, AccountCodes.FILE_NOT_OWN, "File " + file + " was not uploaded by you",
                    Map.of("fileId", file.toString())));
            }
        }
    }

    /** The values of numbered file fields ({@code image1}…), the given ones first, the rest empty. */
    static void putFiles(Map<String, Object> values, String[] fields, List<UUID> files) {
        for (int i = 0; i < fields.length; i++) {
            values.put(fields[i], i < files.size() ? files.get(i) : null);
        }
    }

    static List<Object> stored(EntityInstance row, String[] fields) {
        List<Object> stored = new ArrayList<>();
        if (row != null) {
            for (String field : fields) {
                stored.add(row.get(field));
            }
        }
        return stored;
    }

    /** The files given, without blanks or repeats, in order. */
    static List<UUID> distinct(List<UUID> files) {
        return files == null ? List.of() : files.stream().filter(Objects::nonNull).distinct().toList();
    }

    static EntityQuery byId(String field, Object id) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, id == null ? List.of()
            : List.of(String.valueOf(id)))).limit(1).build();
    }

    static BusinessRuleViolationException refusal(String code, String message, Map<String, Object> params) {
        return new BusinessRuleViolationException(new Violation(null, code, message, params));
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.contains(key) ? ctx.get(key) : null;
        return value == null ? List.of() : (List<EntityInstance>) value;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    static long number(Object value) {
        return AccountEditing.number(value);
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
