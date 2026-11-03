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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.quizbuks.publishing.PublicationEditing.INPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUBLICATION;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUB_ID;

/**
 * A publication's prerequisites (Q76, docs/quizbuks/plans/Q76-prerequisites.md): {@code
 * QB_PUBLICATION_PREREQUISITES_SAVE} replaces a draft's (the wizard's step "Audience"), as the matching conditions are
 * replaced (Q47): under the publication's lock, at the revision the page edited, only while it is a draft. Frozen with
 * the publication once submitted; the copy carries them.
 */
public final class PublicationPrerequisites {

    public static final String SAVE = "QB_PUBLICATION_PREREQUISITES_SAVE";

    static final String PREREQUISITES = "prerequisites";
    static final String SPONSOR_EDGES = "sponsorEdges";
    static final String TARGETS = "prerequisiteTargets";

    /**
     * One prerequisite: the publication required and what the taker must have done on it, or (Q86, D-Q86-5) a
     * certification the taker must hold (its requirement is then stored as PASSED whatever is given).
     */
    public record PrerequisiteInput(UUID requiredPublicationId, @Size(max = 40) String certification,
        @NotNull @Pattern(regexp = "PASSED|ANSWERED") String requirement) {
        public PrerequisiteInput(UUID requiredPublicationId, String requirement) {
            this(requiredPublicationId, null, requirement);
        }

        /** The key a row is kept by: the publication, or the certification. */
        String key() {
            return certification != null ? "c:" + certification : "p:" + requiredPublicationId;
        }

        String storedRequirement() {
            return certification != null ? QbPrerequisites.PASSED : requirement;
        }
    }

    /**
     * @param prerequisites the whole of the draft's prerequisites: those not given are removed, none means no
     *                      requirement
     * @param baseRevision  the revision the page edited; when given and no longer current, 422
     */
    public record PrerequisitesInput(@NotNull UUID publicationId, Long baseRevision,
        @NotNull @Size(max = QbPrerequisites.MAX_PREREQUISITES) List<@Valid @NotNull PrerequisiteInput> prerequisites) {}

    public static ProcessDefinition<PrerequisitesInput, PublicationOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, PrerequisitesInput.class, PublicationOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Sets which of the sponsor's publications a taker must first pass or answer.")
                    .permissions(QbPermissions.PUBLICATION_WRITE)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class));
                PublicationEditing.lockAndLoad(pb);
                load(pb);
                // The sponsor's whole chain (the cycle check, D-Q76-4) and the publications wanted, through the sponsor's
                // own view (one not found is not theirs), in one statement.
                pb.step("Load the sponsor's chain and the publications wanted", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbPrerequisites.DEFAULT_DATASET, ctx -> {
                        EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
                        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("publisherKind", publication == null ? "" : publication.get("publisherKind")),
                                new QueryPredicate.Eq("publisherId", publication == null ? ""
                                    : String.valueOf((Object) publication.get("publisherId"))))))
                            .limit(QbPrerequisites.MAX_ROWS).build();
                    }, SPONSOR_EDGES),
                    QueryEntities.query(QbPublications.sponsorDataset(QbPublications.PUBLICATION), ctx -> {
                        List<Object> ids = new ArrayList<>();
                        for (PrerequisiteInput p : ctx.get(INPUT, PrerequisitesInput.class).prerequisites()) {
                            if (p.requiredPublicationId() != null) {
                                ids.add(p.requiredPublicationId());
                            }
                        }
                        return EntityQuery.builder().where(new QueryPredicate.In("publicationId", ids))
                            .limit(QbPrerequisites.MAX_PREREQUISITES + 1).build();
                    }, TARGETS)));
                pb.compute("Save the prerequisites", (metadata, ctx) -> save(ctx));
            });
    }

    /** The prerequisites the publication has now (to replace, delete or copy them). */
    static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the prerequisites", QueryEntities.<ProcessContext>of(QbPrerequisites.DEFAULT_DATASET,
            ctx -> EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                PublicationEditing.ids(ctx.get(PUB_ID)))).limit(QbPrerequisites.MAX_ROWS).build(), PREREQUISITES));
    }

    static void save(ProcessContext ctx) {
        PrerequisitesInput input = ctx.get(INPUT, PrerequisitesInput.class);
        EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
        if (!PublicationEditing.draft(ctx, publication)
            || !PublicationEditing.current(ctx, publication, input.baseRevision())) {
            return;
        }
        List<PrerequisiteRules.Wanted> wanted = input.prerequisites().stream()
            .map(p -> new PrerequisiteRules.Wanted(p.requiredPublicationId(), p.certification(), p.storedRequirement()))
            .toList();
        Map<UUID, PrerequisiteRules.Target> targets = new HashMap<>();
        for (EntityInstance target : PublicationEditing.list(ctx, TARGETS)) {
            targets.put(PublicationEditing.uuid(target.id()), new PrerequisiteRules.Target(
                PublicationEditing.uuid(target.id()), target.get("status")));
        }
        List<PrerequisiteRules.Edge> edges = PublicationEditing.list(ctx, SPONSOR_EDGES).stream()
            .map(row -> new PrerequisiteRules.Edge(PublicationEditing.uuid(row.get("publicationId")),
                PublicationEditing.uuid(row.get("requiredPublicationId")))).toList();
        PrerequisiteRules.problems(PublicationEditing.uuid(publication.id()), wanted, targets, edges)
            .forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, PrerequisiteInput> rows = new LinkedHashMap<>();
        for (PrerequisiteInput p : input.prerequisites()) {
            rows.put(p.key(), p);
        }
        replace(ctx, publication, rows);
        PublicationEditing.write(ctx, publication, Map.of());
    }

    /** Replaces the publication's rows by {@code wanted} (by key: publication or certification), leaving those that stay. */
    static void replace(ProcessContext ctx, EntityInstance publication, Map<String, PrerequisiteInput> wanted) {
        Set<String> kept = new HashSet<>();
        for (EntityInstance row : PublicationEditing.list(ctx, PREREQUISITES)) {
            String key = keyOf(row);
            PrerequisiteInput want = wanted.get(key);
            if (want != null && want.storedRequirement().equals(row.get("requirement"))) {
                kept.add(key);
            } else {
                ctx.changes().delete(QbPrerequisites.PREREQUISITE, row.id(), row.version());
            }
        }
        wanted.forEach((key, want) -> {
            if (!kept.contains(key)) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("publicationId", publication.id());
                values.put("publisherKind", publication.get("publisherKind"));
                values.put("publisherId", publication.get("publisherId"));
                values.put("requiredPublicationId", want.requiredPublicationId());
                values.put("certification", want.certification());
                values.put("requirement", want.storedRequirement());
                ctx.changes().insert(QbPrerequisites.PREREQUISITE, values);
            }
        });
    }

    static String keyOf(EntityInstance row) {
        Object certification = row.get("certification");
        return certification != null ? "c:" + certification : "p:" + PublicationEditing.uuid(row.get("requiredPublicationId"));
    }

    /** The values of a stored row for a copy of the publication (Q76: the same publications or certifications are required). */
    static Map<String, Object> copyOf(EntityInstance row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("requiredPublicationId", row.get("requiredPublicationId"));
        values.put("certification", row.get("certification"));
        values.put("requirement", row.get("requirement"));
        return values;
    }

    private PublicationPrerequisites() {}
}
