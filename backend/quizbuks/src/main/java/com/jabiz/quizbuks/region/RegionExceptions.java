package com.jabiz.quizbuks.region;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The exceptions to the rule on foreign videos (F-S11, A3-15; docs/quizbuks/plans/impl/F-S11.md): mainland China may
 * answer one publication, or every publication of one sponsor, although its videos are foreign. Each needs a reason and
 * may have a last day (UTC, inclusive; past it the exception simply no longer counts, nothing has to run). Headquarters
 * sets them for any sponsor; an operator for its own sponsors only (the requester, 2026-11-07): its entry scope limits
 * what it reads, and each process checks the sponsor ({@code OperatorChecks.ownSponsor}: another's is not found).
 * <ul>
 *   <li>{@value #ADD}: a new exception;</li>
 *   <li>{@value #EXPIRY_SET}: another last day (or none), with the reason for it;</li>
 *   <li>{@value #REVOKE}: it ends now, with the reason; a revoked exception changes no more.</li>
 * </ul>
 * Written through the platform's write path only (the default dataset is {@code processOnlyWrites}), so every change
 * is audited with its before and after. An exception's own lock ({@code qb.region-exception:<id>}) orders a change of
 * it after another; new ones take none.
 */
public final class RegionExceptions {

    public static final String ADD = "QB_REGION_EXCEPTION_ADD";
    public static final String EXPIRY_SET = "QB_REGION_EXCEPTION_EXPIRY_SET";
    public static final String REVOKE = "QB_REGION_EXCEPTION_REVOKE";

    /**
     * @param kind          {@code PUBLICATION} (with {@code publicationId}) or {@code SPONSOR} (with
     *                      {@code sponsorId}, the sponsor's user id)
     * @param expiresOn     the last day it counts (UTC); none: until revoked
     */
    public record AddInput(@NotNull @Pattern(regexp = "PUBLICATION|SPONSOR") String kind,
        @Size(max = 36) String publicationId, @Size(max = 128) String sponsorId,
        @NotBlank @Size(max = QbRegion.REASON_LENGTH) String reason, LocalDate expiresOn) {}

    /** @param expiresOn the new last day (UTC); none: until revoked */
    public record ExpiryInput(@NotBlank @Size(max = 36) String exceptionId, LocalDate expiresOn,
        @NotBlank @Size(max = QbRegion.REASON_LENGTH) String reason) {}

    public record RevokeInput(@NotBlank @Size(max = 36) String exceptionId,
        @NotBlank @Size(max = QbRegion.REASON_LENGTH) String reason) {}

    public record ExceptionOutput(String exceptionId, String kind, String publicationId, String sponsorId,
        LocalDate expiresOn, boolean revoked) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String TARGET = "region.target";
    static final String FOUND = "region.found";

    private RegionExceptions() {
    }

    public static ProcessDefinition<AddInput, ExceptionOutput, ProcessContext> add() {
        return ProcessDefinition.define(ADD, 1, AddInput.class, ExceptionOutput.class, ProcessContext.class, pb -> {
            pb.description("Lets mainland China answer a publication, or every publication of a sponsor, although its "
                    + "videos are foreign; an operator for its own sponsors, headquarters for any.")
                .permissions(QbPermissions.ADMIN_REGION_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ExceptionOutput.class))
                // Before anything is read: an operator's wrong input is told as such, not as another's sponsor.
                .compute("Check the target", (metadata, ctx) -> checkTarget(ctx.get(INPUT, AddInput.class)));
            // Q48e-1: an operator's member is checked within the act; headquarters passes.
            OperatorChecks.memberWhenScoped(pb);
            pb.step("Find the publication or the sponsor", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.PUBLICATION),
                        ctx -> byKey("publicationId", target(ctx, QbRegion.PUBLICATION)), TARGET + ".publication"),
                    QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR),
                        ctx -> byKey("userId", target(ctx, QbRegion.SPONSOR)), TARGET + ".sponsor")));
            // The requester, 2026-11-07: an operator only for a sponsor it looks after (another's, or none: 404).
            OperatorChecks.ownSponsor(pb, RegionExceptions::owner);
            pb.compute("Add the exception", (metadata, ctx) -> add(ctx, ctx.get(INPUT, AddInput.class)));
        });
    }

    public static ProcessDefinition<ExpiryInput, ExceptionOutput, ProcessContext> expirySet() {
        return ProcessDefinition.define(EXPIRY_SET, 1, ExpiryInput.class, ExceptionOutput.class, ProcessContext.class,
            pb -> changing(pb
                .description("Gives an exception for foreign videos another last day, or none; an operator for its own "
                    + "sponsors, headquarters for any.")
                .actsOn(QbRegion.EXCEPTION, "exceptionId", a -> a.whenField("revoked", "false"))
                .contextFactory((start, input) -> start(start, input, input.exceptionId())))
                .compute("Set the last day", (metadata, ctx) -> {
                    ExpiryInput input = ctx.get(INPUT, ExpiryInput.class);
                    EntityInstance exception = loaded(ctx);
                    if (!open(ctx, exception) | !expiryOk(ctx, input.expiresOn())) {
                        return;
                    }
                    Map<String, Object> values = changed(ctx);
                    values.put("expiresOn", input.expiresOn());
                    values.put("reason", input.reason().strip());
                    ctx.changes().update(QbRegion.EXCEPTION, exception.id(), exception.version(), values);
                    ctx.put(OUTPUT, output(exception, input.expiresOn(), false));
                }));
    }

    public static ProcessDefinition<RevokeInput, ExceptionOutput, ProcessContext> revoke() {
        return ProcessDefinition.define(REVOKE, 1, RevokeInput.class, ExceptionOutput.class, ProcessContext.class,
            pb -> changing(pb
                .description("Ends an exception for foreign videos now; an operator for its own sponsors, headquarters "
                    + "for any.")
                .actsOn(QbRegion.EXCEPTION, "exceptionId", a -> a.whenField("revoked", "false"))
                .contextFactory((start, input) -> start(start, input, input.exceptionId())))
                .compute("Revoke it", (metadata, ctx) -> {
                    RevokeInput input = ctx.get(INPUT, RevokeInput.class);
                    EntityInstance exception = loaded(ctx);
                    if (!open(ctx, exception)) {
                        return;
                    }
                    Map<String, Object> values = changed(ctx);
                    values.put("revoked", true);
                    values.put("revokeReason", input.reason().strip());
                    ctx.changes().update(QbRegion.EXCEPTION, exception.id(), exception.version(), values);
                    ctx.put(OUTPUT, output(exception, (LocalDate) exception.get("expiresOn"), true));
                }));
    }

    /**
     * The steps before a change of an exception: the permission, the caller's membership when an operator's (Q48e-1;
     * its lock first, Q48 lock order), the exception's lock, the exception (an operator's session sees only its own
     * sponsors': another's is not found) and, explicitly, its sponsor's operator (the requester, 2026-11-07).
     */
    private static <I> ProcessDefinitionBuilder<I, ExceptionOutput, ProcessContext> changing(
        ProcessDefinitionBuilder<I, ExceptionOutput, ProcessContext> pb) {
        pb.permissions(QbPermissions.ADMIN_REGION_WRITE)
            .requiresVerifiedEmail()
            .outputMapper(ctx -> ctx.get(OUTPUT, ExceptionOutput.class));
        OperatorChecks.memberWhenScoped(pb);
        pb.step("Lock the exception", HoldLock.<ProcessContext>exclusive(ctx -> lockName(ctx.get(FOUND + ".id"))))
            .step("Load the exception", QueryEntities.<ProcessContext>of(
                QbRegion.defaultDataset(QbRegion.EXCEPTION), ctx -> byKey("exceptionId", ctx.get(FOUND + ".id")),
                FOUND));
        OperatorChecks.ownSponsor(pb, ctx -> {
            EntityInstance exception = first(ctx, FOUND);
            return exception == null ? null : exception.get("ownerId");
        });
        return pb;
    }

    /**
     * The sponsor an exception being added belongs to: the sponsor named, or the publisher of the publication named;
     * none when the target was not found.
     */
    static Object owner(ProcessContext ctx) {
        AddInput input = ctx.get(INPUT, AddInput.class);
        EntityInstance found = first(ctx, TARGET + (QbRegion.PUBLICATION.equals(input.kind()) ? ".publication"
            : ".sponsor"));
        if (found == null) {
            return null;
        }
        return QbRegion.PUBLICATION.equals(input.kind()) ? found.get("publisherId") : found.get("userId");
    }

    /** One target, of the kind's: refused (422) before anything is read. */
    static void checkTarget(AddInput input) {
        boolean forPublication = QbRegion.PUBLICATION.equals(input.kind());
        String publicationId = blankToNull(input.publicationId());
        String sponsorId = blankToNull(input.sponsorId());
        if (forPublication ? publicationId == null || sponsorId != null : sponsorId == null || publicationId != null) {
            throw new BusinessRuleViolationException(new Violation(forPublication ? "publicationId" : "sponsorId",
                RegionCodes.EXCEPTION_TARGET, "An exception names one publication or one sponsor",
                Map.of("kind", input.kind())));
        }
    }

    /** The lock of one exception's changes. */
    static String lockName(Object exceptionId) {
        return "qb.region-exception:" + exceptionId;
    }

    private static ProcessContext start(ProcessStart start, Object input, String exceptionId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(FOUND + ".id", exceptionId.strip());
        return ctx;
    }

    static void add(ProcessContext ctx, AddInput input) {
        String kind = input.kind();
        String publicationId = blankToNull(input.publicationId());
        String sponsorId = blankToNull(input.sponsorId());
        boolean forPublication = QbRegion.PUBLICATION.equals(kind);
        checkTarget(input);
        if (!expiryOk(ctx, input.expiresOn())) {
            return;
        }
        Object owner = owner(ctx);
        if (owner == null) {
            ctx.reject(new Violation(forPublication ? "publicationId" : "sponsorId",
                RegionCodes.EXCEPTION_UNKNOWN_TARGET, "No such publication or sponsor", Map.of()));
            return;
        }
        Map<String, Object> values = changed(ctx);
        values.put("kind", kind);
        values.put("publicationId", publicationId);
        values.put("sponsorId", sponsorId);
        values.put("ownerId", String.valueOf(owner));
        values.put("reason", input.reason().strip());
        values.put("expiresOn", input.expiresOn());
        values.put("revoked", false);
        values.put("createdAt", ctx.opTime());
        values.put("createdBy", ctx.request().actorId());
        Object id = ctx.changes().insert(QbRegion.EXCEPTION, values);
        ctx.put(OUTPUT, new ExceptionOutput(String.valueOf(id), kind, publicationId, sponsorId, input.expiresOn(),
            false));
    }

    /** A last day before today would be an exception never in force: refused. */
    private static boolean expiryOk(ProcessContext ctx, LocalDate expiresOn) {
        if (expiresOn != null && expiresOn.isBefore(RegionRules.today(ctx.opTime()))) {
            ctx.reject(new Violation("expiresOn", RegionCodes.EXCEPTION_EXPIRY_PAST, "The last day is in the past",
                Map.of("today", RegionRules.today(ctx.opTime()).toString())));
            return false;
        }
        return true;
    }

    private static boolean open(ProcessContext ctx, EntityInstance exception) {
        if (Boolean.TRUE.equals(exception.get("revoked"))) {
            ctx.reject(new Violation("exceptionId", RegionCodes.EXCEPTION_REVOKED, "The exception was revoked",
                Map.of()));
            return false;
        }
        return true;
    }

    private static EntityInstance loaded(ProcessContext ctx) {
        EntityInstance exception = first(ctx, FOUND);
        if (exception == null) {
            throw new EntityNotFoundException(QbRegion.EXCEPTION + " " + ctx.get(FOUND + ".id") + " not found");
        }
        return exception;
    }

    private static Map<String, Object> changed(ProcessContext ctx) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("changedAt", ctx.opTime());
        values.put("changedBy", ctx.request().actorId());
        return values;
    }

    private static ExceptionOutput output(EntityInstance exception, LocalDate expiresOn, boolean revoked) {
        return new ExceptionOutput(String.valueOf(exception.id()), (String) exception.get("kind"),
            (String) exception.get("publicationId"), (String) exception.get("sponsorId"), expiresOn, revoked);
    }

    /** The key the input names for {@code kind}; none for the other kind (its query sends nothing). */
    private static List<Object> target(ProcessContext ctx, String kind) {
        AddInput input = ctx.get(INPUT, AddInput.class);
        String key = QbRegion.PUBLICATION.equals(kind) ? input.publicationId() : input.sponsorId();
        List<Object> keys = new ArrayList<>();
        if (kind.equals(input.kind()) && key != null && !key.isBlank()) {
            keys.add(key.strip());
        }
        return keys;
    }

    private static EntityQuery byKey(String field, Object key) {
        List<Object> keys = new ArrayList<>();
        if (key instanceof List<?> list) {
            keys.addAll(list);
        } else if (key != null) {
            keys.add(key);
        }
        return EntityQuery.builder().where(new QueryPredicate.In(field, keys)).limit(1).build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @SuppressWarnings("unchecked")
    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null || found.isEmpty() ? null : found.getFirst();
    }
}
