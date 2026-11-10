package com.jabiz.quizbuks.audience;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Managing the audience rules and their prices (Q47c-1, docs/quizbuks/plans/Q47c-audience-billing.md):
 * <ul>
 *   <li>{@value #RULES_SET}: headquarters open or close the kinds of rule, say which are charged for, and order them;</li>
 *   <li>{@value #PRICES_SET}: headquarters save any operator's price list (F-M5: no list of their own any more,
 *       the requester 2026-11-09); {@value #OPERATOR_PRICES_SET}: an operator's lead saves the operator's own (rules are the
 *       platform's product line; the operators sell them at their own prices, 2026-11-03). A reason is required;</li>
 *   <li>{@value #QUOTE}: what a sponsor's draft would pay for its rules now ({@link AudienceBilling}): the wizard shows
 *       it; Q47c-2 charges the same figure on submit.</li>
 * </ul>
 * A price change applies to what is submitted afterwards. Locks: {@code qb.audience-rules}; an owner's
 * {@code qb.audience-prices:<owner>} (after the caller's membership for an operator).
 */
public final class AudiencePricingProcesses {

    public static final String RULES_SET = "QB_AUDIENCE_RULES_SET";
    public static final String PRICES_SET = "QB_AUDIENCE_PRICES_SET";
    public static final String OPERATOR_PRICES_SET = "QB_OPERATOR_AUDIENCE_PRICES_SET";
    public static final String QUOTE = "QB_AUDIENCE_FEE_QUOTE";

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String RULES = "rules";
    static final String OPERATOR = "operator";
    static final String LIST = "priceList";
    static final String PRICES = "prices";
    static final String PARAMS = "params";
    static final String PUBLICATION = "publication";
    static final String CRITERIA = "criteria";
    static final String ASSIGNMENT = "assignment";
    static final String LISTS = "priceLists";
    static final String OWNER = "owner";

    /**
     * @param ruleKey   a kind of criterion of {@link AudienceCriteria}
     * @param enabled   whether sponsors may use it
     * @param billable  whether it is charged for
     * @param sortOrder its place in the list
     */
    public record RuleInput(@NotBlank @Size(max = 32) String ruleKey, @NotNull Boolean enabled,
        @NotNull Boolean billable, @NotNull @Min(0) @Max(9999) Integer sortOrder) {}

    /** @param rules the rules to save (others stay as they are) */
    public record RulesInput(@NotNull @Size(max = 50) List<@Valid @NotNull RuleInput> rules) {}

    /** @param rules every kind of rule as it stands now */
    public record RulesOutput(List<RuleState> rules) {}

    public record RuleState(String ruleKey, boolean enabled, boolean billable, int sortOrder) {}

    /** @param dailyPrice JPY a day; a rule not listed costs 0 */
    public record PriceInput(@NotBlank @Size(max = 32) String ruleKey,
        @NotNull @Min(0) @Max(QbAudiencePricing.MAX_PRICE) Long dailyPrice) {}

    /**
     * @param owner      the operator whose list it is; headquarters only (required), the operator's own process takes
     *                   the caller's operator
     * @param minimumFee the least a publication using a charged rule pays, JPY
     * @param prices     the whole list: rules not given cost 0
     * @param reason     why it changes (the audit keeps it)
     */
    public record PricesInput(@Size(max = 64) String owner,
        @NotNull @Min(0) @Max(QbAudiencePricing.MAX_PRICE) Long minimumFee,
        @NotNull @Size(max = 50) List<@Valid @NotNull PriceInput> prices,
        @NotBlank @Size(max = QbAudiencePricing.REASON_LENGTH) String reason) {}

    public record PricesOutput(String owner, String priceListId, BigDecimal minimumFee,
        Map<String, BigDecimal> dailyPrices) {}

    public record QuoteInput(@NotNull UUID publicationId) {}

    /**
     * @param quote what the publication would pay now
     * @param rules every kind of rule as the sponsor sees it: whether they may choose it, whether it is charged for,
     *              and its daily price in the list that applies to them (0 without one)
     */
    public record QuoteOutput(AudienceBilling.Quote quote, List<RulePrice> rules) {}

    public record RulePrice(String ruleKey, boolean enabled, boolean billable, int sortOrder, BigDecimal dailyPrice) {}

    private AudiencePricingProcesses() {}

    private static <I, O> void common(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.requiresVerifiedEmail()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            });
        CallerChecks.refuseBanned(pb);
    }

    // ---- the rules ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<RulesInput, RulesOutput, ProcessContext> rulesSet() {
        return ProcessDefinition.define(RULES_SET, 1, RulesInput.class, RulesOutput.class, ProcessContext.class, pb -> {
            pb.description("Opens or closes kinds of audience rule, says which are charged for and orders them.")
                .permissions(QbPermissions.AUDIENCE_RULES_WRITE);
            common(pb);
            pb.outputMapper(ctx -> ctx.get(OUTPUT, RulesOutput.class))
                .step("Take the rules' turn", HoldLock.<ProcessContext>exclusive(ctx -> QbAudiencePricing.RULES_LOCK))
                .step("Load the rules", QueryEntities.<ProcessContext>of(
                    QbAudiencePricing.defaultDataset(QbAudiencePricing.RULE),
                    ctx -> EntityQuery.builder().limit(AudienceCriteria.ALL.size() + 1).build(), RULES))
                .compute("Save them", (metadata, ctx) -> saveRules(ctx));
        });
    }

    static void saveRules(ProcessContext ctx) {
        RulesInput input = ctx.get(INPUT, RulesInput.class);
        Map<String, EntityInstance> rows = byKey(list(ctx, RULES), "ruleKey");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < input.rules().size(); i++) {
            RuleInput rule = input.rules().get(i);
            if (AudienceCriteria.find(rule.ruleKey()).isEmpty()) {
                ctx.reject(new Violation("rules[" + i + "].ruleKey", AudienceCodes.UNKNOWN,
                    "No such rule " + rule.ruleKey(), Map.of()));
            } else if (!seen.add(rule.ruleKey())) {
                ctx.reject(new Violation("rules[" + i + "].ruleKey", AudienceCodes.REPEATED,
                    "The rule " + rule.ruleKey() + " is given twice", Map.of()));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, AudienceBilling.Rule> after = rules(list(ctx, RULES));
        Map<String, Integer> order = order(list(ctx, RULES));
        for (RuleInput rule : input.rules()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("enabled", rule.enabled());
            values.put("billable", rule.billable());
            values.put("sortOrder", BigDecimal.valueOf(rule.sortOrder()));
            values.put("updatedAt", ctx.opTime());
            values.put("updatedBy", ctx.request().actorId());
            EntityInstance row = rows.get(rule.ruleKey());
            if (row == null) {
                values.put("ruleKey", rule.ruleKey());
                ctx.changes().insert(QbAudiencePricing.RULE, values);
            } else {
                ctx.changes().update(QbAudiencePricing.RULE, row.id(), row.version(), values);
            }
            after.put(rule.ruleKey(), new AudienceBilling.Rule(rule.enabled(), rule.billable()));
            order.put(rule.ruleKey(), rule.sortOrder());
        }
        ctx.put(OUTPUT, new RulesOutput(states(after, order)));
    }

    /** Every kind of rule as managed, a kind without a record at its default and its place in the code's list. */
    static List<RuleState> states(Map<String, AudienceBilling.Rule> rules, Map<String, Integer> order) {
        List<RuleState> states = new ArrayList<>();
        for (int i = 0; i < AudienceCriteria.ALL.size(); i++) {
            String key = AudienceCriteria.ALL.get(i).key();
            AudienceBilling.Rule rule = rules.getOrDefault(key, AudienceBilling.Rule.DEFAULT);
            states.add(new RuleState(key, rule.enabled(), rule.billable(), order.getOrDefault(key, (i + 1) * 10)));
        }
        states.sort(java.util.Comparator.comparingInt(RuleState::sortOrder));
        return states;
    }

    /** The managed rules by key. */
    public static Map<String, AudienceBilling.Rule> rules(List<EntityInstance> rows) {
        Map<String, AudienceBilling.Rule> rules = new HashMap<>();
        for (EntityInstance row : rows) {
            rules.put(String.valueOf((Object) row.get("ruleKey")), new AudienceBilling.Rule(
                Boolean.TRUE.equals(row.get("enabled")), Boolean.TRUE.equals(row.get("billable"))));
        }
        return rules;
    }

    private static Map<String, Integer> order(List<EntityInstance> rows) {
        Map<String, Integer> order = new HashMap<>();
        for (EntityInstance row : rows) {
            Object value = row.get("sortOrder");
            order.put(String.valueOf((Object) row.get("ruleKey")), value instanceof Number n ? n.intValue() : 0);
        }
        return order;
    }

    /** The query of every managed rule (at most one per kind). */
    public static QueryEntities.Metadata<ProcessContext> loadRules(String key) {
        return QueryEntities.query(QbAudiencePricing.defaultDataset(QbAudiencePricing.RULE),
            ctx -> EntityQuery.builder().limit(AudienceCriteria.ALL.size() + 1).build(), key);
    }

    // ---- the price lists ---------------------------------------------------------------------------------------------

    public static ProcessDefinition<PricesInput, PricesOutput, ProcessContext> pricesSet() {
        return ProcessDefinition.define(PRICES_SET, 1, PricesInput.class, PricesOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Saves an operator's audience price list (headquarters).")
                    .permissions(QbPermissions.AUDIENCE_PRICES_WRITE);
                common(pb);
                pb.outputMapper(ctx -> ctx.get(OUTPUT, PricesOutput.class))
                    .compute("Whose list", (metadata, ctx) -> {
                        String owner = ctx.get(INPUT, PricesInput.class).owner();
                        ctx.put(OWNER, owner == null ? "" : owner.strip());
                    })
                    .step("Load the operator named", QueryEntities.<ProcessContext>of(
                        QbOperators.defaultDataset(QbOperators.OPERATOR), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("operatorId", owner(ctx).isEmpty()
                                ? List.of() : List.of(owner(ctx)))).limit(1).build(), OPERATOR))
                    .compute("Refuse an unknown owner", (metadata, ctx) -> {
                        if (first(ctx, OPERATOR) == null) {
                            throw new EntityNotFoundException(QbOperators.OPERATOR + " " + owner(ctx) + " not found");
                        }
                    });
                savePrices(pb);
            });
    }

    public static ProcessDefinition<PricesInput, PricesOutput, ProcessContext> operatorPricesSet() {
        return ProcessDefinition.define(OPERATOR_PRICES_SET, 1, PricesInput.class, PricesOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Saves the caller operator's audience price list (its lead).")
                    .permissions(QbPermissions.OPERATOR_USE);
                common(pb);
                pb.outputMapper(ctx -> ctx.get(OUTPUT, PricesOutput.class));
                OperatorChecks.member(pb);
                pb.compute("Only the lead, for their own operator", (metadata, ctx) -> {
                    if (!Boolean.TRUE.equals(ctx.get(OperatorChecks.LEAD))) {
                        throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NOT_LEAD,
                            "Only the operator's lead sets its prices", Map.of()));
                    }
                    ctx.put(OWNER, OperatorChecks.operatorId(ctx));
                });
                savePrices(pb);
            });
    }

    private static <I, O> void savePrices(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Take the owner's turn", HoldLock.<ProcessContext>exclusive(
                ctx -> QbAudiencePricing.priceLockName(owner(ctx))))
            .step("Load the owner's list", QueryEntities.<ProcessContext>of(
                QbAudiencePricing.defaultDataset(QbAudiencePricing.PRICE_LIST), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("owner", owner(ctx))).limit(1).build(), LIST))
            .step("Load its prices", QueryEntities.<ProcessContext>of(
                QbAudiencePricing.defaultDataset(QbAudiencePricing.PRICE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("priceListId", first(ctx, LIST) == null ? List.of()
                        : List.of(String.valueOf(first(ctx, LIST).id())))).limit(51).build(), PRICES))
            .compute("Save the list", (metadata, ctx) -> savePrices(ctx));
    }

    static void savePrices(ProcessContext ctx) {
        PricesInput input = ctx.get(INPUT, PricesInput.class);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < input.prices().size(); i++) {
            String key = input.prices().get(i).ruleKey();
            if (AudienceCriteria.find(key).isEmpty()) {
                ctx.reject(new Violation("prices[" + i + "].ruleKey", AudienceCodes.UNKNOWN, "No such rule " + key,
                    Map.of()));
            } else if (!seen.add(key)) {
                ctx.reject(new Violation("prices[" + i + "].ruleKey", AudienceCodes.REPEATED,
                    "The rule " + key + " is given twice", Map.of()));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        EntityInstance list = first(ctx, LIST);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("minimumFee", BigDecimal.valueOf(input.minimumFee()));
        values.put("reason", input.reason().strip());
        values.put("updatedAt", ctx.opTime());
        values.put("updatedBy", ctx.request().actorId());
        Object listId;
        if (list == null) {
            values.put("owner", owner(ctx));
            listId = ctx.changes().insert(QbAudiencePricing.PRICE_LIST, values);
        } else {
            listId = list.id();
            ctx.changes().update(QbAudiencePricing.PRICE_LIST, list.id(), list.version(), values);
        }
        Map<String, EntityInstance> existing = byKey(list(ctx, PRICES), "ruleKey");
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (PriceInput price : input.prices()) {
            BigDecimal daily = BigDecimal.valueOf(price.dailyPrice());
            prices.put(price.ruleKey(), daily);
            EntityInstance row = existing.remove(price.ruleKey());
            if (row == null) {
                Map<String, Object> insert = new LinkedHashMap<>();
                insert.put("priceListId", String.valueOf(listId));
                insert.put("ruleKey", price.ruleKey());
                insert.put("dailyPrice", daily);
                ctx.changes().insert(QbAudiencePricing.PRICE, insert);
            } else if (BigDecimal.valueOf(price.dailyPrice()).compareTo(money(row.get("dailyPrice"))) != 0) {
                ctx.changes().update(QbAudiencePricing.PRICE, row.id(), row.version(), Map.of("dailyPrice", daily));
            }
        }
        // A rule left out costs nothing: its row goes.
        for (EntityInstance row : existing.values()) {
            ctx.changes().delete(QbAudiencePricing.PRICE, row.id(), row.version());
        }
        ctx.put(OUTPUT, new PricesOutput(owner(ctx), String.valueOf(listId), BigDecimal.valueOf(input.minimumFee()),
            prices));
    }

    // ---- the quote ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<QuoteInput, QuoteOutput, ProcessContext> quote() {
        return ProcessDefinition.define(QUOTE, 1, QuoteInput.class, QuoteOutput.class, ProcessContext.class,
            pb -> pb
                .description("Tells what the caller's publication would pay for its audience rules now.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put("publicationId", input.publicationId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, QuoteOutput.class))
                // The caller's own publication only: 404 for anybody else's.
                .step("Load the publication", LoadEntity.by(QbPublications.sponsorDataset(QbPublications.PUBLICATION),
                    "publicationId", PUBLICATION))
                .step("Load the parameters", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.AUDIENCE_BILLING_ENABLED))
                .steps(AudiencePricingProcesses::loadPricing)
                .compute("Quote", (metadata, ctx) -> ctx.put(OUTPUT, new QuoteOutput(quoteOf(ctx,
                    ctx.get(PUBLICATION, EntityInstance.class)), rulePrices(ctx)))));
    }

    /**
     * The reads a quote needs, in two statements (the price lists' prices depend on the lists): the publication's
     * criteria, the rules, the sponsor's operator and the two price lists; then their prices. The publication is
     * at {@link #PUBLICATION}.
     */
    public static <I, O> void loadPricing(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the criteria, the rules, the operator and the price lists", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbAudience.CRITERION_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("publicationId", List.of(publicationId(ctx))))
                    .limit(QbAudience.MAX_ROWS).build(), CRITERIA),
                loadRules(RULES),
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("userId", sponsorOf(ctx))).limit(1).build(), ASSIGNMENT)))
            .step("Load the price lists", QueryEntities.<ProcessContext>of(
                QbAudiencePricing.defaultDataset(QbAudiencePricing.PRICE_LIST), ctx -> {
                    String operator = operatorOf(ctx);
                    return EntityQuery.builder().where(new QueryPredicate.In("owner",
                        operator == null ? List.of() : List.of(operator))).limit(1).build();
                }, LISTS))
            .step("Load their prices", QueryEntities.<ProcessContext>of(
                QbAudiencePricing.defaultDataset(QbAudiencePricing.PRICE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("priceListId", list(ctx, LISTS).stream()
                        .map(row -> (Object) String.valueOf(row.id())).toList())).limit(101).build(), PRICES));
    }

    /** Every kind of rule with the sponsor's daily price (the list that applies to them). */
    static List<RulePrice> rulePrices(ProcessContext ctx) {
        AudienceBilling.PriceList list = applicableList(ctx);
        List<RulePrice> prices = new ArrayList<>();
        for (RuleState state : states(rules(list(ctx, RULES)), order(list(ctx, RULES)))) {
            prices.add(new RulePrice(state.ruleKey(), state.enabled(), state.billable(), state.sortOrder(),
                list == null ? BigDecimal.ZERO : list.priceOf(state.ruleKey())));
        }
        return prices;
    }

    static AudienceBilling.PriceList applicableList(ProcessContext ctx) {
        String operator = operatorOf(ctx);
        Map<String, AudienceBilling.PriceList> lists = priceLists(list(ctx, LISTS), list(ctx, PRICES));
        return AudienceBilling.applicable(operator, operator == null ? null : lists.get(operator));
    }

    /** The quote of the publication from the reads of {@link #loadPricing} and the parameters at {@link #PARAMS}. */
    public static AudienceBilling.Quote quoteOf(ProcessContext ctx, EntityInstance publication) {
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        boolean enabled = params.find(QbParams.AUDIENCE_BILLING_ENABLED, Boolean.class).orElse(false);
        AudienceBilling.PriceList list = applicableList(ctx);
        List<String> used = list(ctx, CRITERIA).stream().map(row -> String.valueOf((Object) row.get("criterionKey")))
            .toList();
        return AudienceBilling.quote(enabled, used, rules(list(ctx, RULES)), list, instant(publication.get("startAt")),
            instant(publication.get("endAt")));
    }

    public static Map<String, AudienceBilling.PriceList> priceLists(List<EntityInstance> lists, List<EntityInstance> prices) {
        Map<String, AudienceBilling.PriceList> result = new HashMap<>();
        for (EntityInstance list : lists) {
            Map<String, BigDecimal> daily = new HashMap<>();
            for (EntityInstance price : prices) {
                if (String.valueOf(list.id()).equals(String.valueOf((Object) price.get("priceListId")))) {
                    daily.put(String.valueOf((Object) price.get("ruleKey")), money(price.get("dailyPrice")));
                }
            }
            String owner = String.valueOf((Object) list.get("owner"));
            result.put(owner, new AudienceBilling.PriceList(owner, money(list.get("minimumFee")), daily));
        }
        return result;
    }

    private static Object publicationId(ProcessContext ctx) {
        EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
        return publication.id();
    }

    private static String sponsorOf(ProcessContext ctx) {
        EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
        return String.valueOf((Object) publication.get("publisherId"));
    }

    private static String operatorOf(ProcessContext ctx) {
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        return assignment == null ? null : String.valueOf((Object) assignment.get("operatorId"));
    }

    // ---- shared ------------------------------------------------------------------------------------------------------

    private static String owner(ProcessContext ctx) {
        return ctx.get(OWNER, String.class);
    }

    private static Map<String, EntityInstance> byKey(List<EntityInstance> rows, String field) {
        Map<String, EntityInstance> map = new HashMap<>();
        for (EntityInstance row : rows) {
            map.put(String.valueOf((Object) row.get(field)), row);
        }
        return map;
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.contains(key) ? ctx.get(key) : null;
        return value == null ? List.of() : (List<EntityInstance>) value;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = list(ctx, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
