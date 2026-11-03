package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.certification.Certifications;
import com.jabiz.quizbuks.certification.RoundHolders;

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
    /** The prefix of the keys the certifications' holdings are read under (Q86). */
    static final String HOLDINGS = "play.prerequisite.";

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
                        // Q86: a certification required names no publication.
                        if (row.get("requiredPublicationId") != null) {
                            required.add(row.get("requiredPublicationId"));
                        }
                    }
                }
                return EntityQuery.builder().where(new QueryPredicate.In("publicationId", required))
                    .limit(QbPrerequisites.MAX_ROWS).build();
            }, ATTEMPTS));
    }

    /**
     * Q86 (D-Q86-5, D-Q86-6): the caller's holdings of the certifications a new start requires, read only when there
     * are any (two statements more outside the lock: records and rounds, then the attempts on those rounds).
     */
    public static <I, O> void loadHoldings(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Boolean> starting) {
        RoundHolders.loadHoldings(pb, ctx -> Boolean.TRUE.equals(starting.apply(ctx)) ? certifications(ctx) : List.of(),
            HOLDINGS);
    }

    /** The certifications the publication requires, from its prerequisite rows. */
    static List<Certifications.Certification> certifications(ProcessContext ctx) {
        List<Certifications.Certification> found = new ArrayList<>();
        for (EntityInstance row : PlaySteps.list(ctx, PREREQUISITES)) {
            Object key = row.get("certification");
            if (key != null) {
                Certifications.of(String.valueOf(key)).ifPresent(found::add);
            }
        }
        return found;
    }

    /** Rejects the start ({@code 422 QB_PREREQUISITE_NOT_MET}) when a prerequisite is not met, naming the first. */
    public static void refuse(ProcessContext ctx, String field) {
        List<PrerequisiteCheck.Prerequisite> unmet = unmet(ctx);
        if (!unmet.isEmpty()) {
            PrerequisiteCheck.Prerequisite first = unmet.get(0);
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            if (first.certification() != null) {
                params.put("certification", first.certification());
            } else {
                params.put("requiredPublicationId", String.valueOf(first.requiredPublicationId()));
            }
            params.put("requirement", first.requirement());
            params.put("unmet", String.valueOf(unmet.size()));
            ctx.reject(new Violation(field, PlayCodes.PREREQUISITE_NOT_MET, first.certification() != null
                ? "A certification this publication requires is not held"
                : "A publication this one requires has not been " + (QbPrerequisites.PASSED.equals(
                    first.requirement()) ? "passed" : "answered") + " yet", params));
        }
    }

    /** The prerequisites not met, from what was read. */
    public static List<PrerequisiteCheck.Prerequisite> unmet(ProcessContext ctx) {
        List<PrerequisiteCheck.Prerequisite> prerequisites = PlaySteps.list(ctx, PREREQUISITES).stream()
            .map(row -> new PrerequisiteCheck.Prerequisite(uuid(row.get("requiredPublicationId")),
                row.get("certification") == null ? null : String.valueOf((Object) row.get("certification")),
                row.get("requirement"))).toList();
        if (prerequisites.isEmpty()) {
            return List.of();
        }
        List<PrerequisiteCheck.Attempt> attempts = PlaySteps.list(ctx, ATTEMPTS).stream()
            .map(row -> new PrerequisiteCheck.Attempt(uuid(row.get("publicationId")), row.get("status"),
                row.get("passed"))).toList();
        return PrerequisiteCheck.unmet(prerequisites, attempts, RoundHolders.heldKeys(ctx, certifications(ctx), HOLDINGS));
    }

    private static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID u ? u : UUID.fromString(String.valueOf(value));
    }
}
