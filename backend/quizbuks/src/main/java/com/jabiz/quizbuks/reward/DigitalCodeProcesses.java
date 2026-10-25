package com.jabiz.quizbuks.reward;

import com.jabiz.entity.Violation;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.PublishingLimits;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A publication's pool of digital codes (Q33, docs/quizbuks/plans/Q33-digital-prize.md, D-Q33-4):
 * <ul>
 *   <li>{@code QB_DIGITAL_CODES_LOAD}: the sponsor pastes codes, one a line (at most
 *       {@link PublishingLimits#DIGITAL_CODES_PER_LOAD} a load, each at most {@link PublishingLimits#DIGITAL_CODE_LENGTH}
 *       characters; blank lines and repeats within the load are dropped); they are sealed and appended to the pool
 *       after the codes loaded before, in the order given. The lines are sensitive: nothing of them is recorded.</li>
 *   <li>{@code QB_DIGITAL_CODES_REVOKE}: the sponsor takes back the codes not issued yet, while the publication is
 *       not running (a running one promised its winners the pool: end it first).</li>
 * </ul>
 * Both take the pool's lock, which settlement takes to issue a code, so counts stay right. The publication is read
 * through the sponsor's own dataset: another's is 404.
 */
public final class DigitalCodeProcesses {

    public static final String LOAD = "QB_DIGITAL_CODES_LOAD";
    public static final String REVOKE = "QB_DIGITAL_CODES_REVOKE";

    /** @param lines the codes, one a line; never recorded */
    /**
     * @param pastedCodes the codes, one a line. A distinctive name: the platform masks a {@code @Sensitive} component's
     *                    name wherever it appears, so a common word here would blank other processes' outputs.
     */
    public record LoadInput(@NotNull UUID publicationId, @Sensitive @NotBlank @Size(max = 250_000) String pastedCodes) {

        @Override
        public String toString() {
            return "LoadInput[publicationId=" + publicationId + ", pastedCodes=***]";
        }
    }

    public record RevokeInput(@NotNull UUID publicationId) {}

    /** @param loaded codes added by this load; @param available codes not issued nor revoked after it */
    public record LoadOutput(UUID publicationId, int loaded, long available) {}

    /** @param revoked codes taken back; @param available codes left (none) */
    public record RevokeOutput(UUID publicationId, int revoked, long available) {}

    static final String INPUT = "input";
    static final String PUB_ID = "publicationId";
    static final String PUBLICATION = "publication";
    static final String LINES = "lines";
    static final String SEALED = "sealed";
    static final String LAST = "last";
    static final String AVAILABLE = "available";
    static final String OUTPUT = "output";

    private static ProcessContext start(com.jabiz.process.ProcessStart start, Object input, UUID publicationId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(PUB_ID, publicationId);
        return ctx;
    }

    private static EntityInstance publication(ProcessContext ctx) {
        return ctx.get(PUBLICATION, EntityInstance.class);
    }

    /** Whether the publication is a digital prize delivered from a pool; otherwise 422. */
    static boolean pooled(ProcessContext ctx) {
        EntityInstance publication = publication(ctx);
        if (!RewardMethod.DIGITAL_PRIZE.equals(publication.get("rewardMethod"))
            || !DigitalDelivery.POOL.equals(DigitalDelivery.of(publication.get("digitalDelivery")))) {
            ctx.reject(new Violation("publicationId", RewardCodes.DIGITAL_NOT_POOLED, "The publication's prize is "
                + "not delivered from a pool of codes", Map.of()));
            return false;
        }
        return true;
    }

    /**
     * The codes of the publication in a state, oldest first, up to the most a pool covers; none when {@code wanted}
     * is false (after a refusal): an AND with an IN of no values matches nothing and costs no statement (the builder's
     * {@code where} replaces the predicate, so the two conditions are one AND).
     */
    static EntityQuery codes(ProcessContext ctx, String status, boolean wanted) {
        return EntityQuery.builder()
            .where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("publicationId", wanted ? List.of(ctx.get(PUB_ID)) : List.of()),
                new QueryPredicate.Eq("status", status))))
            .orderBy("seq", true)
            .limit(PublishingLimits.MAX_POOL_WINNERS)
            .build();
    }

    // ---- load ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<LoadInput, LoadOutput, ProcessContext> load() {
        return ProcessDefinition.define(LOAD, 1, LoadInput.class, LoadOutput.class, ProcessContext.class, pb -> pb
            .description("Loads codes into a publication's pool of digital prizes, one a line.")
            .permissions(QbPermissions.PUBLICATION_WRITE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .actsOn(QbPublications.PUBLICATION, "publicationId")
            .contextFactory((s, input) -> start(s, input, input.publicationId()))
            .outputMapper(ctx -> ctx.get(OUTPUT, LoadOutput.class))
            .step("Lock the pool", HoldLock.<ProcessContext>exclusive(
                ctx -> QbDigitalPrizes.poolLockName(ctx.get(PUB_ID))))
            .step("Load the publication", LoadEntity.by(QbPublications.sponsorDataset(QbPublications.PUBLICATION),
                PUB_ID, PUBLICATION))
            .compute("Read the lines", (metadata, ctx) -> parse(ctx))
            // The last sequence number, and how many are available: the output tells the sponsor where they stand.
            .step("Load the pool's end", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbDigitalPrizes.sponsorDataset(QbDigitalPrizes.DIGITAL_CODE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("publicationId", ctx.hasViolations() ? List.of()
                        : List.of(ctx.get(PUB_ID)))).orderBy("seq", false).limit(1).build(), LAST),
                QueryEntities.query(QbDigitalPrizes.sponsorDataset(QbDigitalPrizes.DIGITAL_CODE), ctx -> codes(ctx,
                    DigitalPrizeStatus.CODE_AVAILABLE, !ctx.hasViolations()), AVAILABLE)))
            .step("Seal the codes", StepSpec.of(SealDigitalCodes.class, NoMetadata.INSTANCE))
            .compute("Add them to the pool", (metadata, ctx) -> insert(ctx)));
    }

    static void parse(ProcessContext ctx) {
        if (!pooled(ctx)) {
            return;
        }
        LoadInput input = ctx.get(INPUT, LoadInput.class);
        Set<String> lines = new LinkedHashSet<>();
        for (String raw : input.pastedCodes().split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() > PublishingLimits.DIGITAL_CODE_LENGTH) {
                ctx.reject(new Violation("pastedCodes", RewardCodes.DIGITAL_CODE_TOO_LONG, "A code is longer than "
                    + PublishingLimits.DIGITAL_CODE_LENGTH + " characters",
                    Map.of("max", PublishingLimits.DIGITAL_CODE_LENGTH, "line", lines.size() + 1)));
                return;
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            ctx.reject(new Violation("pastedCodes", RewardCodes.DIGITAL_CODES_EMPTY, "No code to load", Map.of()));
            return;
        }
        if (lines.size() > PublishingLimits.DIGITAL_CODES_PER_LOAD) {
            ctx.reject(new Violation("pastedCodes", RewardCodes.DIGITAL_CODES_TOO_MANY, "At most "
                + PublishingLimits.DIGITAL_CODES_PER_LOAD + " codes a load", Map.of("max",
                    PublishingLimits.DIGITAL_CODES_PER_LOAD, "given", lines.size())));
            return;
        }
        ctx.put(LINES, List.copyOf(lines));
    }

    @SuppressWarnings("unchecked")
    static void insert(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        List<String> lines = ctx.get(LINES, List.class);
        List<String> sealed = ctx.get(SEALED, List.class);
        EntityInstance publication = publication(ctx);
        List<EntityInstance> last = ctx.get(LAST, List.class);
        long seq = last.isEmpty() ? 0 : new BigDecimal(String.valueOf((Object) last.getFirst().get("seq"))).longValue();
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("publicationId", publication.id());
            values.put("sponsorId", publication.get("sponsorId"));
            values.put("seq", BigDecimal.valueOf(++seq));
            values.put("sealedContent", sealed.get(i));
            values.put("hint", QbDigitalPrizes.hintOf(lines.get(i)));
            values.put("status", DigitalPrizeStatus.CODE_AVAILABLE);
            values.put("loadedAt", ctx.opTime());
            ctx.changes().insert(QbDigitalPrizes.DIGITAL_CODE, values);
        }
        long available = ((List<?>) ctx.get(AVAILABLE, List.class)).size() + lines.size();
        ctx.put(OUTPUT, new LoadOutput(UUID.fromString(String.valueOf(publication.id())), lines.size(), available));
    }

    // ---- revoke ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<RevokeInput, RevokeOutput, ProcessContext> revoke() {
        return ProcessDefinition.define(REVOKE, 1, RevokeInput.class, RevokeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Takes back the codes of a publication's pool that were not issued yet.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbPublications.PUBLICATION, "publicationId")
                .contextFactory((s, input) -> start(s, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, RevokeOutput.class))
                .step("Lock the pool", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbDigitalPrizes.poolLockName(ctx.get(PUB_ID))))
                .step("Load the publication", LoadEntity.by(QbPublications.sponsorDataset(QbPublications.PUBLICATION),
                    PUB_ID, PUBLICATION))
                .compute("Check the publication", (metadata, ctx) -> {
                    if (!pooled(ctx)) {
                        return;
                    }
                    String status = publication(ctx).get("status");
                    if (PublicationStatus.PENDING_REVIEW.equals(status) || PublicationStatus.SCHEDULED.equals(status)
                        || PublicationStatus.LIVE.equals(status) || PublicationStatus.PAUSED.equals(status)
                        || PublicationStatus.FUNDS_SHORT.equals(status)) {
                        ctx.reject(new Violation("publicationId", RewardCodes.DIGITAL_POOL_LOCKED, "The codes stay "
                            + "while the publication is " + status + ": end it first", Map.of("status", status)));
                    }
                })
                .step("Load the available codes", QueryEntities.<ProcessContext>of(
                    QbDigitalPrizes.sponsorDataset(QbDigitalPrizes.DIGITAL_CODE),
                    ctx -> codes(ctx, DigitalPrizeStatus.CODE_AVAILABLE, !ctx.hasViolations()), AVAILABLE))
                .compute("Revoke them", (metadata, ctx) -> {
                    if (ctx.hasViolations()) {
                        return;
                    }
                    List<EntityInstance> available = ctx.get(AVAILABLE, List.class);
                    List<EntityInstance> rows = new ArrayList<>(available);
                    for (EntityInstance code : rows) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("status", DigitalPrizeStatus.CODE_REVOKED);
                        values.put("revokedAt", ctx.opTime());
                        ctx.changes().update(QbDigitalPrizes.DIGITAL_CODE, code.id(), code.version(), values);
                    }
                    ctx.put(OUTPUT, new RevokeOutput(UUID.fromString(String.valueOf(publication(ctx).id())),
                        rows.size(), 0));
                }));
    }

    private DigitalCodeProcesses() {}
}
