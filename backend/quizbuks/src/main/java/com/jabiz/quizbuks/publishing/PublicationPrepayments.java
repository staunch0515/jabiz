package com.jabiz.quizbuks.publishing;

import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.audience.AudienceBilling;
import com.jabiz.quizbuks.audience.AudiencePricingProcesses;
import com.jabiz.quizbuks.audience.QbAudience;
import com.jabiz.quizbuks.audience.QbAudiencePricing;
import com.jabiz.quizbuks.fee.FeeGate;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.FeeSettlement;
import com.jabiz.quizbuks.fee.PrepaymentRules;
import com.jabiz.quizbuks.fee.Prepayments;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.fee.QbPrepayments;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A publication's money at its submission and after (F-M5, F-M11; docs/quizbuks/plans/impl/F-M.md §8).
 *
 * <p>At the submission, after the review is asked for (the lock order puts the sponsor's wallet lock after the
 * approval's), {@link #load} takes the sponsor's wallet lock and reads, in one statement, the account (fee credit not
 * expired, the cash wallet), their open postpaid prepayments, the publication's audience criteria, the rules and the
 * sponsor's operator; the operator's price list and prices only when the publication uses a rule and the rules are
 * charged for; what a postpaid sponsor owes ({@code qb.system.fee-receivable}) only when something is set aside.
 * {@link #check} adds up what the publication sets aside ({@link #items}: today the audience fee and its tax, F-M7;
 * F-M6 and F-M8 add theirs) and refuses a shortfall with {@code QB_PUBLICATION_FUNDS}, writing nothing (the whole
 * submission is refused); otherwise the operator is frozen on the publication and its fees are set aside
 * ({@code QB_PREPAYMENT_FREEZE}, one a purpose).
 *
 * <p>After the publication's state changes ({@link #after}): starting (going live) charges the audience fee from what
 * was set aside ({@code QB_PREPAYMENT_CHARGE}); back to the draft (withdrawn, rejected, its review expired) gives everything
 * back ({@code QB_PREPAYMENT_RELEASE}). An ended publication's is given back by the daily job.
 */
public final class PublicationPrepayments {

    static final String PARAMS = PublicationLifecycle.FEE_PARAMS;
    static final String CREDITS = "funds.credits";
    static final String CASH_WALLET = "funds.cashWallet";
    static final String POSTPAID = "funds.postpaid";
    static final String CRITERIA = "funds.criteria";
    static final String RULES = "funds.rules";
    static final String ASSIGNMENT = "funds.assignment";
    static final String LISTS = "funds.lists";
    static final String PRICES = "funds.prices";
    static final String ITEMS = "funds.items";
    static final String OWED = "funds.owed";
    static final String FUNDS = "funds.funds";
    static final String FREEZES = "funds.freezes";
    static final String FROZEN = "funds.frozen";
    /** What {@link PublicationEditing#write} found the state change asks for afterwards. */
    static final String RELEASE = "funds.release";
    static final String CHARGE = "funds.charge";
    static final String RELEASED = "funds.released";
    static final String CHARGED = "funds.charged";

    /** The parameters the submission's funds read (optional: not published reads as off, as the fee gate's). */
    static final List<String> PARAM_KEYS = List.of(QbParams.AUDIENCE_BILLING_ENABLED, QbParams.FEE_TAX_RATE,
        QbParams.FEE_DEFAULT_BILLING_MODE);

    private PublicationPrepayments() {}

    /** Whether a publication's submission sets anything aside: a sponsor's (not a shop's), and not a challenge. */
    static boolean concerned(EntityInstance publication) {
        return publication != null && PublisherKind.SPONSOR.equals(publication.get("publisherKind"))
            && !Boolean.TRUE.equals(publication.get("forSponsors"));
    }

    /**
     * The reads of the check. {@code locked}: under the sponsor's wallet lock, taken first (the submission); without
     * it for the wizard's look ({@code QB_PUBLICATION_FUNDS_CHECK}). {@code asked}: whether to read at all (a
     * submission refused already, or a publication not concerned, reads nothing and takes no lock).
     */
    static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, EntityInstance> publication, Predicate<ProcessContext> asked, boolean locked) {
        Predicate<ProcessContext> reads = ctx -> asked.test(ctx) && concerned(publication.apply(ctx));
        if (locked) {
            pb.step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(ctx -> reads.test(ctx)
                ? QbWallets.lockName(QbWallets.SPONSOR, sponsorOf(publication.apply(ctx))) : null));
        }
        pb.step("Read the account, the audience rules and the operator", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFees.defaultDataset(com.jabiz.quizbuks.fee.QbFeeAccount.GRANT),
                    ctx -> FeeSettlement.credits(reads.test(ctx) ? sponsorOf(publication.apply(ctx)) : null,
                        ctx.opTime()), CREDITS),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(QbWallets.CASH,
                    reads.test(ctx) ? sponsorOf(publication.apply(ctx)) : null), CASH_WALLET),
                QueryEntities.query(QbFees.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("sponsorId",
                            FeeGate.ids(reads.test(ctx) ? sponsorOf(publication.apply(ctx)) : null)),
                        new QueryPredicate.Eq("mode", QbPrepayments.POSTPAID),
                        new QueryPredicate.Eq("status", QbPrepayments.OPEN))))
                    .limit(10_000).build(), POSTPAID),
                QueryEntities.query(QbAudience.CRITERION_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("publicationId",
                        FeeGate.ids(reads.test(ctx) ? publication.apply(ctx).id() : null)))
                    .limit(QbAudience.MAX_ROWS).build(), CRITERIA),
                AudiencePricingProcesses.loadRules(RULES),
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("userId",
                        FeeGate.ids(reads.test(ctx) ? sponsorOf(publication.apply(ctx)) : null)))
                    .limit(1).build(), ASSIGNMENT)))
            .step("Read the operator's audience prices", QueryEntities.<ProcessContext>of(
                QbAudiencePricing.defaultDataset(QbAudiencePricing.PRICE_LIST), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("owner", FeeGate.ids(reads.test(ctx) && audiencePriced(ctx)
                        ? operatorOf(ctx) : null))).limit(1).build(), LISTS))
            .step("Read their prices", QueryEntities.<ProcessContext>of(
                QbAudiencePricing.defaultDataset(QbAudiencePricing.PRICE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("priceListId", FeeGate.list(ctx, LISTS).stream()
                        .map(row -> (Object) String.valueOf(row.id())).toList())).limit(101).build(), PRICES))
            .compute("Work out what it sets aside", (metadata, ctx) -> {
                if (reads.test(ctx)) {
                    ctx.put(ITEMS, items(ctx, publication.apply(ctx)));
                }
            })
            .step("Read what a postpaid sponsor owes", RunTemplate.<ProcessContext>when(
                ctx -> ctx.contains(ITEMS) && postpaid(ctx) && total(ctx).signum() > 0,
                "qb.system.fee-receivable", ctx -> Map.of("sponsorId", sponsorOf(publication.apply(ctx))), OWED));
    }

    /** Whether the publication uses a rule and the rules are charged for (only then are the prices read). */
    private static boolean audiencePriced(ProcessContext ctx) {
        return !FeeGate.list(ctx, CRITERIA).isEmpty() && operatorOf(ctx) != null
            && Boolean.TRUE.equals(params(ctx).find(QbParams.AUDIENCE_BILLING_ENABLED, Boolean.class).orElse(false));
    }

    /** What the publication sets aside: the audience fee and its tax (F-M7), when there is one. */
    static List<PrepaymentRules.Item> items(ProcessContext ctx, EntityInstance publication) {
        boolean enabled = params(ctx).find(QbParams.AUDIENCE_BILLING_ENABLED, Boolean.class).orElse(false);
        String operator = operatorOf(ctx);
        Map<String, AudienceBilling.PriceList> lists = AudiencePricingProcesses.priceLists(FeeGate.list(ctx, LISTS),
            FeeGate.list(ctx, PRICES));
        List<String> used = FeeGate.list(ctx, CRITERIA).stream()
            .map(row -> String.valueOf((Object) row.get("criterionKey"))).toList();
        AudienceBilling.Quote quote = AudienceBilling.quote(enabled, used,
            AudiencePricingProcesses.rules(FeeGate.list(ctx, RULES)),
            AudienceBilling.applicable(operator, operator == null ? null : lists.get(operator)),
            instant(publication.get("startAt")), instant(publication.get("endAt")));
        List<PrepaymentRules.Item> items = new ArrayList<>();
        if (quote.fee().signum() > 0) {
            BigDecimal rate = params(ctx).find(QbParams.FEE_TAX_RATE, BigDecimal.class).orElse(BigDecimal.ZERO);
            BigDecimal tax = FeeRules.tax(quote.fee(), rate);
            BigDecimal fee = quote.fee().setScale(0, java.math.RoundingMode.CEILING);
            BigDecimal withTax = fee.add(tax == null ? BigDecimal.ZERO : tax);
            items.add(new PrepaymentRules.Item(QbPrepayments.AUDIENCE, withTax, fee, detail(quote, withTax)));
        }
        return List.copyOf(items);
    }

    /** The audience fee's quote as stored with its prepayment: whose list, the days, the rules and the figures. */
    static String detail(AudienceBilling.Quote quote, BigDecimal withTax) {
        StringBuilder json = new StringBuilder("{\"owner\":").append(quote.owner() == null ? "null"
            : "\"" + quote.owner() + "\"").append(",\"days\":").append(quote.days()).append(",\"items\":[");
        for (int i = 0; i < quote.items().size(); i++) {
            AudienceBilling.Item item = quote.items().get(i);
            json.append(i == 0 ? "" : ",").append("{\"ruleKey\":\"").append(item.ruleKey())
                .append("\",\"dailyPrice\":").append(item.dailyPrice().toPlainString()).append('}');
        }
        return json.append("],\"subtotal\":").append(quote.subtotal().toPlainString())
            .append(",\"minimumFee\":").append(quote.minimumFee().toPlainString())
            .append(",\"fee\":").append(quote.fee().toPlainString())
            .append(",\"withTax\":").append(withTax.toPlainString()).append('}').toString();
    }

    /**
     * The check of a submission (after {@link #load}): a shortfall is refused; otherwise the freezes are put for
     * {@link #freeze} and the billing values for the publication's write ({@link #values}).
     */
    static void check(ProcessContext ctx, EntityInstance publication) {
        if (!ctx.contains(ITEMS)) {
            return;
        }
        PrepaymentRules.Funds funds = funds(ctx);
        ctx.put(FUNDS, funds);
        com.jabiz.entity.Violation refusal = PrepaymentRules.refusal(funds);
        if (refusal != null) {
            ctx.reject(refusal);
            return;
        }
        List<Prepayments.FreezeInput> freezes = new ArrayList<>();
        for (PrepaymentRules.Item item : funds.items()) {
            freezes.add(new Prepayments.FreezeInput(sponsorOf(publication), String.valueOf(publication.id()),
                item.purpose(), funds.billingMode(), item.amount(), item.chargeAmount(), operatorOf(ctx),
                item.detail()));
        }
        ctx.put(FREEZES, List.copyOf(freezes));
    }

    /** The funds of the publication from the reads (the check, and the wizard's look). */
    static PrepaymentRules.Funds funds(ProcessContext ctx) {
        List<PrepaymentRules.Item> items = itemsOf(ctx);
        FeeRules.Standing standing = FeeGate.standing(ctx, params(ctx)
            .find(QbParams.FEE_DEFAULT_BILLING_MODE, String.class).orElse(null));
        BigDecimal setAside = FeeGate.list(ctx, POSTPAID).stream().map(row -> FeeGate.money(row.get("remaining")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal owed = ctx.contains(OWED) ? owedOf(ctx) : BigDecimal.ZERO;
        return PrepaymentRules.check(items, new PrepaymentRules.Standing(standing.billingMode(),
            FeeSettlement.spendable(FeeGate.list(ctx, CREDITS), FeeGate.first(ctx, CASH_WALLET), ctx.opTime()),
            owed, setAside, standing.creditLimit()));
    }

    @SuppressWarnings("unchecked")
    private static BigDecimal owedOf(ProcessContext ctx) {
        Object rows = ctx.get(OWED);
        if (rows instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> row) {
            return FeeGate.money(((Map<String, Object>) row).get("owed"));
        }
        return BigDecimal.ZERO;
    }

    @SuppressWarnings("unchecked")
    static List<PrepaymentRules.Item> itemsOf(ProcessContext ctx) {
        return ctx.contains(ITEMS) ? (List<PrepaymentRules.Item>) ctx.get(ITEMS) : List.of();
    }

    private static BigDecimal total(ProcessContext ctx) {
        return itemsOf(ctx).stream().map(PrepaymentRules.Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean postpaid(ProcessContext ctx) {
        return FeeRules.POSTPAID.equals(FeeGate.standing(ctx, params(ctx)
            .find(QbParams.FEE_DEFAULT_BILLING_MODE, String.class).orElse(null)).billingMode());
    }

    /** Sets the publication's fees aside ({@code QB_PREPAYMENT_FREEZE}, one a purpose), under the wallet lock. */
    static <I, O> void freeze(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Set its fees aside", CallProcess.<ProcessContext>forEach(Prepayments.FREEZE, 1,
            ctx -> !ctx.hasViolations() && ctx.contains(FREEZES) ? (List<?>) ctx.get(FREEZES) : List.of(), FROZEN));
    }

    /** F-M11, F-M5: what the submission writes on the publication: the operator, and what it set aside. */
    static Map<String, Object> values(ProcessContext ctx) {
        if (!ctx.contains(FUNDS)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("billingOperatorId", operatorOf(ctx));
        BigDecimal total = ctx.get(FUNDS, PrepaymentRules.Funds.class).total();
        values.put("prepaymentTotal", total.signum() > 0 ? total : null);
        return values;
    }

    /**
     * Notes what a state change of the publication asks for afterwards ({@link PublicationEditing#write} calls it):
     * back to the draft with something set aside gives it back; starting (going live, not approved) with something set
     * aside charges the audience fee from it.
     */
    static void note(ProcessContext ctx, EntityInstance publication, Map<String, Object> values, String to) {
        if (!concerned(publication)) {
            return;
        }
        Object setAside = values.containsKey("prepaymentTotal") ? values.get("prepaymentTotal")
            : publication.get("prepaymentTotal");
        if (setAside == null || FeeGate.money(setAside).signum() <= 0) {
            return;
        }
        String sponsorId = sponsorOf(publication);
        String publicationId = String.valueOf(publication.id());
        if (PublicationStatus.DRAFT.equals(to)) {
            ctx.put(RELEASE, new Prepayments.ReleaseInput(sponsorId, publicationId, releaseReason(values)));
        } else if (PublicationStatus.LIVE.equals(to) && !PublicationStatus.FUNDS_SHORT.equals(publication.get("status"))) {
            // §8.8 (M7 of the F-M5 review): charged when it starts, not when it is approved; ended before it starts,
            // nothing is charged and the daily job gives it all back. Resumed after a pause it may have started
            // already: the charge is once (QB_PREPAYMENT_CHARGE does nothing for a prepayment charged).
            ctx.put(CHARGE, new Prepayments.ChargeInput(sponsorId, publicationId, QbPrepayments.AUDIENCE));
        }
    }

    /** Why it went back to the draft: its review expired, it was refused (rejected, banned, its video taken down), or withdrawn. */
    static String releaseReason(Map<String, Object> values) {
        if (PublishingCodes.EXPIRED_IN_REVIEW.equals(values.get("rejectCode"))) {
            return QbPrepayments.REVIEW_EXPIRED;
        }
        return values.get("rejectCode") != null || values.get("rejectReason") != null
            ? QbPrepayments.REJECTED : QbPrepayments.WITHDRAWN;
    }

    /**
     * The steps after a state change ({@link #note}): the sponsor's wallet lock (after the publication's and the
     * approval's, as the lock order says), then the charge or the release. Nothing noted: no lock, no statement.
     */
    static <I, O> void after(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the sponsor's wallets for the prepayment", HoldLock.<ProcessContext>exclusive(ctx ->
                ctx.hasViolations() ? null
                    : ctx.contains(RELEASE) ? QbWallets.lockName(QbWallets.SPONSOR,
                        ctx.get(RELEASE, Prepayments.ReleaseInput.class).sponsorId())
                    : ctx.contains(CHARGE) ? QbWallets.lockName(QbWallets.SPONSOR,
                        ctx.get(CHARGE, Prepayments.ChargeInput.class).sponsorId())
                    : null))
            .step("Give its prepayments back", CallProcess.<ProcessContext>when(
                ctx -> !ctx.hasViolations() && ctx.contains(RELEASE), Prepayments.RELEASE, 1,
                ctx -> ctx.get(RELEASE), RELEASED))
            .step("Charge its audience fee", CallProcess.<ProcessContext>when(
                ctx -> !ctx.hasViolations() && ctx.contains(CHARGE), Prepayments.CHARGE, 1,
                ctx -> ctx.get(CHARGE), CHARGED));
    }

    // ---- the wizard's look ---------------------------------------------------------------------------------------------

    public static final String CHECK = "QB_PUBLICATION_FUNDS_CHECK";
    static final String CHECKED = "funds.checked";

    /** One fee the publication would set aside: what for, with its tax, and the fee before tax. */
    public record CheckItem(String purpose, BigDecimal amount, BigDecimal chargeAmount) {}

    /**
     * @param total     what submitting now would set aside
     * @param available prepaid: what the account may spend; postpaid: what is left of the credit limit
     * @param shortfall what is missing (0: the submission passes the check)
     */
    public record CheckOutput(List<CheckItem> items, BigDecimal total, BigDecimal available, BigDecimal shortfall,
        String billingMode, boolean enough) {}

    /**
     * {@value #CHECK}: what submitting the caller's publication now would set aside and whether the account covers it
     * (the wizard's last step). The same reads and rules as the submission, without the lock; it writes nothing.
     */
    public static com.jabiz.process.ProcessDefinition<PublicationProcesses.PublicationRef, CheckOutput, ProcessContext>
        check() {
        return com.jabiz.process.ProcessDefinition.define(CHECK, 1, PublicationProcesses.PublicationRef.class,
            CheckOutput.class, ProcessContext.class, pb -> {
                pb.description("Tells what submitting the caller's publication now would set aside of their account "
                        + "for its fees, and whether the account covers it.")
                    .permissions(com.jabiz.quizbuks.QbPermissions.PUBLICATION_WRITE)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(PublicationEditing.PUB_ID, input.publicationId());
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(CHECKED, CheckOutput.class))
                    // The caller's own publication only: anybody else's is not found.
                    .step("Load the publication", com.jabiz.runtime.process.steps.LoadEntity.by(
                        QbPublications.sponsorDataset(QbPublications.PUBLICATION), PublicationEditing.PUB_ID,
                        PublicationEditing.PUBLICATION))
                    .step("Load the fee parameters", com.jabiz.runtime.process.steps.LoadParams.<ProcessContext>optional(
                        ProcessContext::opTime, PARAMS, QbParams.FEE_DEFAULT_BILLING_MODE,
                        QbParams.AUDIENCE_BILLING_ENABLED, QbParams.FEE_TAX_RATE))
                    .steps(b -> FeeGate.load(b, ctx -> ctx.request().actorId()));
                load(pb, ctx -> ctx.get(PublicationEditing.PUBLICATION, EntityInstance.class), ctx -> true, false);
                pb.compute("Check", (metadata, ctx) -> {
                    PrepaymentRules.Funds funds = ctx.contains(ITEMS) ? funds(ctx)
                        : PrepaymentRules.check(List.of(), new PrepaymentRules.Standing(FeeRules.PREPAID,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
                    ctx.put(CHECKED, new CheckOutput(funds.items().stream()
                        .map(item -> new CheckItem(item.purpose(), item.amount(), item.chargeAmount())).toList(),
                        funds.total(), funds.available(), funds.shortfall(), funds.billingMode(), funds.enough()));
                });
            });
    }

    static String sponsorOf(EntityInstance publication) {
        return String.valueOf((Object) publication.get("publisherId"));
    }

    static String operatorOf(ProcessContext ctx) {
        EntityInstance assignment = FeeGate.first(ctx, ASSIGNMENT);
        return assignment == null ? null : String.valueOf((Object) assignment.get("operatorId"));
    }

    static ParamValues params(ProcessContext ctx) {
        return ctx.get(PARAMS, ParamValues.class);
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }
}
