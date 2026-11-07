package com.jabiz.quizbuks.asset;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.TextTranslations;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.publishing.PublicationLanguages;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.file.FileEntities;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The sponsor's asset library (F-C1, docs/quizbuks/plans/impl/F-C1.md):
 * <ul>
 *   <li>{@value #SAVE}: makes or changes one asset (its kind never changes);</li>
 *   <li>{@value #DELETE}: deletes one, with the record of where it was copied to (what was copied stays; its files
 *       go with the platform's sweep once nothing refers to them);</li>
 *   <li>{@value #COLLECT}: takes the candidates the sponsor ticked ({@code qb.sponsor.asset-candidates}) into the
 *       library, skipping those already in it or twice in the batch (files by id, texts by hash).</li>
 * </ul>
 * Each holds the library's lock ({@link QbAssets#lockName}) and reads all the sponsor's assets under it, so that the
 * count ({@code qb.assets.max-count}) and the duplicates are those of the moment. Only the caller's own assets are
 * found (another's is 404), and only files they uploaded go in.
 */
public final class AssetProcesses {

    public static final String SAVE = "QB_ASSET_SAVE";
    public static final String DELETE = "QB_ASSET_DELETE";
    public static final String COLLECT = "QB_ASSET_COLLECT";

    /**
     * @param assetId      the asset to change; none for a new one
     * @param kind         {@link AssetKind}; an asset keeps the one it was made with
     * @param translations the texts in other languages: {language: {field: text}}; none (null) keeps those stored
     * @param confirmed    translations ({@code <language>.<field>}) the sponsor says still fit a changed text
     * @param baseRevision the revision the caller edited; when given and no longer current, 422
     */
    public record SaveInput(String assetId, @NotBlank @Size(max = 20) String kind, String name, String description,
        @NotBlank @Size(max = 5) String language, Map<String, Map<String, String>> translations, List<String> confirmed,
        List<String> tags, UUID image, UUID pdf, UUID audio, String url, Integer durationSec, String body,
        String usage, String prizeMethod, String prizeDescription, String digitalDelivery, Integer digitalSendDays,
        Long baseRevision) {

        AssetRules.Asset asset() {
            return new AssetRules.Asset(kind, name, description, language, tags, image, pdf, audio, url, durationSec,
                body, usage, prizeMethod, prizeDescription, digitalDelivery, digitalSendDays);
        }
    }

    public record AssetOutput(String assetId, long revision) {}

    /** @param baseRevision the revision the caller saw; when given and no longer current, 422 */
    public record DeleteInput(@NotBlank String assetId, Long baseRevision) {}

    /** @param uses the records of where the asset was copied to, deleted with it */
    public record DeleteOutput(String assetId, int uses) {}

    /** One candidate to collect: what {@link SaveInput} gives for a new asset. */
    public record CollectItem(@NotBlank @Size(max = 20) String kind, String name, String description,
        @NotBlank @Size(max = 5) String language, Map<String, Map<String, String>> translations, List<String> tags,
        UUID image, UUID pdf, UUID audio, String url, Integer durationSec, String body, String usage,
        String prizeMethod, String prizeDescription, String digitalDelivery, Integer digitalSendDays) {

        AssetRules.Asset asset() {
            return new AssetRules.Asset(kind, name, description, language, tags, image, pdf, audio, url, durationSec,
                body, usage, prizeMethod, prizeDescription, digitalDelivery, digitalSendDays);
        }
    }

    public record CollectInput(@NotNull @Size(max = AssetRules.MAX_COLLECT) List<@NotNull @Valid CollectItem> items) {}

    /** @param index the item's place in the input (from 0) */
    public record Created(int index, String assetId) {}

    /**
     * An item not collected, being already in the library or earlier in the batch.
     *
     * @param assetId the asset of the library it is the same as; none when it is the same as an earlier item
     * @param sameAs  the earlier item of the batch it is the same as; none when it is in the library
     */
    public record Skipped(int index, String assetId, Integer sameAs) {}

    public record CollectOutput(List<Created> created, List<Skipped> skipped) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String SPONSOR = "assetSponsor";
    static final String PARAMS = "params";
    static final String ASSETS = "assets";
    static final String ASSET = "asset";
    static final String USES = "uses";
    static final String FILES = "files";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> TAG_LIST = new TypeReference<>() {};

    // ---- save ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SaveInput, AssetOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, AssetOutput.class, ProcessContext.class, pb -> {
            pb.description("Saves one of the sponsor's assets (an image, PDF, audio, video link, link, article, text "
                    + "or prize card) to copy into their quizzes and publications.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input))
                .outputMapper(ctx -> ctx.get(OUTPUT, AssetOutput.class));
            lockAndLoad(pb, ctx -> {
                SaveInput input = ctx.get(INPUT, SaveInput.class);
                return files(List.of(input.asset()));
            });
            pb.compute("Write the asset", (metadata, ctx) -> saved(ctx));
            pb.step("Save", SaveChanges.now());
        });
    }

    static void saved(ProcessContext ctx) {
        SaveInput input = ctx.get(INPUT, SaveInput.class);
        List<EntityInstance> assets = list(ctx, ASSETS);
        EntityInstance row = null;
        if (input.assetId() != null) {
            row = assets.stream().filter(a -> String.valueOf(a.id()).equals(input.assetId())).findFirst()
                .orElseThrow(() -> new EntityNotFoundException(QbAssets.ASSET + " " + input.assetId()));
            if (!AccountEditing.current(ctx, row, input.baseRevision(), AssetCodes.CHANGED)) {
                return;
            }
            if (!row.get("kind").equals(input.kind())) {
                ctx.reject(new Violation("kind", AssetCodes.KIND_FIXED, "The asset is of kind " + row.get("kind"),
                    Map.of("kind", String.valueOf((Object) row.get("kind")))));
                return;
            }
        } else {
            int max = limit(ctx);
            if (assets.size() >= max) {
                ctx.reject(limitReached(max));
            }
        }
        AssetRules.Asset asset = input.asset();
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        AssetRules.problems(asset, QbParams.limit(params, QbParams.ASSETS_MAX_TAGS),
            QbParams.limit(params, QbParams.ASSETS_TAG_LENGTH)).forEach(ctx::reject);
        Map<String, String> sources = AssetRules.sources(asset);
        Map<String, Map<String, PublicationLanguages.Text>> stored = PublicationLanguages.parse(
            row == null ? null : row.get("translations"));
        List<String> languages = new ArrayList<>(input.translations() == null ? stored.keySet()
            : input.translations().keySet());
        Map<String, Map<String, PublicationLanguages.Text>> translations = TextTranslations.merge(
            input.translations(), stored, sources, languages, input.confirmed());
        AssetRules.translationProblems(asset.language(), input.translations(), translations, sources,
            AssetRules.maxLengths(asset)).forEach(ctx::reject);
        ownFiles(ctx, asset, row).forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return;
        }
        Object id = write(ctx, row, asset, translations);
        long revision = row == null ? 1 : AccountEditing.number(row.get("revision")) + 1;
        ctx.put(OUTPUT, new AssetOutput(String.valueOf(id), revision));
    }

    // ---- delete ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<DeleteInput, DeleteOutput, ProcessContext> delete() {
        return ProcessDefinition.define(DELETE, 1, DeleteInput.class, DeleteOutput.class, ProcessContext.class, pb -> {
            pb.description("Deletes one of the sponsor's assets and the record of where it was copied to; what was "
                    + "copied stays as it is.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input))
                .outputMapper(ctx -> ctx.get(OUTPUT, DeleteOutput.class));
            pb.step("Lock the library", HoldLock.<ProcessContext>exclusive(ctx -> QbAssets.lockName(sponsor(ctx))));
            CallerChecks.refuseBanned(pb);
            pb.step("Load the asset and its uses", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbAssets.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("assetId", List.of(ctx.get(INPUT, DeleteInput.class).assetId())),
                        new QueryPredicate.In("sponsorId", List.of(sponsor(ctx))))))
                    .limit(1).build(), ASSET),
                QueryEntities.query(QbAssets.USE_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("assetId", List.of(ctx.get(INPUT, DeleteInput.class).assetId())),
                        new QueryPredicate.In("sponsorId", List.of(sponsor(ctx))))))
                    .limit(Integer.MAX_VALUE).build(), USES)));
            pb.compute("Delete", (metadata, ctx) -> {
                DeleteInput input = ctx.get(INPUT, DeleteInput.class);
                List<EntityInstance> found = list(ctx, ASSET);
                if (found.isEmpty()) {
                    // Another's asset is not found, as it is in none of the caller's lists.
                    throw new EntityNotFoundException(QbAssets.ASSET + " " + input.assetId());
                }
                EntityInstance asset = found.getFirst();
                if (!AccountEditing.current(ctx, asset, input.baseRevision(), AssetCodes.CHANGED)) {
                    return;
                }
                List<EntityInstance> uses = list(ctx, USES);
                for (EntityInstance use : uses) {
                    ctx.changes().delete(QbAssets.USE, use.id(), use.version());
                }
                ctx.changes().delete(QbAssets.ASSET, asset.id(), asset.version());
                ctx.put(OUTPUT, new DeleteOutput(String.valueOf(asset.id()), uses.size()));
            });
            pb.step("Save", SaveChanges.now());
        });
    }

    // ---- collect ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<CollectInput, CollectOutput, ProcessContext> collect() {
        return ProcessDefinition.define(COLLECT, 1, CollectInput.class, CollectOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Takes the sponsor's existing content they ticked into their asset library, skipping "
                        + "what is already in it.")
                    .permissions(QbPermissions.CONTENT_WRITE)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> start(start, input))
                    .outputMapper(ctx -> ctx.get(OUTPUT, CollectOutput.class));
                lockAndLoad(pb, ctx -> files(ctx.get(INPUT, CollectInput.class).items().stream()
                    .map(CollectItem::asset).toList()));
                pb.compute("Write the assets", (metadata, ctx) -> collected(ctx));
                pb.step("Save", SaveChanges.now());
            });
    }

    static void collected(ProcessContext ctx) {
        List<CollectItem> items = ctx.get(INPUT, CollectInput.class).items();
        List<EntityInstance> assets = list(ctx, ASSETS);
        Map<String, String> library = new HashMap<>();
        for (EntityInstance row : assets) {
            String key = AssetRules.dedupeKey(row.get("kind"), row.get("usage"), row.get("prizeMethod"),
                file(row), row.get("contentHash"));
            if (key != null) {
                library.putIfAbsent(key, String.valueOf(row.id()));
            }
        }
        Map<String, Integer> batch = new HashMap<>();
        List<Integer> taken = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String key = AssetRules.dedupeKey(items.get(i).asset());
            if (key != null && library.containsKey(key)) {
                skipped.add(new Skipped(i, library.get(key), null));
            } else if (key != null && batch.containsKey(key)) {
                skipped.add(new Skipped(i, null, batch.get(key)));
            } else {
                if (key != null) {
                    batch.put(key, i);
                }
                taken.add(i);
            }
        }
        int max = limit(ctx);
        if (assets.size() + taken.size() > max) {
            // The whole batch or nothing: the sponsor chooses what to leave out.
            ctx.reject(limitReached(max));
            return;
        }
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        int maxTags = QbParams.limit(params, QbParams.ASSETS_MAX_TAGS);
        int tagLength = QbParams.limit(params, QbParams.ASSETS_TAG_LENGTH);
        Map<Integer, Map<String, Map<String, PublicationLanguages.Text>>> translations = new HashMap<>();
        for (int i : taken) {
            CollectItem item = items.get(i);
            AssetRules.Asset asset = item.asset();
            int index = i;
            List<Violation> problems = new ArrayList<>(AssetRules.problems(asset, maxTags, tagLength));
            Map<String, String> sources = AssetRules.sources(asset);
            List<String> languages = item.translations() == null ? List.of()
                : new ArrayList<>(item.translations().keySet());
            Map<String, Map<String, PublicationLanguages.Text>> merged = TextTranslations.merge(item.translations(),
                Map.of(), sources, languages, null);
            problems.addAll(AssetRules.translationProblems(asset.language(), item.translations(), merged, sources,
                AssetRules.maxLengths(asset)));
            problems.addAll(ownFiles(ctx, asset, null));
            problems.forEach(problem -> ctx.reject(AssetRules.within("items", index, problem)));
            translations.put(i, merged);
        }
        if (ctx.hasViolations()) {
            return;
        }
        List<Created> created = new ArrayList<>();
        for (int i : taken) {
            Object id = write(ctx, null, items.get(i).asset(), translations.get(i));
            created.add(new Created(i, String.valueOf(id)));
        }
        ctx.put(OUTPUT, new CollectOutput(List.copyOf(created), List.copyOf(skipped)));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static ProcessContext start(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        String actor = start.request().actorId();
        if (actor != null) {
            ctx.put(SPONSOR, actor);
        }
        return ctx;
    }

    /** The sponsor whose library it is: the caller, never the input. */
    static String sponsor(ProcessContext ctx) {
        return ctx.contains(SPONSOR) ? ctx.get(SPONSOR, String.class) : null;
    }

    /**
     * Takes the library's lock, refuses a banned caller, and reads under the lock the parameters, all the sponsor's
     * assets and the platform's records of the files the input names (to know who uploaded them).
     */
    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        java.util.function.Function<ProcessContext, List<UUID>> files) {
        pb.step("Lock the library", HoldLock.<ProcessContext>exclusive(ctx -> QbAssets.lockName(sponsor(ctx))));
        CallerChecks.refuseBanned(pb);
        pb.step("Load the parameters", LoadParams.<ProcessContext>of(com.jabiz.process.ProcessContext::opTime, PARAMS,
            QbParams.ASSETS_MAX_COUNT, QbParams.ASSETS_MAX_TAGS, QbParams.ASSETS_TAG_LENGTH));
        pb.step("Load the assets and the files", QueryEntities.<ProcessContext>all(
            QueryEntities.query(QbAssets.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("sponsorId", sponsor(ctx) == null ? List.of() : List.of(sponsor(ctx))))
                .limit(Integer.MAX_VALUE).build(), ASSETS),
            QueryEntities.query(FileEntities.DATASET, ctx -> {
                List<Object> ids = new ArrayList<>(files.apply(ctx));
                return EntityQuery.builder().where(new QueryPredicate.In(FileEntities.FILE_ID, ids))
                    .limit(Math.max(1, ids.size())).build();
            }, FILES)));
    }

    /** The distinct files the assets name. */
    private static List<UUID> files(List<AssetRules.Asset> assets) {
        Set<UUID> files = new LinkedHashSet<>();
        for (AssetRules.Asset asset : assets) {
            for (UUID file : new UUID[] {asset.image(), asset.pdf(), asset.audio()}) {
                if (file != null) {
                    files.add(file);
                }
            }
        }
        return List.copyOf(files);
    }

    /**
     * Refuses a file newly put into the asset that the sponsor did not upload (as {@code QuizEditing.requireOwnFile}):
     * nobody keeps another's file by knowing its key. A file the asset already holds stays; a file the platform does
     * not know is left to the platform's check of the file field.
     */
    private static List<Violation> ownFiles(ProcessContext ctx, AssetRules.Asset asset, EntityInstance row) {
        List<Violation> problems = new ArrayList<>();
        Map<String, UUID> given = new LinkedHashMap<>();
        given.put("image", asset.image());
        given.put("pdf", asset.pdf());
        given.put("audio", asset.audio());
        given.forEach((field, file) -> {
            Object stored = row == null ? null : row.get(field);
            if (file == null || stored != null && String.valueOf(stored).equals(file.toString())) {
                return;
            }
            EntityInstance record = list(ctx, FILES).stream()
                .filter(f -> String.valueOf((Object) f.get(FileEntities.FILE_ID)).equals(file.toString())).findFirst()
                .orElse(null);
            if (record != null && !String.valueOf((Object) record.get(FileEntities.UPLOADED_BY)).equals(sponsor(ctx))) {
                problems.add(new Violation(field, AssetCodes.FILE_NOT_OWN, "File " + file + " was not uploaded by you",
                    Map.of("fileId", file.toString())));
            }
        });
        return problems;
    }

    /** Inserts a new asset, or updates {@code row} with the next revision; returns its id. */
    private static Object write(ProcessContext ctx, EntityInstance row, AssetRules.Asset asset,
        Map<String, Map<String, PublicationLanguages.Text>> translations) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", asset.name().strip());
        values.put("description", AssetRules.text(asset.description()));
        values.put("language", asset.language());
        values.put("translations", PublicationLanguages.toJson(translations));
        List<String> tags = AssetRules.tags(asset.tags());
        values.put("tags", tags.isEmpty() ? null : JSON.writeValueAsString(tags));
        values.put("image", asset.image());
        values.put("pdf", asset.pdf());
        values.put("audio", asset.audio());
        values.put("url", AssetRules.text(asset.url()));
        values.put("durationSec", asset.durationSec() == null ? null : BigDecimal.valueOf(asset.durationSec()));
        values.put("body", AssetRules.text(asset.body()));
        values.put("usage", AssetRules.text(asset.usage()));
        values.put("prizeMethod", AssetRules.text(asset.prizeMethod()));
        values.put("prizeDescription", AssetRules.text(asset.prizeDescription()));
        values.put("digitalDelivery", AssetRules.text(asset.digitalDelivery()));
        values.put("digitalSendDays", asset.digitalSendDays() == null ? null
            : BigDecimal.valueOf(asset.digitalSendDays()));
        values.put("contentHash", AssetRules.contentHash(asset));
        values.put("editedAt", ctx.opTime());
        if (row == null) {
            values.put("sponsorId", sponsor(ctx));
            values.put("kind", asset.kind());
            values.put("revision", BigDecimal.ONE);
            values.put("createdAt", ctx.opTime());
            return ctx.changes().insert(QbAssets.ASSET, values);
        }
        values.put("revision", BigDecimal.valueOf(AccountEditing.number(row.get("revision")) + 1));
        ctx.changes().update(QbAssets.ASSET, row.id(), row.version(), values);
        return row.id();
    }

    /** The tags an asset holds (its column's JSON list); none for none. */
    public static List<String> tagsOf(Object column) {
        return column == null || column.toString().isBlank() ? List.of()
            : JSON.readValue(column.toString(), TAG_LIST);
    }

    private static Object file(EntityInstance row) {
        Object image = row.get("image");
        Object pdf = row.get("pdf");
        return image != null ? image : pdf != null ? pdf : row.get("audio");
    }

    private static int limit(ProcessContext ctx) {
        return QbParams.limit(ctx.get(PARAMS, ParamValues.class), QbParams.ASSETS_MAX_COUNT);
    }

    private static Violation limitReached(int max) {
        return new Violation(null, AssetCodes.LIMIT, "A sponsor keeps at most " + max + " assets", Map.of("max", max));
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
        return found == null ? List.of() : found;
    }

    private AssetProcesses() {
    }
}
