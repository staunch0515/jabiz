package com.jabiz.quizbuks.exposure;

import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.ExposureFeeRules;
import com.jabiz.quizbuks.fee.QbExposureFees;
import com.jabiz.quizbuks.publishing.PublicationNotices;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-M8: the sponsor is told when what is left of the exposures bought for a publication is {@code qb.exposure.low-ratio}
 * of them or less ({@value #ITEM}, the item of the job {@value #JOB}). Once for each number bought: a top-up lets it be
 * told again. The job lists the publications under half left that have more counted since they were last looked at
 * ({@value #DUE_TEMPLATE}); the item decides with the real ratio, under a lock of its own, and writes what it looked at
 * in {@code QbExposureNotice}. The notice is the publication's status event ({@link PublicationNotices#EXPOSURE_LOW}),
 * mailed by {@code qb.publication-notify} outside any publication's lock; the reason carries {@code left/bought}.
 */
public final class ExposureLowNotices {

    public static final String ITEM = "QB_EXPOSURE_LOW_NOTICE_ITEM";
    public static final String JOB = "qb.exposure-low";
    public static final String DUE_TEMPLATE = "qb.system.exposure-low-due";
    /** The pace of the job (every ten minutes); deployment setting {@code quizbuks.exposure.low-cron}. */
    public static final String CRON = "0 */10 * * * *";

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String CARD = "card";
    static final String NOTICE_ROW = "noticeRow";
    static final String COUNTS = "counts";
    static final String NOTICE = "notice";

    private ExposureLowNotices() {}

    public record LowRef(@NotBlank @Size(max = 36) String publicationId) {}

    public record LowOutput(String publicationId, String outcome) {}

    public static ProcessDefinition<LowRef, LowOutput, ProcessContext> item() {
        return ProcessDefinition.define(ITEM, 1, LowRef.class, LowOutput.class, ProcessContext.class, pb -> pb
            .description("Tells a sponsor that what is left of the exposures bought for a publication is low.")
            .permissions(QbPermissions.PUBLICATION_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new LowOutput(ref(ctx).publicationId(),
                ctx.contains(NOTICE) ? "TOLD" : ctx.contains(CHECKED) ? "LOOKED" : "SKIPPED"))
            .step("Load the ratio", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                QbParams.EXPOSURE_LOW_RATIO))
            // Its own lock, no publication's: nothing here changes the publication.
            .step("Lock its notice", HoldLock.<ProcessContext>exclusive(ctx -> "qb.exposure-notice:" + ref(ctx).publicationId()))
            .step("Read the publication and its notice", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD),
                    ctx -> byPublication(ref(ctx).publicationId()), CARD),
                QueryEntities.query(QbExposureFees.defaultDataset(QbExposureFees.NOTICE),
                    ctx -> byPublication(ref(ctx).publicationId()), NOTICE_ROW)))
            .step("Count its exposures", RunTemplate.<ProcessContext>of("qb.system.exposure-count", ctx -> Map.of(
                "publicationId", ref(ctx).publicationId(), "before", Instant.parse("9999-12-31T00:00:00Z")), COUNTS))
            .compute("Look at it", (metadata, ctx) -> look(ctx))
            .step("Tell the sponsor", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(NOTICE),
                PublicationNotices.EVENT, ctx -> ctx.get(NOTICE))));
    }

    static final String CHECKED = "checked";

    static void look(ProcessContext ctx) {
        EntityInstance card = first(ctx, CARD);
        if (card == null || card.get("exposurePurchased") == null || !PublisherKind.SPONSOR.equals(card.get("publisherKind"))
            || !PublicationStatus.RUNNING.contains(String.valueOf((Object) card.get("status")))) {
            return;
        }
        long purchased = ((Number) card.get("exposurePurchased")).longValue();
        Map<?, ?> counts = ctx.get(COUNTS) instanceof List<?> found && !found.isEmpty()
            && found.getFirst() instanceof Map<?, ?> row ? row : Map.of();
        long used = counts.get("total") == null ? 0 : new BigDecimal(String.valueOf(counts.get("total"))).longValue();
        BigDecimal ratio = ctx.get(PARAMS, ParamValues.class).find(QbParams.EXPOSURE_LOW_RATIO, BigDecimal.class)
            .orElse(BigDecimal.ZERO).min(ExposureFeeRules.MAX_LOW_RATIO);
        EntityInstance notice = first(ctx, NOTICE_ROW);
        boolean told = notice != null && ((Number) notice.get("notifiedFor")).longValue() == purchased;
        boolean tell = !told && used < purchased && ExposureFeeRules.running(purchased, used, ratio);
        ctx.put(CHECKED, true);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("checkedUsed", BigDecimal.valueOf(used));
        if (tell) {
            values.put("notifiedFor", BigDecimal.valueOf(purchased));
            values.put("notifiedAt", ctx.opTime());
        }
        if (notice == null) {
            values.put("publicationId", ref(ctx).publicationId());
            values.putIfAbsent("notifiedFor", BigDecimal.ZERO);
            values.putIfAbsent("notifiedAt", ctx.opTime());
            ctx.changes().insert(QbExposureFees.NOTICE, values);
        } else {
            ctx.changes().update(QbExposureFees.NOTICE, notice.id(), notice.version(), values);
        }
        if (!tell) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("publicationId", ref(ctx).publicationId());
        payload.put("publisherKind", card.get("publisherKind"));
        payload.put("publisherId", card.get("publisherId"));
        payload.put("from", card.get("status"));
        payload.put("to", card.get("status"));
        payload.put("kind", PublicationNotices.EXPOSURE_LOW);
        payload.put("reason", (purchased - used) + "/" + purchased);
        payload.put("name", card.get("name"));
        ctx.put(NOTICE, payload);
    }

    private static EntityQuery byPublication(String publicationId) {
        return EntityQuery.builder().where(new QueryPredicate.In("publicationId", List.of(publicationId))).limit(1)
            .build();
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        return ctx.get(key) instanceof List<?> rows && !rows.isEmpty() ? (EntityInstance) rows.getFirst() : null;
    }

    static LowRef ref(ProcessContext ctx) {
        return ctx.get(INPUT, LowRef.class);
    }
}
