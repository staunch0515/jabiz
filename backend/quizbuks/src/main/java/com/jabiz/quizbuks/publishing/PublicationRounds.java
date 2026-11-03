package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.certification.CertificationCodes;
import com.jabiz.quizbuks.certification.CertificationProcesses;
import com.jabiz.quizbuks.certification.QbCertifications;
import com.jabiz.quizbuks.certification.QbSponsorCertifications;
import com.jabiz.quizbuks.certification.SponsorCertificationProcesses;
import com.jabiz.quizbuks.certification.SponsorCertifications;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.publishing.PublicationEditing.INPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUB_ID;

/**
 * A sponsor's publication as a round of the sponsor's own certification (Q88a,
 * docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md, D-Q88-3): {@value #SET} makes a draft a round of one
 * of the caller's active certifications, or no round, under the publication's lock as the prerequisites are saved;
 * once submitted, the round can only be closed ({@value #CLOSE}: passes from then on do not count). The row is the
 * same {@code QbCertificationRound} the operators' rounds are, its owner the sponsor's operator (so the operator closes
 * it too), its key {@code sp:<certificationId>}. A publication whose tiers give no reward can be submitted when it is a
 * round (D-Q88-5, {@link #isRound}). A draft deleted takes its round with it.
 */
public final class PublicationRounds {

    public static final String SET = "QB_SPONSOR_ROUND_SET";
    public static final String CLOSE = "QB_SPONSOR_ROUND_CLOSE";

    static final String ROUND = "publicationRound";
    static final String CERTIFICATION = "roundCertification";
    static final String ASSIGNMENT = "roundAssignment";
    static final String PARAMS = "roundParams";

    /** @param certificationId the caller's certification the draft is to be a round of; null: no round */
    public record SetInput(@NotNull UUID publicationId, UUID certificationId, Long baseRevision) {}

    public static ProcessDefinition<SetInput, PublicationOutput, ProcessContext> set() {
        return ProcessDefinition.define(SET, 1, SetInput.class, PublicationOutput.class, ProcessContext.class, pb -> {
            pb.description("Makes a draft publication a round of one of the sponsor's own certifications (passing it "
                    + "lets the taker hold the certification), or no round.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .step("Load the switch", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.SPONSOR_ROUNDS_ENABLED));
            PublicationEditing.lockAndLoad(pb);
            load(pb);
            // The certification named (the caller's own view: another sponsor's is not found) and the caller's
            // operator, whose the round is (none: headquarters'), in one statement.
            pb.step("Load the certification and the sponsor's operator", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbSponsorCertifications.SPONSOR_DATASET, ctx -> {
                        UUID id = ctx.get(INPUT, SetInput.class).certificationId();
                        return EntityQuery.builder().where(new QueryPredicate.In("certificationId",
                            id == null ? List.of() : List.of(id.toString()))).limit(1).build();
                    }, CERTIFICATION),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> EntityQuery
                        .builder().where(new QueryPredicate.Eq("userId", ctx.request().actorId())).limit(1).build(),
                        ASSIGNMENT)))
                .compute("Set the round", (metadata, ctx) -> set(ctx));
        });
    }

    public static ProcessDefinition<PublicationProcesses.PublicationRef, CertificationProcesses.RoundOutput,
        ProcessContext> close() {
        return ProcessDefinition.define(CLOSE, 1, PublicationProcesses.PublicationRef.class,
            CertificationProcesses.RoundOutput.class, ProcessContext.class, pb -> {
                pb.description("Closes the round the sponsor's publication is: passes from now on no longer count "
                        + "for the certification (those before still do).")
                    .permissions(QbPermissions.PUBLICATION_WRITE)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, CertificationProcesses.RoundOutput.class));
                PublicationEditing.lockAndLoad(pb);
                load(pb);
                pb.compute("Close the round", (metadata, ctx) -> close(ctx));
            });
    }

    /** Loads the round of the publication named by {@link PublicationEditing#PUB_ID}, if it is one. */
    static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the publication's round", QueryEntities.<ProcessContext>of(
            QbCertifications.defaultDataset(QbCertifications.ROUND), ctx -> {
                Object id = ctx.get(PUB_ID);
                return EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                    id == null ? List.of() : List.of(String.valueOf(id)))).limit(1).build();
            }, ROUND));
    }

    /**
     * Whether the publication is an open round of a certification (D-Q88-5), from what {@link #load} read: a round
     * closed (by a raise of the certification's version, or by hand) gives nothing, so it does not let the tiers pay
     * nothing.
     */
    static boolean isRound(ProcessContext ctx) {
        EntityInstance round = PublicationEditing.first(ctx, ROUND);
        return round != null && round.get("closedAt") == null;
    }

    /** The round's key, for the save's output; null for none. */
    static String certificationOf(ProcessContext ctx) {
        EntityInstance round = PublicationEditing.first(ctx, ROUND);
        return round == null ? null : round.get("certification");
    }

    static void set(ProcessContext ctx) {
        SetInput input = ctx.get(INPUT, SetInput.class);
        EntityInstance publication = PublicationEditing.publication(ctx);
        // Taking the round off a draft is allowed while the switch is off (what it did before stays undone).
        if (input.certificationId() != null
            && !SponsorCertificationProcesses.enabled(ctx.get(PARAMS, ParamValues.class))) {
            ctx.reject(SponsorCertificationProcesses.off());
            return;
        }
        if (!PublicationStatus.DRAFT.equals(publication.get("status"))) {
            ctx.reject(new Violation("publicationId", CertificationCodes.ROUND_AFTER_SUBMIT, "The round of a "
                + "submitted publication can only be closed", Map.of("status", String.valueOf((Object) publication
                .get("status")))));
            return;
        }
        if (!PublicationEditing.current(ctx, publication, input.baseRevision())) {
            return;
        }
        EntityInstance existing = PublicationEditing.first(ctx, ROUND);
        if (input.certificationId() == null) {
            if (existing == null) {
                unchanged(ctx, publication);
                return;
            }
            ctx.changes().delete(QbCertifications.ROUND, existing.id(), existing.version());
            PublicationEditing.write(ctx, publication, Map.of());
            return;
        }
        EntityInstance certification = PublicationEditing.first(ctx, CERTIFICATION);
        if (certification == null) {
            throw new EntityNotFoundException(QbSponsorCertifications.CERTIFICATION + " " + input.certificationId()
                + " not found");
        }
        if (!QbSponsorCertifications.ACTIVE.equals(certification.get("status"))) {
            ctx.reject(SponsorCertificationProcesses.retired(input.certificationId()));
            return;
        }
        String key = SponsorCertifications.keyOf(certification.id());
        int version = SponsorCertifications.number(certification.get("certificationVersion"));
        if (existing != null && key.equals(existing.get("certification"))
            && SponsorCertifications.number(existing.get("certificationVersion")) == version) {
            unchanged(ctx, publication);
            return;
        }
        if (existing != null) {
            // A draft's round has no record on it (nobody answers a draft): replaced, not closed.
            ctx.changes().delete(QbCertifications.ROUND, existing.id(), existing.version());
        }
        EntityInstance assignment = PublicationEditing.first(ctx, ASSIGNMENT);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("certification", key);
        values.put("certificationVersion", version);
        values.put("publicationId", String.valueOf(publication.id()));
        values.put("owner", assignment == null ? QbCertifications.DIRECT : assignment.get("operatorId"));
        values.put("addedBy", ctx.request().actorId());
        values.put("addedAt", ctx.opTime());
        ctx.changes().insert(QbCertifications.ROUND, values);
        PublicationEditing.write(ctx, publication, Map.of());
    }

    private static void unchanged(ProcessContext ctx, EntityInstance publication) {
        ctx.put(OUTPUT, new PublicationOutput(PublicationEditing.uuid(publication.id()), publication.get("status"),
            PublicationEditing.number(publication.get("revision")), null, null));
    }

    static void close(ProcessContext ctx) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        EntityInstance round = PublicationEditing.first(ctx, ROUND);
        if (round == null) {
            ctx.reject(new Violation("publicationId", CertificationCodes.NOT_A_ROUND, "The publication is no round "
                + "of a certification", Map.of()));
            return;
        }
        Object closedAt = round.get("closedAt");
        Instant closed = closedAt == null ? null : closedAt instanceof Instant i ? i
            : closedAt instanceof java.time.OffsetDateTime t ? t.toInstant() : Instant.parse(closedAt.toString());
        if (closed == null) {
            closed = ctx.opTime();
            ctx.changes().update(QbCertifications.ROUND, round.id(), round.version(), Map.of("closedAt", closed));
        }
        ctx.put(OUTPUT, new CertificationProcesses.RoundOutput(String.valueOf(round.id()),
            String.valueOf(publication.id()), round.get("certification"),
            SponsorCertifications.number(round.get("certificationVersion")), closed));
    }

    private PublicationRounds() {}
}
