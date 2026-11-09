package com.jabiz.quizbuks.block;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A taker's blocks (F-S8, docs/quizbuks/plans/impl/F-S8.md):
 * <ul>
 *   <li>{@value #ADD}: blocks one object of a publication's card ({@link BlockRules}): the publication, its quiz, its
 *       sponsor or its shared video's provider. The caller names the publication and the kind; the object comes from
 *       the card, so it is always one the caller could see. Blocking again what is blocked answers the row there is;
 *       at most {@code qb.block.max-per-user} rows;</li>
 *   <li>{@value #REMOVE}: removes one of the caller's blocks (the undo of "not interested", the list in the app's
 *       settings, the "you blocked this" page). Another's, or none, is 404.</li>
 * </ul>
 * Both take the caller's own lock ({@link QbBlocks#lockName}) and read their blocks under it; no publication's, start
 * or wallet lock: answering is not touched. Anyone who answers ({@code qb.play}) blocks; no verified address is needed
 * (it only hides things from oneself).
 */
public final class BlockProcesses {

    public static final String ADD = "QB_BLOCK_ADD";
    public static final String REMOVE = "QB_BLOCK_REMOVE";

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String PARAMS = "params";
    static final String CARD = "card";
    static final String OBJECT = "object";
    static final String MINE = "mine";

    /**
     * @param publicationId the publication whose card names the object
     * @param kind          what of it is blocked: {@code PUBLICATION}, {@code QUIZ}, {@code SPONSOR} or {@code PROVIDER}
     */
    public record AddInput(@NotNull UUID publicationId,
        @NotBlank @Pattern(regexp = "PUBLICATION|QUIZ|SPONSOR|PROVIDER") String kind) {}

    /** @param blockId the block removed */
    public record RemoveInput(@NotBlank @Size(max = 36) String blockId) {}

    /**
     * @param blockId  the block (after a removal: the one removed)
     * @param kind     what it blocks
     * @param objectId the object
     * @param created  whether this call made it (false: it was there already, or it was removed)
     */
    public record BlockOutput(String blockId, String kind, String objectId, boolean created) {}

    private BlockProcesses() {}

    public static ProcessDefinition<AddInput, BlockOutput, ProcessContext> add() {
        return ProcessDefinition.define(ADD, 1, AddInput.class, BlockOutput.class, ProcessContext.class, pb -> pb
            .description("Blocks a publication, its quiz, its sponsor or its video's provider for the caller: none of "
                + "it is shown to them or started by them until the block is removed.")
            .permissions(QbPermissions.PLAY)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, BlockOutput.class))
            .step("Load the limit", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                QbParams.BLOCK_MAX_PER_USER))
            .step("Load the card", QueryEntities.<ProcessContext>of(QbPublications.defaultDataset(
                QbPublications.CARD), ctx -> EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                    List.of(ctx.get(INPUT, AddInput.class).publicationId()))).limit(1).build(), CARD))
            .compute("Take the object from the card", (metadata, ctx) -> ctx.put(OBJECT, object(ctx)))
            .step("Take the caller's turn", HoldLock.<ProcessContext>exclusive(
                ctx -> QbBlocks.lockName(ctx.request().actorId())))
            .step("Load the caller's blocks", QueryEntities.<ProcessContext>of(QbBlocks.DEFAULT_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("userId", ctx.request().actorId()))
                    .limit(maxPerUser(ctx) + 1).build(), MINE))
            .compute("Block", (metadata, ctx) -> block(ctx)));
    }

    public static ProcessDefinition<RemoveInput, BlockOutput, ProcessContext> remove() {
        return ProcessDefinition.define(REMOVE, 1, RemoveInput.class, BlockOutput.class, ProcessContext.class, pb -> pb
            .description("Removes one of the caller's blocks: what it blocked is shown to them and can be started "
                + "again.")
            .permissions(QbPermissions.PLAY)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, BlockOutput.class))
            .step("Take the caller's turn", HoldLock.<ProcessContext>exclusive(
                ctx -> QbBlocks.lockName(ctx.request().actorId())))
            .step("Load the block", QueryEntities.<ProcessContext>of(QbBlocks.DEFAULT_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("blockId",
                    List.of(ctx.get(INPUT, RemoveInput.class).blockId()))).limit(1).build(), MINE))
            .compute("Remove it", (metadata, ctx) -> {
                EntityInstance block = first(ctx, MINE);
                if (block == null || !Objects.equals(ctx.request().actorId(), text(block.get("userId")))) {
                    throw new EntityNotFoundException(QbBlocks.BLOCK + " " + ctx.get(INPUT, RemoveInput.class)
                        .blockId() + " not found");
                }
                ctx.changes().delete(QbBlocks.BLOCK, block.id(), block.version());
                ctx.put(OUTPUT, new BlockOutput(String.valueOf(block.id()), text(block.get("kind")),
                    text(block.get("objectId")), false));
            }));
    }

    /** The blocked object the card names; 404 without a card of a sponsor's (a shop's is not the App's). */
    private static BlockRules.Block object(ProcessContext ctx) {
        AddInput input = ctx.get(INPUT, AddInput.class);
        EntityInstance card = first(ctx, CARD);
        if (card == null || !PublisherKind.sponsor(card.get(PublisherKind.KIND_FIELD))) {
            throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + input.publicationId()
                + " not found");
        }
        BlockRules.Target target = target(card);
        if (BlockRules.own(ctx.request().actorId(), target)) {
            throw refusal(BlockCodes.OWN, "You do not block your own publication, yourself or your own video",
                Map.of());
        }
        return BlockRules.objectOf(input.kind(), target).map(id -> new BlockRules.Block(input.kind(), id))
            .orElseThrow(() -> refusal(BlockCodes.NO_PROVIDER, "This publication uses no shared video: it has no "
                + "video provider to block", Map.of()));
    }

    private static void block(ProcessContext ctx) {
        BlockRules.Block wanted = ctx.get(OBJECT, BlockRules.Block.class);
        List<EntityInstance> mine = list(ctx, MINE);
        for (EntityInstance row : mine) {
            if (wanted.kind().equals(text(row.get("kind"))) && wanted.objectId().equals(text(row.get("objectId")))) {
                ctx.put(OUTPUT, new BlockOutput(String.valueOf(row.id()), wanted.kind(), wanted.objectId(), false));
                return;
            }
        }
        int max = maxPerUser(ctx);
        if (mine.size() >= max) {
            throw refusal(BlockCodes.LIMIT, "You have " + max + " blocks already: remove some first",
                Map.of("max", max));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userId", ctx.request().actorId());
        values.put("kind", wanted.kind());
        values.put("objectId", wanted.objectId());
        values.put("createdAt", ctx.opTime());
        Object blockId = ctx.changes().insert(QbBlocks.BLOCK, values);
        ctx.put(OUTPUT, new BlockOutput(String.valueOf(blockId), wanted.kind(), wanted.objectId(), true));
    }

    /** What of a card a block can name (the card read by any process). */
    public static BlockRules.Target target(EntityInstance card) {
        return new BlockRules.Target(text(card.get("publicationId")), text(card.get("quizId")),
            text(card.get(PublisherKind.ID_FIELD)), text(card.get("videoProviderId")));
    }

    static int maxPerUser(ProcessContext ctx) {
        Integer value = ctx.contains(PARAMS) ? ctx.get(PARAMS, ParamValues.class)
            .find(QbParams.BLOCK_MAX_PER_USER, BigDecimal.class).map(BigDecimal::intValue).orElse(null) : null;
        return BlockRules.maxPerUser(value);
    }

    static BusinessRuleViolationException refusal(String code, String message, Map<String, Object> params) {
        return new BusinessRuleViolationException(new Violation(null, code, message, params));
    }

    static String text(Object value) {
        return value == null ? null : value.toString();
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
}
