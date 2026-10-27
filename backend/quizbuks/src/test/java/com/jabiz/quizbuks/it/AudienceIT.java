package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.audience.AudienceCodes;
import com.jabiz.quizbuks.audience.AudienceMatch;
import com.jabiz.quizbuks.audience.QbAudience;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Customer matching (docs/quizbuks/plans/Q39-audience.md): what takers tell of themselves, a draft's conditions, the
 * feed, the page and the start that follow them, and the count a sponsor is given: rounded, of their own drafts only,
 * agreeing with {@link AudienceMatch}.
 */
class AudienceIT extends PlayItSupport {

    @Test
    void aTakersAnswersReplaceWhatTheyToldAndAreTheirsAlone() {
        String taker = newTaker("t-attr-" + UUID.randomUUID(), "JP");
        String token = taker(taker);
        assertThat(run("QB_PROFILE_ATTRIBUTES_SAVE", token, answers(
            answer("gender", List.of("FEMALE"), null), answer("age", null, 1995),
            answer("interest", List.of("FOOD", "TRAVEL"), null)))).containsEntry("answered", 3);
        assertThat(rows(QbAudience.MY_ATTRIBUTE_DATASET, token)).extracting(row -> row.get("criterionKey") + "="
            + (row.get("valueCode") != null ? row.get("valueCode") : row.get("valueNumber")))
            .containsExactlyInAnyOrder("gender=FEMALE", "age=1995", "interest=FOOD", "interest=TRAVEL");
        // The whole of it each time: a criterion not given is cleared, an option dropped is removed.
        assertThat(run("QB_PROFILE_ATTRIBUTES_SAVE", token, answers(
            answer("interest", List.of("TRAVEL", "PETS"), null)))).containsEntry("answered", 1);
        assertThat(rows(QbAudience.MY_ATTRIBUTE_DATASET, token)).extracting(row -> row.get("valueCode"))
            .containsExactlyInAnyOrder("TRAVEL", "PETS");
        // Every problem at once; nothing written.
        assertThat(codes(refused("QB_PROFILE_ATTRIBUTES_SAVE", token, answers(
            answer("gender", List.of("FEMALE", "MALE"), null), answer("age", null, 2020),
            answer("religion", List.of("X"), null)), 422)))
            .containsExactlyInAnyOrder(AudienceCodes.ONE_OPTION, AudienceCodes.BIRTH_YEAR, AudienceCodes.UNKNOWN);
        assertThat(rows(QbAudience.MY_ATTRIBUTE_DATASET, token)).hasSize(2);
        // An age is a birth year, never options; a null option is a problem, not an error.
        assertThat(codes(refused("QB_PROFILE_ATTRIBUTES_SAVE", token, answers(answer("age", List.of("X"), null),
            answer("interest", java.util.Arrays.asList("FOOD", null), null)), 422)))
            .containsExactlyInAnyOrder(AudienceCodes.BIRTH_YEAR, AudienceCodes.OPTION);
        // What was told stays out of the audit trail and the process's summary.
        assertThat(query("SELECT changes FROM sys_audit_record WHERE entity_type = 'QbProfileAttribute' AND actor_id = ?",
            taker)).isNotEmpty().allSatisfy(row -> assertThat(String.valueOf(row.get("changes")))
                .doesNotContain("TRAVEL", "PETS", "FEMALE", "1995"));
        assertThat(query("SELECT CAST(input_summary AS text) AS s FROM op_process WHERE process_name = ? AND actor_id = ?",
            "QB_PROFILE_ATTRIBUTES_SAVE", taker)).isNotEmpty().allSatisfy(row -> assertThat(String.valueOf(
                row.get("s"))).doesNotContain("TRAVEL", "PETS", "FEMALE"));
        // Another taker reads none of it; nobody reads the default dataset, nor writes either.
        assertThat(rows(QbAudience.MY_ATTRIBUTE_DATASET, taker(u2()))).noneMatch(row -> taker.equals(
            row.get("userId")));
        for (String reader : List.of(token, tokenOf("content", QbRoles.ADMIN_CONTENT), tokenOf("finance", QbRoles.ADMIN_FINANCE),
            tokenOf("super", QbRoles.ADMIN_SUPER))) {
            queryDataset(QbAudience.ATTRIBUTE_DATASET, reader).expectStatus().isForbidden();
        }
        assertThat(run("QB_PROFILE_ATTRIBUTES_SAVE", token, answers())).containsEntry("answered", 0);
        assertThat(rows(QbAudience.MY_ATTRIBUTE_DATASET, token)).isEmpty();
    }

    @Test
    void aDraftsConditionsAreReplacedFrozenOnSubmissionAndCopied() {
        String sponsor = newSponsor("s-aud-draft");
        String token = sponsorToken(sponsor);
        String publicationId = draft(token, version(token, "Audience draft quiz"), "Audience draft");
        Map<String, Object> saved = run("QB_PUBLICATION_AUDIENCE_SAVE", token, conditions(publicationId,
            condition("gender", List.of("FEMALE", "OTHER"), null, null), condition("age", null, 20, 39)));
        assertThat(saved).containsEntry("publicationId", publicationId);
        assertThat(criteriaOf(token, publicationId)).containsExactlyInAnyOrder("gender=FEMALE", "gender=OTHER",
            "age=20-39");
        run("QB_PUBLICATION_AUDIENCE_SAVE", token, conditions(publicationId,
            condition("gender", List.of("OTHER"), null, null), condition("interest", List.of("FOOD"), null, null)));
        assertThat(criteriaOf(token, publicationId)).containsExactlyInAnyOrder("gender=OTHER", "interest=FOOD");
        // Every problem at once.
        assertThat(codes(refused("QB_PUBLICATION_AUDIENCE_SAVE", token, conditions(publicationId,
            condition("occupation", List.of("ASTRONAUT"), null, null), condition("age", null, 40, 20)), 422)))
            .containsExactlyInAnyOrder(AudienceCodes.OPTION, AudienceCodes.RANGE);
        // Somebody else's: not found.
        String other = sponsorToken(newSponsor("s-aud-other"));
        refused("QB_PUBLICATION_AUDIENCE_SAVE", other, conditions(publicationId), 404);
        assertThat(rows(QbAudience.SPONSOR_CRITERION_DATASET, other)).noneMatch(row -> publicationId.equals(
            String.valueOf(row.get("publicationId"))));
        // A copy carries them; deleting a draft removes its own.
        String copy = (String) run("QB_PUBLICATION_COPY", token, body("publicationId", publicationId))
            .get("publicationId");
        assertThat(criteriaOf(token, copy)).containsExactlyInAnyOrder("gender=OTHER", "interest=FOOD");
        run("QB_PUBLICATION_DELETE", token, body("publicationId", copy));
        assertThat(query("SELECT count(*) AS n FROM qb_publication_criterion WHERE publication_id = ?::uuid", copy)
            .getFirst()).containsEntry("n", 0L);
        // Submitted, they are frozen.
        submit(token, publicationId);
        assertThat(codes(refused("QB_PUBLICATION_AUDIENCE_SAVE", token, conditions(publicationId), 422)))
            .containsExactly("QB_PUBLICATION_NOT_DRAFT");
        assertThat(criteriaOf(token, publicationId)).hasSize(2);
    }

    @Test
    void anInvitedPublicationTakesNoConditions() {
        String sponsor = newSponsor("s-aud-invited");
        String token = sponsorToken(sponsor);
        String publicationId = draft(token, version(token, "Invited quiz"), "Invited");
        run("QB_PUBLICATION_AUDIENCE_SAVE", token, conditions(publicationId,
            condition("gender", List.of("FEMALE"), null, null)));
        Map<String, Object> save = complete(versionOf(publicationId), "Invited", clock.instant().plus(
            Duration.ofDays(1)), clock.instant().plus(Duration.ofDays(8)));
        save.put("publicationId", publicationId);
        save.put("audience", "INVITED");
        run("QB_PUBLICATION_SAVE", token, save);
        // Switching to the list clears the conditions; a copy has none either.
        assertThat(query("SELECT count(*) AS n FROM qb_publication_criterion WHERE publication_id = ?::uuid",
            publicationId).getFirst()).containsEntry("n", 0L);
        assertThat(codes(refused("QB_PUBLICATION_AUDIENCE_SAVE", token, conditions(publicationId,
            condition("gender", List.of("FEMALE"), null, null)), 422))).containsExactly(AudienceCodes.INVITED);
        assertThat(codes(refused("QB_AUDIENCE_COUNT", token, body("publicationId", publicationId), 422)))
            .containsExactly(AudienceCodes.INVITED);
    }

    @Test
    void theFeedThePageAndTheStartFollowTheTakersProfile() {
        String sponsor = newSponsor("s-aud-play");
        credit(sponsor, 1_000);
        String publicationId = liveWith(sponsor, "Students only", condition("occupation", List.of("STUDENT"), null,
            null), condition("age", null, 18, 25));
        String open = livePublication(sponsor, "For everybody", Map.of("regions", List.of("GLOBAL")));
        String taker = newTaker("t-aud-play-" + UUID.randomUUID(), "JP");
        String token = taker(taker);
        int year = clock.instant().atZone(ZoneOffset.UTC).getYear();
        // Nothing told: the open one only; the page shows it, but it cannot be started.
        assertThat(feedIds(token)).contains(open).doesNotContain(publicationId);
        assertThat(detail(token, publicationId)).containsEntry("matched", false)
            .containsEntry("canStart", false);
        assertThat(codes(refused("QB_ATTEMPT_START", token, body("publicationId", publicationId), 422)))
            .containsExactly(AudienceCodes.NOT_MATCHED);
        // A student, but too old.
        run("QB_PROFILE_ATTRIBUTES_SAVE", token, answers(answer("occupation", List.of("STUDENT"), null),
            answer("age", null, year - 30)));
        assertThat(feedIds(token)).doesNotContain(publicationId);
        // A student of 20: matched.
        run("QB_PROFILE_ATTRIBUTES_SAVE", token, answers(answer("occupation", List.of("STUDENT"), null),
            answer("age", null, year - 20)));
        assertThat(feedIds(token)).contains(publicationId, open);
        assertThat(detail(token, publicationId)).containsEntry("matched", true)
            .containsEntry("canStart", true);
        // The answers of the attempt are not masked by the profile's sensitive input (a distinct component name).
        assertThat(start(token, publicationId)).containsKey("attemptId").containsEntry("answers", List.of());
    }

    @Test
    void theCountIsRoundedAndTheOwnersOnly() {
        String sponsor = newSponsor("s-aud-count");
        String token = sponsorToken(sponsor);
        String publicationId = draft(token, version(token, "Count quiz"), "Count");
        run("QB_PUBLICATION_AUDIENCE_SAVE", token, conditions(publicationId,
            condition("interest", List.of("PETS"), null, null)));
        String marker = UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            String taker = newTaker("t-aud-count-" + marker + "-" + i, "JP");
            run("QB_PROFILE_ATTRIBUTES_SAVE", taker(taker), answers(answer("interest", List.of("PETS"), null)));
        }
        assertThat(run("QB_AUDIENCE_COUNT", token, body("publicationId", publicationId)))
            .containsEntry("count", null).containsEntry("fewerThan", 20);
        refused("QB_AUDIENCE_COUNT", sponsorToken(newSponsor("s-aud-count-other")), body("publicationId",
            publicationId), 404);
        // The exact number is no role's: the template is run by the process only.
        for (String caller : List.of(token, taker(u1()), tokenOf("content", QbRoles.ADMIN_CONTENT),
            tokenOf("super", QbRoles.ADMIN_SUPER))) {
            client.post().uri("/api/queries/qb.system.audience-count").contentType(
                org.springframework.http.MediaType.APPLICATION_JSON).header(
                org.springframework.http.HttpHeaders.AUTHORIZATION, caller).bodyValue(Map.of("params", Map.of(
                "publicationId", publicationId, "at", clock.instant().toString()))).exchange().expectStatus()
                .isForbidden();
        }
    }

    @Test
    void theTemplatesCountAgreesWithTheJavaMatch() {
        String sponsor = newSponsor("s-aud-agree");
        String token = sponsorToken(sponsor);
        int year = clock.instant().atZone(ZoneOffset.UTC).getYear();
        String marker = UUID.randomUUID().toString().substring(0, 8);
        // Takers of every kind: nothing told, part told, all told, out of the age range by a year on either side.
        List<List<Map<String, Object>>> profiles = List.of(
            List.of(),
            List.of(answer("gender", List.of("MALE"), null)),
            List.of(answer("gender", List.of("FEMALE"), null), answer("age", null, year - 30)),
            List.of(answer("gender", List.of("NO_ANSWER"), null), answer("age", null, year - 25),
                answer("interest", List.of("MUSIC", "BOOKS"), null)),
            List.of(answer("gender", List.of("FEMALE"), null), answer("age", null, year - 24),
                answer("interest", List.of("BOOKS"), null)),
            List.of(answer("gender", List.of("FEMALE"), null), answer("age", null, year - 41),
                answer("interest", List.of("BOOKS"), null)),
            List.of(answer("gender", List.of("OTHER"), null), answer("age", null, year - 40),
                answer("interest", List.of("MUSIC"), null), answer("occupation", List.of("RETIRED"), null)));
        for (int i = 0; i < profiles.size(); i++) {
            String taker = newTaker("t-aud-agree-" + marker + "-" + i, "JP");
            run("QB_PROFILE_ATTRIBUTES_SAVE", taker(taker), answers(profiles.get(i).toArray(Map[]::new)));
        }
        List<List<Map<String, Object>>> conditionSets = List.of(
            List.of(),
            List.of(condition("gender", List.of("FEMALE", "NO_ANSWER"), null, null)),
            List.of(condition("age", null, 25, 40)),
            List.of(condition("gender", List.of("FEMALE", "OTHER", "NO_ANSWER"), null, null),
                condition("age", null, 25, 40), condition("interest", List.of("BOOKS", "MUSIC"), null, null)),
            List.of(condition("occupation", List.of("RETIRED"), null, null), condition("age", null, 40, 40)),
            List.of(condition("occupation", List.of("STUDENT"), null, null)));
        String auditor = TestTokens.bearer(tokens, "audience-auditor", QbPermissions.AUDIENCE_SYSTEM);
        for (List<Map<String, Object>> set : conditionSets) {
            String publicationId = draft(token, version(token, "Agree quiz"), "Agree");
            Map<String, Object> input = conditions(publicationId);
            input.put("criteria", set);
            run("QB_PUBLICATION_AUDIENCE_SAVE", token, input);
            Map<String, Object> params = new HashMap<>();
            params.put("publicationId", publicationId);
            params.put("at", clock.instant().toString());
            params.put("publisherId", sponsor);
            Object counted = items("qb.system.audience-count", auditor, Map.of("params", params)).getFirst()
                .get("takers");
            assertThat(((Number) counted).longValue()).as(String.valueOf(set)).isEqualTo(javaCount(publicationId,
                sponsor, year));
        }
    }

    /** The Java match over every complete, unbanned profile (in a GLOBAL publication's reach: all of them). */
    private static long javaCount(String publicationId, String sponsor, int year) {
        List<AudienceMatch.ConditionRow> conditions = query("SELECT criterion_key, value_code, min_value, max_value "
            + "FROM qb_publication_criterion WHERE publication_id = ?::uuid", publicationId).stream()
            .map(row -> new AudienceMatch.ConditionRow((String) row.get("criterion_key"),
                (String) row.get("value_code"), integer(row.get("min_value")), integer(row.get("max_value"))))
            .toList();
        long count = 0;
        for (Map<String, Object> profile : latest("qb_profile_version", "profile_id", "country_code IS NOT NULL")) {
            String userId = (String) profile.get("user_id");
            if (userId.equals(sponsor) || !canAnswer(userId)) {
                continue;
            }
            List<AudienceMatch.ProfileRow> rows = query("SELECT criterion_key, value_code, value_number "
                + "FROM qb_profile_attribute WHERE user_id = ?", userId).stream()
                .map(row -> new AudienceMatch.ProfileRow((String) row.get("criterion_key"),
                    (String) row.get("value_code"), integer(row.get("value_number")))).toList();
            if (AudienceMatch.matches(conditions, rows, year)) {
                count++;
            }
        }
        return count;
    }

    /** An enabled account with a verified address, as the count template asks. */
    private static boolean canAnswer(String userId) {
        List<Map<String, Object>> users = latest("sec_user_version", "user_id", "user_id = '" + userId + "'");
        if (users.isEmpty()) {
            return false;
        }
        Map<String, Object> user = users.getFirst();
        return Boolean.TRUE.equals(user.get("enabled")) && user.get("verified_email") != null
            && String.valueOf(user.get("verified_email")).equalsIgnoreCase(String.valueOf(user.get("email")));
    }

    private static Integer integer(Object value) {
        return value instanceof BigDecimal d ? d.intValueExact() : value == null ? null : ((Number) value).intValue();
    }

    /** A live publication aimed at GLOBAL with the given conditions, set while it is a draft. */
    @SafeVarargs
    private String liveWith(String sponsorId, String name, Map<String, Object>... criteria) {
        String token = sponsorToken(sponsorId);
        String versionId = version(token, name + " quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        save.put("regions", List.of("GLOBAL"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        run("QB_PUBLICATION_AUDIENCE_SAVE", token, conditions(publicationId, criteria));
        assertThat(run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId)).get("status"))
            .isIn("LIVE", "SCHEDULED");
        return publicationId;
    }

    private Map<String, Object> detail(String token, String publicationId) {
        List<Map<String, Object>> rows = items("qb.app.publication", token, Map.of("params", Map.of(
            "publicationId", publicationId, "at", clock.instant().toString(), "country", "JP")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<String> feedIds(String token) {
        return feed(token, "JP", Map.of()).stream().map(row -> String.valueOf(row.get("publicationId"))).toList();
    }

    private List<String> criteriaOf(String token, String publicationId) {
        return rows(QbAudience.SPONSOR_CRITERION_DATASET, token).stream()
            .filter(row -> publicationId.equals(String.valueOf(row.get("publicationId"))))
            .map(row -> row.get("criterionKey") + "=" + (row.get("valueCode") != null ? row.get("valueCode")
                : integer(row.get("minValue")) + "-" + integer(row.get("maxValue")))).toList();
    }

    private String versionOf(String publicationId) {
        return String.valueOf(latest("qb_publication_version", "publication_id", "publication_id = '"
            + publicationId + "'").getFirst().get("version_id"));
    }

    @SafeVarargs
    private static Map<String, Object> answers(Map<String, Object>... answers) {
        return body("profileAnswers", List.of(answers));
    }

    private static Map<String, Object> answer(String key, List<String> options, Integer number) {
        Map<String, Object> answer = new HashMap<>();
        answer.put("key", key);
        answer.put("options", options);
        answer.put("number", number);
        return answer;
    }

    @SafeVarargs
    private static Map<String, Object> conditions(String publicationId, Map<String, Object>... criteria) {
        return body("publicationId", publicationId, "criteria", new ArrayList<>(List.of(criteria)));
    }

    private static Map<String, Object> condition(String key, List<String> options, Integer min, Integer max) {
        Map<String, Object> condition = new HashMap<>();
        condition.put("key", key);
        condition.put("options", options);
        condition.put("min", min);
        condition.put("max", max);
        return condition;
    }
}
