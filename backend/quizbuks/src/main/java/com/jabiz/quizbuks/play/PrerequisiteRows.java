package com.jabiz.quizbuks.play;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.publishing.QbPrerequisites;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.entity.Violation;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The rows the start reads for {@link PrerequisiteCheck} (Q76, D-Q76-7): the publication's prerequisites in the
 * statement that reads the card (the publication is known), then — only when there are any — the caller's attempts on
 * the required publications, one statement more outside the lock (an ordinary publication's query is empty and sends
 * nothing, as {@code RoundHolders}). The statement budget of an ordinary start is unchanged.
 */
public final class PrerequisiteRows {

    static final String PREREQUISITES = "play.prerequisites";
    static final String ATTEMPTS = "play.prerequisiteAttempts";

    private PrerequisiteRows() {}

    /** The publication's prerequisites, through the default dataset; none for a null publication. */
    public static <C extends ProcessContext> QueryEntities.Metadata<C> of(Function<C, Object> publicationId,
        String targetKey) {
        return QueryEntities.query(QbPrerequisites.DEFAULT_DATASET, ctx -> {
            Object id = publicationId.apply(ctx);
            return EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                id == null ? List.of() : List.of(id))).limit(QbPrerequisites.MAX_ROWS).build();
        }, targetKey);
    }

    /**
     * The caller's own attempts on the required publications (the {@code taker:} view holds theirs only), read only
     * when a new start has prerequisites to meet.
     */
    public static <I, O> void loadAttempts(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Boolean> starting) {
        pb.step("Load the caller's attempts on the prerequisites", QueryEntities.<ProcessContext>of(
            QbPlay.TAKER_ATTEMPTS, ctx -> {
                List<Object> required = new ArrayList<>();
                if (Boolean.TRUE.equals(starting.apply(ctx))) {
                    for (EntityInstance row : PlaySteps.list(ctx, PREREQUISITES)) {
                        required.add(row.get("requiredPublicationId"));
                    }
                }
                return EntityQuery.builder().where(new QueryPredicate.In("publicationId", required))
                    .limit(QbPrerequisites.MAX_ROWS).build();
            }, ATTEMPTS));
    }

    /** Rejects the start ({@code 422 QB_PREREQUISITE_NOT_MET}) when a prerequisite is not met, naming the first. */
    public static void refuse(ProcessContext ctx, String field) {
        List<PrerequisiteCheck.Prerequisite> unmet = unmet(ctx);
        if (!unmet.isEmpty()) {
            ctx.reject(new Violation(field, PlayCodes.PREREQUISITE_NOT_MET,
                "A publication this one requires has not been " + (QbPrerequisites.PASSED.equals(
                    unmet.get(0).requirement()) ? "passed" : "answered") + " yet",
                Map.of("requiredPublicationId", String.valueOf(unmet.get(0).requiredPublicationId()),
                    "requirement", unmet.get(0).requirement(), "unmet", String.valueOf(unmet.size()))));
        }
    }

    /** The prerequisites not met, from what was read. */
    public static List<PrerequisiteCheck.Prerequisite> unmet(ProcessContext ctx) {
        List<PrerequisiteCheck.Prerequisite> prerequisites = PlaySteps.list(ctx, PREREQUISITES).stream()
            .map(row -> new PrerequisiteCheck.Prerequisite(uuid(row.get("requiredPublicationId")),
                row.get("requirement"))).toList();
        if (prerequisites.isEmpty()) {
            return List.of();
        }
        List<PrerequisiteCheck.Attempt> attempts = PlaySteps.list(ctx, ATTEMPTS).stream()
            .map(row -> new PrerequisiteCheck.Attempt(uuid(row.get("publicationId")), row.get("status"),
                row.get("passed"))).toList();
        return PrerequisiteCheck.unmet(prerequisites, attempts);
    }

    private static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID u ? u : UUID.fromString(String.valueOf(value));
    }
}
