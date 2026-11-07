package com.jabiz.quizbuks.region;

import com.jabiz.context.ClientCountry;
import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadClientCountry;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The rule on foreign videos at a start (F-S11, docs/quizbuks/plans/impl/F-S11.md): the server's defence, where the
 * App's pages only hide. Before any lock: the caller's country from the address ({@code LoadClientCountry}, in memory,
 * no statement); only for a new start of a card with a foreign video from mainland China or an unknown address, one
 * statement reading the parameter for unknown addresses (only when unknown) and the exceptions of the publication and
 * of its sponsor. An ordinary start (no foreign video, or another country) reads nothing more: its queries are empty
 * and send nothing, so {@code AttemptStatementBudgetIT} is unchanged. Invited publications alike (A3-15).
 */
public final class RegionStart {

    static final String COUNTRY = "region.country";
    static final String UNKNOWN_PARAM = "region.unknownParam";
    static final String EXCEPTIONS = "region.exceptions";

    private RegionStart() {
    }

    /**
     * The steps reading what the rule needs.
     *
     * @param card     the card read before the lock
     * @param starting whether this is a new start (a continued attempt is not asked again)
     */
    public static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, EntityInstance> card, Predicate<ProcessContext> starting) {
        pb.step("Where the caller is", LoadClientCountry.<ProcessContext>into(COUNTRY))
            .step("Read the exceptions of a foreign video from mainland China", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbWallets.PARAMS_NOW, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("paramKey", candidate(ctx, card, starting) && !country(ctx).known()
                        ? new ArrayList<>(List.of(QbParams.REGION_UNKNOWN_IP_COUNTRY)) : new ArrayList<>()))
                    .limit(1).build(), UNKNOWN_PARAM),
                QueryEntities.query(QbRegion.defaultDataset(QbRegion.EXCEPTION),
                    ctx -> exceptionsOf(candidate(ctx, card, starting) ? card.apply(ctx) : null), EXCEPTIONS)));
    }

    /**
     * Refuses (422 {@code QB_PUBLICATION_REGION_VIDEO}) a new start the rule keeps out, and counts it.
     *
     * @param publicationId the publication started, as the input names it
     */
    public static void refuse(ProcessContext ctx, EntityInstance card, Object publicationId, boolean starting,
        RegionRefusals refusals) {
        if (!starting || card == null || !RegionRules.foreign(card.get("foreignVideo"))) {
            return;
        }
        boolean mainland = RegionRules.mainland(country(ctx), unknownAs(ctx));
        String sponsor = PublisherKind.sponsor(card.get("publisherKind"))
            ? String.valueOf((Object) card.get("publisherId")) : null;
        if (RegionRules.refused(card.get("foreignVideo"), mainland, exceptions(ctx),
            RegionRules.today(ctx.opTime()), String.valueOf(publicationId), sponsor)) {
            refusals.count(ctx.opTime());
            ctx.reject(new Violation("publicationId", RegionCodes.REGION_VIDEO, "This quiz has a video that cannot "
                + "be played where you are, so it cannot be answered there", Map.of()));
        }
    }

    /** Whether the start may be kept out: a new start of a card with a foreign video, from mainland China or unknown. */
    private static boolean candidate(ProcessContext ctx, Function<ProcessContext, EntityInstance> card,
        Predicate<ProcessContext> starting) {
        if (!starting.test(ctx)) {
            return false;
        }
        EntityInstance found = card.apply(ctx);
        if (found == null || !RegionRules.foreign(found.get("foreignVideo"))) {
            return false;
        }
        ClientCountry country = country(ctx);
        return !country.known() || country.mainlandChina();
    }

    private static EntityQuery exceptionsOf(EntityInstance card) {
        if (card == null) {
            return EntityQuery.builder().where(new QueryPredicate.In("exceptionId", new ArrayList<>())).limit(1)
                .build();
        }
        List<QueryPredicate> either = new ArrayList<>();
        either.add(new QueryPredicate.Eq("publicationId", String.valueOf((Object) card.get("publicationId"))));
        if (PublisherKind.sponsor(card.get("publisherKind"))) {
            either.add(new QueryPredicate.Eq("sponsorId", String.valueOf((Object) card.get("publisherId"))));
        }
        // A publication's own and its sponsor's: a handful at most (revoked ones stay as records).
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(new QueryPredicate.Or(either),
            new QueryPredicate.Eq("revoked", false)))).limit(100).build();
    }

    static ClientCountry country(ProcessContext ctx) {
        ClientCountry country = ctx.contains(COUNTRY) ? ctx.get(COUNTRY, ClientCountry.class) : null;
        return country == null ? ClientCountry.UNKNOWN : country;
    }

    /** The parameter for unknown addresses: its value, the declared one before the setup created it. */
    @SuppressWarnings("unchecked")
    static String unknownAs(ProcessContext ctx) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(UNKNOWN_PARAM);
        if (rows == null || rows.isEmpty()) {
            return QbParams.defaultOf(QbParams.REGION_UNKNOWN_IP_COUNTRY);
        }
        Object value = rows.getFirst().get("value");
        String text = value == null ? "" : value.toString().strip();
        // A text parameter as its JSON string, or as the bare text.
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    static List<RegionRules.Exception> exceptions(ProcessContext ctx) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(EXCEPTIONS);
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(row -> new RegionRules.Exception((String) row.get("kind"),
            (String) row.get("publicationId"), (String) row.get("sponsorId"), date(row.get("expiresOn")),
            Boolean.TRUE.equals(row.get("revoked")))).toList();
    }

    private static LocalDate date(Object value) {
        return value == null ? null : value instanceof LocalDate date ? date : LocalDate.parse(value.toString());
    }
}
