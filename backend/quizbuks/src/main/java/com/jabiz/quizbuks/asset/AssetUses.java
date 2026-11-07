package com.jabiz.quizbuks.asset;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Where the sponsor's assets are used (F-C1b, docs/quizbuks/plans/impl/F-C1.md): the content and publication
 * processes that save a place the sponsor filled from their library say which asset it came from, and record it here
 * in their own transaction ({@code QbAssetUse}, one row per place). The client copies the content ("选了就复制"); this
 * only checks that the asset is the caller's (404 otherwise) and suits the place ({@link AssetRules#suits}), and
 * records the asset's revision, so that a later change of the asset shows the place as older content.
 *
 * <p>Locks: after the process's own lock, the library's ({@link QbAssets#lockName}) shared, and only when the
 * process picks an asset or deletes places that may have uses; the asset processes take it alone and never a content
 * lock, so the two never wait for each other. Without either, no lock is taken and nothing is read.
 */
public final class AssetUses {

    /** A place of the caller's content and where it is (its key once the save has made it). */
    public record Place(String targetKind, Object targetId) {}

    /**
     * A place the input says was filled from an asset.
     *
     * @param field        the input's field of the asset id (the violation's field)
     * @param materialKind for a material, its kind; none for a material of another language, which is no place for an
     *                     asset (it follows its base)
     */
    public record Pick(String field, String targetKind, String materialKind, UUID assetId) {}

    static final String PICKED = "assetUses.picked";
    static final String STORED = "assetUses.stored";

    /**
     * Adds the steps that take the library's lock (shared) and read the assets picked, the caller's only, and the
     * uses of the places, all in one statement. Add them after the process's own lock and reads (the functions may
     * read what those loaded).
     *
     * @param assets the assets the input picks (nulls left out)
     * @param places the existing places whose uses may be replaced or deleted
     */
    public static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, List<UUID>> assets, Function<ProcessContext, List<Place>> places) {
        pb.step("Lock the asset library", HoldLock.<ProcessContext>shared(ctx ->
            ids(assets.apply(ctx)).isEmpty() && places.apply(ctx).isEmpty() ? null
                : QbAssets.lockName(ctx.request().actorId())));
        pb.step("Load the assets picked and the uses", QueryEntities.<ProcessContext>all(
            QueryEntities.query(QbAssets.DEFAULT_DATASET, ctx -> {
                List<Object> ids = ids(assets.apply(ctx));
                Object actor = ctx.request().actorId();
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("assetId", ids),
                        new QueryPredicate.In("sponsorId", actor == null ? List.of() : List.of(actor)))))
                    .limit(Math.max(1, ids.size())).build();
            }, PICKED),
            QueryEntities.query(QbAssets.USE_DATASET, ctx -> {
                Set<Object> kinds = new LinkedHashSet<>();
                Set<Object> ids = new LinkedHashSet<>();
                for (Place place : places.apply(ctx)) {
                    if (place.targetId() != null) {
                        kinds.add(place.targetKind());
                        ids.add(String.valueOf(place.targetId()));
                    }
                }
                // The unique key (target_kind, target_id): a place has at most one use.
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("targetKind", new ArrayList<>(kinds)),
                        new QueryPredicate.In("targetId", new ArrayList<>(ids)))))
                    .limit(Integer.MAX_VALUE).build();
            }, STORED)));
    }

    /**
     * Checks the picks: an asset not the caller's, or none, is not found (404, as {@link AssetProcesses} answers,
     * without saying whether it exists); one that does not suit its place is a violation
     * ({@code QB_ASSET_TARGET_KIND}), all of them at once. Run before the save writes anything.
     */
    public static void check(ProcessContext ctx, List<Pick> picks) {
        for (Pick pick : picks) {
            if (pick.assetId() == null) {
                continue;
            }
            EntityInstance asset = asset(ctx, pick.assetId());
            String kind = asset.get("kind");
            if (!AssetRules.suits(pick.targetKind(), pick.materialKind(), kind, asset.get("usage"))) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("target", pick.targetKind());
                params.put("kind", kind);
                ctx.reject(new Violation(pick.field(), AssetCodes.TARGET_KIND, "An asset of kind " + kind
                    + " cannot fill " + pick.targetKind(), params));
            }
        }
    }

    /**
     * Records that the place was filled from the asset now: its earlier use goes, a new one with the asset's present
     * revision comes (the place, the asset and the sponsor of a use never change). Nothing without an asset.
     */
    public static void record(ProcessContext ctx, String targetKind, Object targetId, UUID assetId) {
        if (assetId == null) {
            return;
        }
        Objects.requireNonNull(targetId, "targetId must not be null");
        EntityInstance asset = asset(ctx, assetId);
        forget(ctx, targetKind, List.of(targetId));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("assetId", String.valueOf(asset.id()));
        values.put("sponsorId", ctx.request().actorId());
        values.put("targetKind", targetKind);
        values.put("targetId", String.valueOf(targetId));
        values.put("assetRevision", asset.get("revision"));
        values.put("usedAt", ctx.opTime());
        ctx.changes().insert(QbAssets.USE, values);
    }

    /** Deletes the uses of these places (those loaded by {@link #load}); each once. */
    public static void forget(ProcessContext ctx, String targetKind, Collection<?> targetIds) {
        Set<String> ids = new HashSet<>();
        targetIds.stream().filter(Objects::nonNull).forEach(id -> ids.add(String.valueOf(id)));
        List<EntityInstance> stored = new ArrayList<>(AssetProcesses.list(ctx, STORED));
        List<EntityInstance> kept = new ArrayList<>();
        for (EntityInstance use : stored) {
            if (targetKind.equals(use.get("targetKind")) && ids.contains(String.valueOf((Object) use.get("targetId")))) {
                ctx.changes().delete(QbAssets.USE, use.id(), use.version());
            } else {
                kept.add(use);
            }
        }
        // A use deleted is not deleted twice (a place picked again after its part was forgotten).
        ctx.put(STORED, List.copyOf(kept));
    }

    private static EntityInstance asset(ProcessContext ctx, UUID assetId) {
        return AssetProcesses.list(ctx, PICKED).stream()
            .filter(row -> String.valueOf(row.id()).equals(assetId.toString())).findFirst()
            .orElseThrow(() -> new EntityNotFoundException(QbAssets.ASSET + " " + assetId));
    }

    private static List<Object> ids(List<UUID> assets) {
        Set<Object> ids = new LinkedHashSet<>();
        if (assets != null) {
            assets.stream().filter(Objects::nonNull).forEach(id -> ids.add(id.toString()));
        }
        return new ArrayList<>(ids);
    }

    private AssetUses() {
    }
}
