package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.audience.AudienceCodes;
import com.jabiz.quizbuks.audience.AudiencePricingProcesses;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audience rules as a product line (Q47c-1, docs/quizbuks/plans/Q47c-audience-billing.md): headquarters open,
 * close and charge for the kinds of rule; each owner (headquarters for the direct sponsors, an operator's lead for
 * its own) prices them; a sponsor's draft is quoted from the list that applies to them, by the days of its run, never
 * below the least fee, and free while charging is off.
 */
class AudiencePricingIT extends PlayItSupport {

    private String hq() {
        return TestTokens.bearer(tokens, "q47c-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String prices() {
        return TestTokens.bearer(tokens, "q47c-finance", QbPermissions.AUDIENCE_PRICES_WRITE);
    }

    private String rulesAdmin() {
        return TestTokens.bearer(tokens, "q47c-content", QbPermissions.AUDIENCE_RULES_WRITE);
    }

    private String operatorSession(String userId, String operatorId) {
        Actor actor = new Actor(userId, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operatorId));
        return "Bearer " + tokens.issue(actor).token();
    }

    private String newOperator(String name) {
        return (String) run(OperatorProcesses.SAVE, hq(), body("name", name, "contactName", "Owner of " + name,
            "contactEmail", "ops@" + name.toLowerCase() + ".example")).get("operatorId");
    }

    private String member(String operatorId, String name, boolean lead) {
        String userId = createUser(name);
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operatorId, "userId", userId, "lead", lead));
        return userId;
    }

    private void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "Q47c"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    private static Map<String, Object> priceList(String owner, long minimum, Map<String, Long> daily) {
        List<Object> prices = new ArrayList<>();
        daily.forEach((key, price) -> prices.add(body("ruleKey", key, "dailyPrice", price)));
        Map<String, Object> input = body("minimumFee", minimum, "prices", prices, "reason", "Launch prices");
        input.put("owner", owner);
        return input;
    }

    private static Map<String, Object> condition(String key, List<String> options, Integer min, Integer max) {
        Map<String, Object> condition = new HashMap<>();
        condition.put("key", key);
        condition.put("options", options);
        condition.put("min", min);
        condition.put("max", max);
        return condition;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> quote(String token, String publicationId) {
        return (Map<String, Object>) run(AudiencePricingProcesses.QUOTE, token, body("publicationId", publicationId))
            .get("quote");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rulePrices(String token, String publicationId) {
        return (List<Map<String, Object>>) run(AudiencePricingProcesses.QUOTE, token,
            body("publicationId", publicationId)).get("rules");
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    /** A sponsor's seven-day draft that uses gender and age. */
    private String draftUsingGenderAndAge(String token, String name) {
        String publicationId = draft(token, version(token, name + " quiz"), name);
        run("QB_PUBLICATION_AUDIENCE_SAVE", token, body("publicationId", publicationId, "criteria", new ArrayList<>(
            List.of(condition("gender", List.of("FEMALE", "OTHER"), null, null), condition("age", null, 20, 39)))));
        return publicationId;
    }

    @Test
    void aDraftIsQuotedFromTheListThatAppliesByTheDaysOfItsRun() {
        String sponsor = newSponsor(unique("s-q47c-quote"));
        String token = sponsorToken(sponsor);
        String publicationId = draftUsingGenderAndAge(token, "Q47c quote");

        // The class shares one database: start from the rules as shipped and charging off.
        run(AudiencePricingProcesses.RULES_SET, rulesAdmin(), body("rules", List.of(
            body("ruleKey", "gender", "enabled", true, "billable", true, "sortOrder", 10),
            body("ruleKey", "age", "enabled", true, "billable", true, "sortOrder", 20),
            body("ruleKey", "occupation", "enabled", true, "billable", true, "sortOrder", 30),
            body("ruleKey", "interest", "enabled", true, "billable", true, "sortOrder", 40))));
        param(QbParams.AUDIENCE_BILLING_ENABLED, "false");
        param(QbParams.AUDIENCE_PRICE_FALLBACK, "DIRECT");
        // Charging off: free, whatever the prices.
        run(AudiencePricingProcesses.PRICES_SET, prices(), priceList(null, 1000, Map.of("gender", 100L, "age", 200L)));
        assertThat(quote(token, publicationId)).containsEntry("enabled", false).satisfies(q ->
            assertThat(number(q.get("fee"))).isZero());
        assertThat(rulePrices(token, publicationId)).extracting(r -> r.get("ruleKey"))
            .containsExactly("gender", "age", "occupation", "interest");

        param(QbParams.AUDIENCE_BILLING_ENABLED, "true");
        Map<String, Object> q = quote(token, publicationId);
        assertThat(q).containsEntry("enabled", true).containsEntry("owner", "direct").containsEntry("days", 7);
        assertThat(number(q.get("subtotal"))).isEqualTo(2100);
        assertThat(number(q.get("fee"))).isEqualTo(2100);
        assertThat(rulePrices(token, publicationId)).filteredOn(r -> "age".equals(r.get("ruleKey")))
            .singleElement().satisfies(r -> assertThat(number(r.get("dailyPrice"))).isEqualTo(200));

        // An operator's sponsor without the operator's list: headquarters' prices, or free by the fallback.
        String north = newOperator(unique("North"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", north, "reason", "Q47c"));
        assertThat(quote(token, publicationId)).containsEntry("owner", "direct");
        param(QbParams.AUDIENCE_PRICE_FALLBACK, "FREE");
        assertThat(quote(token, publicationId)).satisfies(free -> {
            assertThat(free.get("owner")).isNull();
            assertThat(number(free.get("fee"))).isZero();
        });

        // The operator's lead prices its own; a member who is not the lead cannot; the least fee applies.
        String lead = member(north, unique("q47c-lead"), true);
        String staff = member(north, unique("q47c-staff"), false);
        assertThat(codes(refused(AudiencePricingProcesses.OPERATOR_PRICES_SET, operatorSession(staff, north),
            priceList(null, 0, Map.of("gender", 1L)), 422))).containsExactly(OperatorCodes.NOT_LEAD);
        run(AudiencePricingProcesses.OPERATOR_PRICES_SET, operatorSession(lead, north),
            priceList(null, 500, Map.of("gender", 50L)));
        Map<String, Object> theirs = quote(token, publicationId);
        assertThat(theirs).containsEntry("owner", north);
        // 50 a day for gender, age costs nothing in their list: 350 for 7 days, above the least fee 500? no: 500.
        assertThat(number(theirs.get("subtotal"))).isEqualTo(350);
        assertThat(number(theirs.get("fee"))).isEqualTo(500);
        assertThat(items("qb.operator.audience-prices", operatorSession(lead, north), Map.of())).singleElement()
            .satisfies(row -> assertThat(String.valueOf(row.get("prices"))).contains("\"gender\"").contains("50"));
        // Another operator sees only its own.
        String south = newOperator(unique("South"));
        String southLead = member(south, unique("q47c-south"), true);
        assertThat(items("qb.operator.audience-prices", operatorSession(southLead, south), Map.of())).isEmpty();
        // Headquarters may set an operator's list too; an unknown owner is none.
        run(AudiencePricingProcesses.PRICES_SET, prices(), priceList(north, 0, Map.of("gender", 10L, "age", 10L)));
        assertThat(number(quote(token, publicationId).get("fee"))).isEqualTo(140);
        refused(AudiencePricingProcesses.PRICES_SET, prices(), priceList("no-such-operator", 0, Map.of()), 404);
        assertThat(codes(refused(AudiencePricingProcesses.PRICES_SET, prices(), priceList(null, 0,
            Map.of("astrology", 10L)), 422))).containsExactly(AudienceCodes.UNKNOWN);

        // Another sponsor's publication is none.
        refused(AudiencePricingProcesses.QUOTE, sponsorToken(newSponsor(unique("s-q47c-other"))),
            body("publicationId", publicationId), 404);
        assertThat(items("qb.admin.audience-prices", prices(), Map.of())).extracting(row -> row.get("owner"))
            .contains("direct", north);
    }

    @Test
    void headquartersCloseRulesAndSayWhichAreChargedFor() {
        String sponsor = newSponsor(unique("s-q47c-rules"));
        String token = sponsorToken(sponsor);
        String publicationId = draftUsingGenderAndAge(token, "Q47c rules");
        param(QbParams.AUDIENCE_BILLING_ENABLED, "true");
        run(AudiencePricingProcesses.PRICES_SET, prices(), priceList(null, 0, Map.of("gender", 100L, "age", 200L)));

        Map<String, Object> saved = run(AudiencePricingProcesses.RULES_SET, rulesAdmin(), body("rules", List.of(
            body("ruleKey", "age", "enabled", true, "billable", false, "sortOrder", 5),
            body("ruleKey", "interest", "enabled", false, "billable", true, "sortOrder", 40))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> states = (List<Map<String, Object>>) saved.get("rules");
        assertThat(states).extracting(r -> r.get("ruleKey")).containsExactly("age", "gender", "occupation", "interest");
        // Age is no longer charged for: only gender's 100 a day.
        assertThat(number(quote(token, publicationId).get("fee"))).isEqualTo(700);
        // A closed rule cannot be chosen.
        assertThat(codes(refused("QB_PUBLICATION_AUDIENCE_SAVE", token, body("publicationId", publicationId,
            "criteria", new ArrayList<>(List.of(condition("interest", List.of("FOOD"), null, null)))), 422)))
            .containsExactly(AudienceCodes.RULE_CLOSED);
        assertThat(codes(refused(AudiencePricingProcesses.RULES_SET, rulesAdmin(), body("rules", List.of(
            body("ruleKey", "astrology", "enabled", true, "billable", true, "sortOrder", 1))), 422)))
            .containsExactly(AudienceCodes.UNKNOWN);
        assertThat(items("qb.admin.audience-rules", rulesAdmin(), Map.of())).extracting(r -> r.get("ruleKey"))
            .contains("age", "interest");
        // A sponsor cannot manage them.
        refused(AudiencePricingProcesses.RULES_SET, token, body("rules", List.of()), 403);
    }
}
