package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.audience.AudienceCodes;
import com.jabiz.quizbuks.audience.AudienceCount;
import com.jabiz.quizbuks.audience.AudienceCriteria;
import com.jabiz.quizbuks.audience.AudienceCriterion;
import com.jabiz.quizbuks.audience.QbAudience;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
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
 * A publication's matching conditions (docs/quizbuks/plans/Q47-audience.md): {@code QB_PUBLICATION_AUDIENCE_SAVE}
 * replaces a draft's conditions (the wizard's step "Audience"), and {@code QB_AUDIENCE_COUNT} tells the sponsor how
 * many takers its conditions and regions reach, rounded ({@link AudienceCount}). The conditions are frozen with the
 * publication once it is submitted (only a draft is edited, D-Q4-2) and copied with it.
 */
public final class PublicationAudience {

    public static final String SAVE = "QB_PUBLICATION_AUDIENCE_SAVE";
    public static final String COUNT = "QB_AUDIENCE_COUNT";
    /** The exact count, run by {@link #COUNT} only (its permission is no role's, D-Q47-5). */
    public static final String COUNT_TEMPLATE = "qb.system.audience-count";

    static final String CRITERIA = "criteria";
    static final String COUNTED = "counted";

    /**
     * @param criteria     the whole of the draft's conditions: those not given are removed, none means everybody
     * @param baseRevision the revision the page edited; when given and no longer current, 422
     */
    public record AudienceInput(@NotNull UUID publicationId, Long baseRevision,
        @NotNull @Size(max = 20) List<AudienceCriteria.@Valid @NotNull ConditionInput> criteria) {}

    public record CountInput(@NotNull UUID publicationId) {}

    public static ProcessDefinition<AudienceInput, PublicationOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, AudienceInput.class, PublicationOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Sets a draft publication's matching conditions on the takers' profiles.")
                    .permissions(QbPermissions.PUBLICATION_WRITE)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class));
                PublicationEditing.lockAndLoad(pb);
                loadCriteria(pb);
                pb.compute("Save the conditions", (metadata, ctx) -> save(ctx));
            });
    }

    public static ProcessDefinition<CountInput, AudienceCount, ProcessContext> count() {
        return ProcessDefinition.define(COUNT, 1, CountInput.class, AudienceCount.class, ProcessContext.class,
            pb -> pb.description("Counts the takers a publication's regions and conditions reach, rounded.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, AudienceCount.class))
                // The caller's own publication only: 404 for anybody else's.
                .step("Load the publication", LoadEntity.by(QbPublications.sponsorDataset(QbPublications.PUBLICATION),
                    PUB_ID, PUBLICATION))
                // The template's permission is no role's: a process runs it, so the exact number never leaves here.
                .step("Count the takers", RunTemplate.<ProcessContext>of(COUNT_TEMPLATE, ctx -> {
                    EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
                    Map<String, Object> params = new HashMap<>();
                    params.put("publicationId", String.valueOf(ctx.get(PUB_ID)));
                    params.put("regions", publication == null ? null : publication.get("regions"));
                    params.put("publisherId", publication == null ? null : publication.get("publisherId"));
                    params.put("at", ctx.opTime());
                    return params;
                }, COUNTED))
                .compute("Round the count", (metadata, ctx) -> rounded(ctx)));
    }

    /** The conditions the publication has now, to replace them. */
    static <I, O> void loadCriteria(com.jabiz.process.ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the conditions", QueryEntities.<ProcessContext>of(QbAudience.CRITERION_DATASET,
            ctx -> EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                PublicationEditing.ids(ctx.get(PUB_ID)))).limit(QbAudience.MAX_ROWS).build(), CRITERIA));
    }

    static void save(ProcessContext ctx) {
        AudienceInput input = ctx.get(INPUT, AudienceInput.class);
        EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
        if (!PublicationEditing.draft(ctx, publication)
            || !PublicationEditing.current(ctx, publication, input.baseRevision())) {
            return;
        }
        if (Audience.INVITED.equals(publication.get("audience")) && !input.criteria().isEmpty()) {
            ctx.reject(new Violation("criteria", AudienceCodes.INVITED, "An invited publication takes its list, not "
                + "conditions", Map.of()));
            return;
        }
        AudienceCriteria.conditionProblems(input.criteria()).forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return;
        }
        replace(ctx, publication, rowsOf(input.criteria()));
        PublicationEditing.write(ctx, publication, Map.of());
    }

    /** The rows the conditions make, by the table's key (an option, or the criterion's one range). */
    static Map<String, Map<String, Object>> rowsOf(List<AudienceCriteria.ConditionInput> criteria) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (AudienceCriteria.ConditionInput condition : criteria) {
            AudienceCriterion criterion = AudienceCriteria.find(condition.key()).orElseThrow();
            if (criterion.kind() == AudienceCriterion.Kind.SET) {
                for (String option : new HashSet<>(condition.options())) {
                    rows.put(condition.key() + "|" + option, row(condition.key(), option, null, null, null));
                }
            } else {
                rows.put(condition.key() + "|" + condition.min() + "-" + condition.max(), row(condition.key(), null,
                    condition.min(), condition.max(), criterion.measure().name()));
            }
        }
        return rows;
    }

    /** Replaces the publication's condition rows by {@code wanted}, leaving those that stay. */
    static void replace(ProcessContext ctx, EntityInstance publication, Map<String, Map<String, Object>> wanted) {
        Set<String> kept = new HashSet<>();
        for (EntityInstance row : PublicationEditing.list(ctx, CRITERIA)) {
            String key = keyOf(row);
            if (wanted.containsKey(key)) {
                kept.add(key);
            } else {
                ctx.changes().delete(QbAudience.CRITERION, row.id(), row.version());
            }
        }
        wanted.forEach((key, values) -> {
            if (!kept.contains(key)) {
                Map<String, Object> next = new LinkedHashMap<>(values);
                next.put("publicationId", publication.id());
                next.put("publisherKind", publication.get("publisherKind"));
                next.put("publisherId", publication.get("publisherId"));
                ctx.changes().insert(QbAudience.CRITERION, next);
            }
        });
    }

    private static Map<String, Object> row(String key, String option, Integer min, Integer max, String measure) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("criterionKey", key);
        values.put("valueCode", option);
        values.put("minValue", min == null ? null : BigDecimal.valueOf(min));
        values.put("maxValue", max == null ? null : BigDecimal.valueOf(max));
        values.put("measure", measure);
        return values;
    }

    static String keyOf(EntityInstance row) {
        Object code = row.get("valueCode");
        return row.get("criterionKey") + "|" + (code != null ? code
            : integer(row.get("minValue")) + "-" + integer(row.get("maxValue")));
    }

    /** The values of a stored condition row, for a copy of the publication (Q47: conditions travel with it). */
    static Map<String, Object> copyOf(EntityInstance row) {
        return row(row.get("criterionKey"), row.get("valueCode"), integer(row.get("minValue")),
            integer(row.get("maxValue")), row.get("measure"));
    }

    private static Integer integer(Object value) {
        return value instanceof BigDecimal d ? d.intValueExact() : value instanceof Number n ? n.intValue() : null;
    }

    static void rounded(ProcessContext ctx) {
        EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
        if (Audience.INVITED.equals(publication.get("audience"))) {
            ctx.reject(new Violation("publicationId", AudienceCodes.INVITED, "An invited publication reaches its "
                + "list, not an audience", Map.of()));
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(COUNTED);
        Object takers = rows == null || rows.isEmpty() ? null : rows.getFirst().get("takers");
        long exact = takers instanceof Number n ? n.longValue() : 0L;
        ctx.put(OUTPUT, AudienceCount.of(exact));
    }

    private PublicationAudience() {}
}
