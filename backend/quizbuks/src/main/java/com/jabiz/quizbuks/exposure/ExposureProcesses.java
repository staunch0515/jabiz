package com.jabiz.quizbuks.exposure;

import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationRules;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.reward.QbRewards;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@value #REPORT} (F-S13, docs/quizbuks/plans/impl/F-S13.md): the App's batch of reports about the videos of
 * publications using another's shared video ({@link ExposureRules}): a push opened in the vertical mode, an exposure
 * watched long enough on its surface, the taker going on to answer. Each publication's row of the caller's is made or
 * changed once per call; what counts is decided by {@link ExposureRules#decide} on the card as it is now (an ended
 * publication no longer counts) and the caller's exposures of the UTC day (the daily cap).
 *
 * <p>Anyone who answers ({@code qb.play}) reports, with a verified address (platform: signed in and verified). It takes
 * the caller's own lock ({@link QbExposures#lockName}) only, never a publication's, start or wallet lock: counting
 * stays out of the way of answering. Rate-limited in {@code jabiz.process.rate-limits}.
 */
public final class ExposureProcesses {

    public static final String REPORT = "QB_EXPOSURE_REPORT";

    /** The publication is none the App lists (none, or a shop's). */
    public static final String UNKNOWN = "UNKNOWN";

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String PARAMS = "params";
    static final String CARDS = "cards";
    static final String STAFF = "staff";
    static final String MINE = "mine";
    static final String TODAY = "today";

    /**
     * One report.
     *
     * @param publicationId the publication whose video it is about
     * @param kind          {@code OPEN}, {@code EXPOSURE} or {@code GO}
     * @param surface       where: {@code VERTICAL} (the vertical mode) or {@code START} (the start page)
     * @param seconds       an exposure's seconds watched without a break
     * @param timer         how they were timed: {@code PLAYER} (the player's clock) or {@code SCREEN} (on the screen,
     *                      the page in front)
     */
    public record Report(@NotNull UUID publicationId, @NotNull @Pattern(regexp = "OPEN|EXPOSURE|GO") String kind,
        @Pattern(regexp = "VERTICAL|START") String surface,
        @Min(0) @Max(ExposureRules.MAX_REPORTED_SEC) Integer seconds,
        @Pattern(regexp = "PLAYER|SCREEN") String timer) {}

    public record ReportInput(@NotEmpty @Size(max = ExposureRules.MAX_BATCH) List<@Valid @NotNull Report> reports) {}

    /** @param outcome what became of it: one of {@link ExposureRules}' outcomes, or {@value #UNKNOWN} */
    public record Result(String publicationId, String kind, String outcome) {}

    /** @param results one per report, in their order; {@code counted}: how many exposures counted */
    public record ReportOutput(List<Result> results, int counted) {}

    private ExposureProcesses() {}

    public static ProcessDefinition<ReportInput, ReportOutput, ProcessContext> report() {
        return ProcessDefinition.define(REPORT, 1, ReportInput.class, ReportOutput.class, ProcessContext.class,
            pb -> pb
                .description("Records what the App saw of the videos of publications using another's shared video: "
                    + "a push opened, an exposure, going on to answer.")
                .permissions(QbPermissions.PLAY)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ReportOutput.class))
                .step("Load the parameters", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.EXPOSURE_SECONDS, QbParams.EXPOSURE_DAILY_CAP))
                .step("Load the cards and the caller's place on a staff", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                            new ArrayList<>(publications(ctx)))).limit(ExposureRules.MAX_BATCH).build(), CARDS),
                    QueryEntities.query(QbRewards.defaultDataset(QbRewards.STAFF),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("userId", ctx.request().actorId()))
                            .limit(1).build(), STAFF)))
                .step("Take the caller's turn", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbExposures.lockName(ctx.request().actorId())))
                .step("Load the caller's rows and today's exposures", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbExposures.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("userId", ctx.request().actorId()),
                            new QueryPredicate.In("publicationId", publications(ctx).stream()
                                .map(id -> (Object) id.toString()).toList()))))
                        .limit(ExposureRules.MAX_BATCH).build(), MINE),
                    QueryEntities.query(QbExposures.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("userId", ctx.request().actorId()),
                            new QueryPredicate.Gte("countedAt", dayStart(ctx.opTime())),
                            new QueryPredicate.Lt("countedAt", dayStart(ctx.opTime()).plusSeconds(86_400)))))
                        .limit(cap(ctx) + 1).build(), TODAY)))
                .compute("Record the reports", (metadata, ctx) -> record(ctx)));
    }

    /** The publications the reports name, once each, in their order. */
    static LinkedHashSet<Object> publications(ProcessContext ctx) {
        LinkedHashSet<Object> ids = new LinkedHashSet<>();
        ctx.get(INPUT, ReportInput.class).reports().forEach(report -> ids.add(report.publicationId()));
        return ids;
    }

    static Instant dayStart(Instant at) {
        return at.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static int seconds(ProcessContext ctx) {
        return ExposureRules.seconds(param(ctx, QbParams.EXPOSURE_SECONDS));
    }

    static int cap(ProcessContext ctx) {
        return ExposureRules.dailyCap(param(ctx, QbParams.EXPOSURE_DAILY_CAP));
    }

    private static Integer param(ProcessContext ctx, String key) {
        return ctx.contains(PARAMS) ? ctx.get(PARAMS, ParamValues.class).find(key, BigDecimal.class)
            .map(BigDecimal::intValue).orElse(null) : null;
    }

    /** One publication's row as this call leaves it: the row read (none: new) and the values to write. */
    private static final class Row {
        final EntityInstance stored;
        final Map<String, Object> values = new LinkedHashMap<>();

        Row(EntityInstance stored) {
            this.stored = stored;
        }

        Object get(String field) {
            return values.containsKey(field) ? values.get(field) : stored == null ? null : stored.get(field);
        }
    }

    static void record(ProcessContext ctx) {
        String caller = ctx.request().actorId();
        Instant now = ctx.opTime();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        int seconds = seconds(ctx);
        int cap = cap(ctx);
        Map<String, EntityInstance> cards = new LinkedHashMap<>();
        for (EntityInstance card : list(ctx, CARDS)) {
            if (PublisherKind.sponsor(card.get(PublisherKind.KIND_FIELD))) {
                cards.put(String.valueOf((Object) card.get("publicationId")), card);
            }
        }
        List<String> staffOf = list(ctx, STAFF).stream().map(row -> text(row.get("sponsorId")))
            .filter(Objects::nonNull).toList();
        Map<String, Row> rows = new LinkedHashMap<>();
        for (EntityInstance row : list(ctx, MINE)) {
            rows.put(text(row.get("publicationId")), new Row(row));
        }
        int countedToday = list(ctx, TODAY).size();
        int counted = 0;
        List<Result> results = new ArrayList<>();
        for (Report report : ctx.get(INPUT, ReportInput.class).reports()) {
            String publicationId = report.publicationId().toString();
            EntityInstance card = cards.get(publicationId);
            if (card == null) {
                results.add(new Result(publicationId, report.kind(), UNKNOWN));
                continue;
            }
            ExposureRules.Card rules = card(card, now);
            Row row = rows.computeIfAbsent(publicationId, id -> new Row(null));
            String outcome;
            if (!ExposureRules.othersVideo(rules)) {
                outcome = ExposureRules.OWN_VIDEO;
            } else if (ExposureRules.OPEN.equals(report.kind())) {
                if (row.get("openedAt") == null) {
                    row.values.put("openedAt", now);
                }
                outcome = ExposureRules.RECORDED;
            } else if (ExposureRules.GO.equals(report.kind())) {
                if (row.get("wentToAnswerAt") == null) {
                    row.values.put("wentToAnswerAt", now);
                }
                outcome = ExposureRules.RECORDED;
            } else {
                outcome = ExposureRules.decide(rules, caller, staffOf, report.surface(),
                    report.seconds() == null ? 0 : report.seconds(), seconds, row.get("countedAt") != null,
                    countedToday, cap);
                if (ExposureRules.COUNTED.equals(outcome)) {
                    row.values.put("countedAt", now);
                    row.values.put("surface", report.surface());
                    row.values.put("countedSec", BigDecimal.valueOf(report.seconds()));
                    row.values.put("timer", report.timer());
                    countedToday++;
                    counted++;
                } else if (ExposureRules.DAILY_CAP.equals(outcome) && !today.equals(row.get("lastCappedOn"))) {
                    Object days = row.get("cappedDays");
                    row.values.put("cappedDays", BigDecimal.valueOf((days instanceof Number n ? n.longValue() : 0)
                        + 1));
                    row.values.put("lastCappedOn", today);
                }
            }
            results.add(new Result(publicationId, report.kind(), outcome));
        }
        write(ctx, rows, cards, caller, now);
        ctx.put(OUTPUT, new ReportOutput(List.copyOf(results), counted));
    }

    private static void write(ProcessContext ctx, Map<String, Row> rows, Map<String, EntityInstance> cards,
        String caller, Instant now) {
        rows.forEach((publicationId, row) -> {
            if (row.values.isEmpty()) {
                return;
            }
            if (row.stored == null) {
                EntityInstance card = cards.get(publicationId);
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("publicationId", publicationId);
                values.put("userId", caller);
                values.put("publisherId", card.get(PublisherKind.ID_FIELD));
                values.put("providerId", card.get("videoProviderId"));
                values.put("cappedDays", BigDecimal.ZERO);
                values.put("createdAt", now);
                values.putAll(row.values);
                ctx.changes().insert(QbExposures.EXPOSURE, values);
            } else {
                ctx.changes().update(QbExposures.EXPOSURE, row.stored.id(), row.stored.version(), row.values);
            }
        });
    }

    /** What the rules read of a card at {@code now}. */
    static ExposureRules.Card card(EntityInstance card, Instant now) {
        Object duration = card.get("videoDurationSec");
        return new ExposureRules.Card(text(card.get(PublisherKind.ID_FIELD)), text(card.get("videoProviderId")),
            card.get("sharedVideoId") != null, Boolean.TRUE.equals(card.get("videoVertical")),
            PublicationRules.acceptsNewAttempts(text(card.get("status")), instant(card.get("startAt")),
                instant(card.get("endAt")), now),
            duration instanceof Number n ? n.intValue() : null);
    }

    static Instant instant(Object value) {
        return value instanceof Instant i ? i : value instanceof java.time.OffsetDateTime o ? o.toInstant()
            : value == null ? null : Instant.parse(value.toString());
    }

    static String text(Object value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.contains(key) ? ctx.get(key) : null;
        return value == null ? List.of() : (List<EntityInstance>) value;
    }
}
