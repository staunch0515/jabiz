package com.jabiz.quizbuks.publishing;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.publishing.PublicationEditing.INPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUBLICATION;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUB_ID;

/**
 * A publication's prerequisites (Q76, docs/quizbuks/plans/Q76-prerequisites.md): {@code
 * QB_PUBLICATION_PREREQUISITES_SAVE} replaces a draft's (the wizard's step "Audience", beside the conditions), checked
 * by {@link PrerequisiteRules}; they are frozen with the publication once it is submitted, copied with it and deleted
 * with its draft. An invited publication may have them too (D-Q76-5).
 */
public final class PublicationPrerequisites {

    public static final String SAVE = "QB_PUBLICATION_PREREQUISITES_SAVE";

    static final String OWN = "prerequisites";
    static final String SPONSORS = "sponsorPrerequisites";
    static final String CHOSEN = "chosenPublications";
    static final String REQUIRED_BY = "requiredBy";

    /** @param requirement {@code PASSED} (passed it) or {@code ANSWERED} (finished it, passed or not) */
    public record PrerequisiteInput(@NotNull UUID publicationId,
        @NotNull @Pattern(regexp = "PASSED|ANSWERED") String requirement) {}

    /**
     * @param prerequisites the whole of the draft's prerequisites: those not given are removed, none means none
     * @param baseRevision  the revision the page edited; when given and no longer current, 422
     */
    public record PrerequisitesInput(@NotNull UUID publicationId, Long baseRevision,
        @NotNull @Size(max = 20) List<@Valid @NotNull PrerequisiteInput> prerequisites) {}

    public static ProcessDefinition<PrerequisitesInput, PublicationOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, PrerequisitesInput.class, PublicationOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Sets which of the sponsor's publications a taker first passes or answers.")
                    .permissions(QbPermissions.PUBLICATION_WRITE)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class));
                PublicationEditing.lockAndLoad(pb);
                // The draft's rows, the sponsor's whole chain (the cycle check) and the chosen publications, the
                // caller's own only (the sponsor's datasets): one statement.
                pb.step("Load the prerequisites, the sponsor's chain and the chosen publications",
                    QueryEntities.<ProcessContext>all(
                        own(),
                        QueryEntities.query(QbPrerequisites.SPONSOR_DATASET, ctx -> EntityQuery.builder()
                            .limit(QbPrerequisites.MAX_SPONSOR_ROWS).build(), SPONSORS),
                        QueryEntities.query(QbPublications.sponsorDataset(QbPublications.PUBLICATION), ctx -> {
                            List<Object> ids = new ArrayList<>();
                            ctx.get(INPUT, PrerequisitesInput.class).prerequisites()
                                .forEach(p -> ids.add(String.valueOf(p.publicationId())));
                            return EntityQuery.builder().where(new QueryPredicate.In("publicationId", ids))
                                .limit(QbPrerequisites.MAX_PREREQUISITES * 4).build();
                        }, CHOSEN)));
                pb.compute("Save the prerequisites", (metadata, ctx) -> save(ctx));
            });
    }

    /** The publication's own prerequisites: for a copy, a deletion and a save. */
    static QueryEntities.Metadata<ProcessContext> own() {
        return QueryEntities.query(QbPrerequisites.DATASET, ctx -> EntityQuery.builder()
            .where(new QueryPredicate.In("publicationId", PublicationEditing.ids(ctx.get(PUB_ID))))
            .limit(QbPrerequisites.MAX_PREREQUISITES * 4).build(), OWN);
    }

    /** The publication's prerequisites and those of others that require it (a deletion refuses then). */
    static <I, O> void loadForDeletion(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the prerequisites", QueryEntities.<ProcessContext>all(own(),
            QueryEntities.query(QbPrerequisites.DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("requiredPublicationId", PublicationEditing.ids(ctx.get(PUB_ID))))
                .limit(1).build(), REQUIRED_BY)));
    }

    /** The publication's prerequisites, for its copy. */
    static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the prerequisites", QueryEntities.<ProcessContext>of(QbPrerequisites.DATASET,
            ctx -> EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                PublicationEditing.ids(ctx.get(PUB_ID)))).limit(QbPrerequisites.MAX_PREREQUISITES * 4).build(), OWN));
    }

    static void save(ProcessContext ctx) {
        PrerequisitesInput input = ctx.get(INPUT, PrerequisitesInput.class);
        EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
        if (!PublicationEditing.draft(ctx, publication)
            || !PublicationEditing.current(ctx, publication, input.baseRevision())) {
            return;
        }
        UUID self = UUID.fromString(String.valueOf(publication.id()));
        Map<UUID, String> statuses = new HashMap<>();
        for (EntityInstance chosen : PublicationEditing.list(ctx, CHOSEN)) {
            statuses.put(UUID.fromString(String.valueOf(chosen.id())), String.valueOf((Object) chosen.get("status")));
        }
        Map<UUID, List<UUID>> chain = new HashMap<>();
        for (EntityInstance row : PublicationEditing.list(ctx, SPONSORS)) {
            UUID from = uuid(row.get("publicationId"));
            if (!from.equals(self)) {
                chain.computeIfAbsent(from, k -> new ArrayList<>()).add(uuid(row.get("requiredPublicationId")));
            }
        }
        List<PrerequisiteRules.Wanted> wanted = input.prerequisites().stream()
            .map(p -> new PrerequisiteRules.Wanted(p.publicationId(), p.requirement())).toList();
        PrerequisiteRules.problems(self, wanted, statuses, chain).forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return;
        }
        Map<UUID, String> rows = new LinkedHashMap<>();
        wanted.forEach(w -> rows.put(w.publicationId(), w.requirement()));
        replace(ctx, publication, rows);
        PublicationEditing.write(ctx, publication, Map.of());
    }

    /** Replaces the publication's rows by {@code wanted} (required publication to requirement), keeping the same. */
    static void replace(ProcessContext ctx, EntityInstance publication, Map<UUID, String> wanted) {
        Map<UUID, String> kept = new HashMap<>();
        for (EntityInstance row : PublicationEditing.list(ctx, OWN)) {
            UUID required = uuid(row.get("requiredPublicationId"));
            if (String.valueOf((Object) row.get("requirement")).equals(wanted.get(required))) {
                kept.put(required, wanted.get(required));
            } else {
                ctx.changes().delete(QbPrerequisites.PREREQUISITE, row.id(), row.version());
            }
        }
        wanted.forEach((required, requirement) -> {
            if (!kept.containsKey(required)) {
                ctx.changes().insert(QbPrerequisites.PREREQUISITE, values(publication.id(),
                    publication.get("publisherKind"), publication.get("publisherId"), required, requirement));
            }
        });
    }

    /** Copies the source's prerequisites to the copy (they still name the same required publications). */
    static void copy(ProcessContext ctx, Object copyId, Object publisherKind, Object publisherId) {
        for (EntityInstance row : PublicationEditing.list(ctx, OWN)) {
            ctx.changes().insert(QbPrerequisites.PREREQUISITE, values(copyId, publisherKind, publisherId,
                uuid(row.get("requiredPublicationId")), row.get("requirement")));
        }
    }

    /** Deletes the draft's prerequisites; false (and refused) when another publication requires the draft. */
    static boolean delete(ProcessContext ctx) {
        if (!PublicationEditing.list(ctx, REQUIRED_BY).isEmpty()) {
            ctx.reject(new com.jabiz.entity.Violation("publicationId", PublishingCodes.PREREQUISITE_IN_USE,
                "Another publication requires this one"));
            return false;
        }
        PublicationEditing.list(ctx, OWN).forEach(row -> ctx.changes().delete(QbPrerequisites.PREREQUISITE,
            row.id(), row.version()));
        return true;
    }

    private static Map<String, Object> values(Object publicationId, Object publisherKind, Object publisherId,
        UUID required, Object requirement) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("publicationId", publicationId);
        values.put("publisherKind", publisherKind);
        values.put("publisherId", publisherId);
        values.put("requiredPublicationId", required);
        values.put("requirement", requirement);
        return values;
    }

    private static UUID uuid(Object value) {
        return UUID.fromString(String.valueOf(value));
    }

    private PublicationPrerequisites() {}
}
