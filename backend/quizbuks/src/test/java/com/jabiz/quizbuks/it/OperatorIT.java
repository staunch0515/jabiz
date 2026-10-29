package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.security.SignInGuards;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.security.SignInAttempt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operators (docs/quizbuks/plans/Q48-operators.md, Q48a): headquarters creates them and names their members; the
 * entry admits current members of running operators only; members take publications down and ban sponsors, and
 * headquarters' takedowns and bans come first; the lead removes members; headquarters spot-checks the reviews they
 * decided; sponsors move between operators with a reason.
 */
class OperatorIT extends PlayItSupport {

    @Autowired
    SignInGuards guards;

    /** Headquarters: a super administrator's operator permissions (a token: the processes check them alone). */
    private String hq() {
        return TestTokens.bearer(tokens, "q48-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ,
            QbPermissions.SPOT_CHECK, QbPermissions.ADMIN_CONTENT_WRITE, QbPermissions.ADMIN_USERS_BAN);
    }

    /** A member's token, with the role's permissions. */
    private String member(String userId) {
        return tokenOf(userId, QbRoles.OPERATOR);
    }

    /** A user whose address is verified: a new version of the platform's user, as the sign-up's verification writes. */
    private String verifiedUser(String name) {
        String userId = createUser(name);
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        return userId;
    }

    private String newOperator(String name) {
        return (String) run(OperatorProcesses.SAVE, hq(), body("name", name, "contactName", "Owner of " + name,
            "contactEmail", "ops@" + name.toLowerCase() + ".example")).get("operatorId");
    }

    private String newMember(String operatorId, String name, boolean lead) {
        String userId = verifiedUser(name);
        assertThat(run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operatorId, "userId", userId,
            "lead", lead))).containsEntry("changed", true);
        return userId;
    }

    private boolean admitted(String userId) {
        return asTestRequest(guards.check(userId, "u", QbOperators.ENTRY, Set.of(QbRoles.OPERATOR), true,
            SignInAttempt.Factor.TOTP, clock.instant())).block().allowed();
    }

    private static long count(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private Map<String, Object> publication(String publicationId) {
        return latest("qb_publication_version", "publication_id", "publication_id = '" + publicationId + "'")
            .getFirst();
    }

    @Test
    void headquartersCreatesAnOperatorAndNamesItsMembers() {
        String operatorId = newOperator(unique("Kansai"));
        assertThat(items("qb.admin.operators", hq(), Map.of("params", Map.of("operatorId", operatorId))))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", QbOperators.ACTIVE)
                .containsEntry("collection", QbOperators.PLATFORM).satisfies(r -> assertThat(count(r, "members"))
                    .isZero()));

        // A change needs the revision the page read; the address must be one.
        assertThat(codes(refused(OperatorProcesses.SAVE, hq(), body("operatorId", operatorId, "name", "Kansai",
            "contactName", "Owner", "contactEmail", "ops@kansai.example"), 422))).containsExactly(OperatorCodes.CHANGED);
        assertThat(codes(refused(OperatorProcesses.SAVE, hq(), body("name", "X", "contactName", "Y",
            "contactEmail", "not an address"), 422))).containsExactly(OperatorCodes.FIELD);
        // Only headquarters: an operator's member does not create operators.
        String lead = newMember(operatorId, unique("q48-lead"), true);
        refused(OperatorProcesses.SAVE, member(lead), body("name", "Mine", "contactName", "Me",
            "contactEmail", "me@example.com"), 403);

        // Who cannot be named: an unverified user, a sponsor, a member of another operator.
        assertThat(codes(refused(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operatorId,
            "userId", createUser(unique("q48-unverified")), "lead", false), 422)))
            .containsExactly(OperatorCodes.MEMBER_UNVERIFIED);
        String sponsor = newSponsor(unique("s-q48-member"));
        assertThat(codes(refused(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operatorId,
            "userId", sponsor, "lead", false), 422))).contains(OperatorCodes.MEMBER_IS_SPONSOR);
        String other = newOperator(unique("Kyushu"));
        assertThat(codes(refused(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", other, "userId", lead,
            "lead", false), 422))).containsExactly(OperatorCodes.MEMBER_ELSEWHERE);

        // The team as a member reads it; naming again only changes the lead.
        String kai = newMember(operatorId, unique("q48-kai"), false);
        Map<String, Object> team = run(OperatorProcesses.TEAM, member(kai), body());
        assertThat(team).containsEntry("operatorId", operatorId).containsEntry("lead", false);
        assertThat((List<?>) team.get("members")).hasSize(2);
        assertThat(run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operatorId, "userId", kai,
            "lead", false))).containsEntry("changed", false);
        assertThat(items("qb.admin.operator-members", hq(), Map.of("params", Map.of("operatorId", operatorId))))
            .hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("hasRole", false));
        // A non-member reads no team.
        assertThat(codes(refused(OperatorProcesses.TEAM, member(verifiedUser(unique("q48-nobody"))), body(), 422)))
            .containsExactly(OperatorCodes.NOT_MEMBER);
    }

    @Test
    void theEntryAdmitsCurrentMembersOfRunningOperatorsOnly() {
        String operatorId = newOperator(unique("Tohoku"));
        String lead = newMember(operatorId, unique("q48-tohoku-lead"), true);
        String mei = newMember(operatorId, unique("q48-mei"), false);
        assertThat(admitted(mei)).isTrue();
        assertThat(admitted(verifiedUser(unique("q48-outsider")))).isFalse();

        // The lead removes a member: out of the entry and of the acts at once. A lead does not remove themself.
        assertThat(run(OperatorProcesses.MEMBER_DROP, member(lead), body("userId", mei)))
            .containsEntry("changed", true);
        assertThat(admitted(mei)).isFalse();
        assertThat(codes(refused(OperatorProcesses.TEAM, member(mei), body(), 422)))
            .containsExactly(OperatorCodes.NOT_MEMBER);
        assertThat(codes(refused(OperatorProcesses.MEMBER_DROP, member(lead), body("userId", lead), 422)))
            .containsExactly(OperatorCodes.LEAD_SELF);
        // A member who does not lead removes nobody; another operator's member is not found.
        String ren = newMember(operatorId, unique("q48-ren"), false);
        assertThat(codes(refused(OperatorProcesses.MEMBER_DROP, member(ren), body("userId", lead), 422)))
            .containsExactly(OperatorCodes.NOT_LEAD);
        String otherLead = newMember(newOperator(unique("Hokuriku")), unique("q48-other-lead"), true);
        refused(OperatorProcesses.MEMBER_DROP, member(otherLead), body("userId", ren), 404);

        // Suspended: its members do not get in or act; active again, they do.
        Map<String, Object> operator = items("qb.admin.operators", hq(),
            Map.of("params", Map.of("operatorId", operatorId))).getFirst();
        run(OperatorProcesses.SAVE, hq(), body("operatorId", operatorId, "name", operator.get("name"),
            "contactName", operator.get("contactName"), "contactEmail", operator.get("contactEmail"),
            "status", QbOperators.SUSPENDED, "baseRevision", operator.get("revision")));
        assertThat(admitted(ren)).isFalse();
        assertThat(codes(refused(OperatorProcesses.TEAM, member(ren), body(), 422)))
            .containsExactly(OperatorCodes.SUSPENDED);
        run(OperatorProcesses.SAVE, hq(), body("operatorId", operatorId, "name", operator.get("name"),
            "contactName", operator.get("contactName"), "contactEmail", operator.get("contactEmail"),
            "status", QbOperators.ACTIVE, "baseRevision", 2));
        assertThat(admitted(ren)).isTrue();

        // Headquarters removes anyone; a ban keeps them out whatever their membership.
        assertThat(run(OperatorProcesses.MEMBER_REMOVE, hq(), body("userId", ren))).containsEntry("changed", true);
        assertThat(admitted(ren)).isFalse();
    }

    @Test
    void anOperatorTakesDownAndResumesItsOwnButNotHeadquarters() {
        String operatorId = newOperator(unique("Chubu"));
        String kai = member(newMember(operatorId, unique("q48-chubu"), false));
        String sponsor = newSponsor(unique("s-q48-takedown"));
        credit(sponsor, 1_000);
        String mine = livePublication(sponsor, "Q48 operator takedown");
        String theirs = livePublication(sponsor, "Q48 HQ takedown");

        run(PublicationAdmin.OPERATOR_PAUSE, kai, body("publicationId", mine, "reason", "Misleading prize"));
        assertThat(publication(mine)).containsEntry("status", "PAUSED").containsEntry("paused_by", "ADMIN")
            .containsEntry("takedown_operator_id", operatorId);
        // The sponsor cannot resume an operator's takedown; the operator can.
        assertThat(codes(refused("QB_PUBLICATION_RESUME", sponsorToken(sponsor), body("publicationId", mine), 422)))
            .containsExactly(PublishingCodes.ADMIN_PAUSED);
        assertThat(run(PublicationAdmin.OPERATOR_RESUME, kai, body("publicationId", mine, "reason", "Fixed")))
            .containsEntry("status", "LIVE");
        assertThat(publication(mine)).containsEntry("takedown_operator_id", null);

        // Headquarters' takedown: the operator neither resumes nor ends it.
        run(PublicationAdmin.PAUSE, hq(), body("publicationId", theirs, "reason", "Legal"));
        assertThat(codes(refused(PublicationAdmin.OPERATOR_RESUME, kai, body("publicationId", theirs,
            "reason", "Mine now"), 422))).containsExactly(OperatorCodes.HQ_TAKEDOWN);
        assertThat(codes(refused(PublicationAdmin.OPERATOR_END, kai, body("publicationId", theirs,
            "reason", "Over"), 422))).containsExactly(OperatorCodes.HQ_TAKEDOWN);
        // Headquarters' pause of an operator's takedown makes it headquarters'.
        run(PublicationAdmin.OPERATOR_PAUSE, kai, body("publicationId", mine, "reason", "Again"));
        run(PublicationAdmin.PAUSE, hq(), body("publicationId", mine, "reason", "Ours"));
        assertThat(publication(mine)).containsEntry("takedown_operator_id", null).containsEntry("admin_reason", "Ours");
        assertThat(codes(refused(PublicationAdmin.OPERATOR_RESUME, kai, body("publicationId", mine,
            "reason", "Back"), 422))).containsExactly(OperatorCodes.HQ_TAKEDOWN);

        // Not a member, not an act: the role alone is not enough.
        assertThat(codes(refused(PublicationAdmin.OPERATOR_PAUSE, member(verifiedUser(unique("q48-role-only"))),
            body("publicationId", theirs, "reason", "x"), 422))).containsExactly(OperatorCodes.NOT_MEMBER);
        // The operator's role does not run headquarters' takedowns.
        refused(PublicationAdmin.PAUSE, kai, body("publicationId", theirs, "reason", "x"), 403);
    }

    @Test
    void anOperatorBansSponsorsAndLiftsOnlyItsOwnBans() {
        String operatorId = newOperator(unique("Shikoku"));
        String kai = member(newMember(operatorId, unique("q48-shikoku"), false));
        String sponsor = newSponsor(unique("s-q48-ban"));
        credit(sponsor, 1_000);
        String running = livePublication(sponsor, "Q48 banned");

        // Only sponsors.
        String taker = newTaker(unique("t-q48-ban"), "JP");
        assertThat(codes(refused(com.jabiz.quizbuks.identity.BanProcesses.OPERATOR_BAN, kai, body("userId", taker,
            "reason", "Spam"), 422))).containsExactly(OperatorCodes.NOT_A_SPONSOR);

        run(com.jabiz.quizbuks.identity.BanProcesses.OPERATOR_BAN, kai, body("userId", sponsor, "reason", "Fraud"));
        assertThat(banOf(sponsor)).containsEntry("banned", true).containsEntry("operator_id", operatorId);
        assertThat(publication(running)).containsEntry("status", "PAUSED")
            .containsEntry("takedown_operator_id", operatorId);
        assertThat(items("qb.operator.sponsors", kai, Map.of("params", Map.of("userId", sponsor))))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("banned", true)
                .containsEntry("bannedByOperator", true).containsEntry("mine", false));
        run(com.jabiz.quizbuks.identity.BanProcesses.OPERATOR_UNBAN, kai, body("userId", sponsor));
        assertThat(banOf(sponsor)).containsEntry("banned", false);

        // Headquarters' ban: the operator cannot lift it; headquarters' ban of the operator's makes it theirs.
        run("QB_USER_BAN", hq(), body("userId", sponsor, "reason", "Legal"));
        assertThat(codes(refused(com.jabiz.quizbuks.identity.BanProcesses.OPERATOR_UNBAN, kai,
            body("userId", sponsor), 422))).containsExactly(OperatorCodes.HQ_BAN);
        run("QB_USER_UNBAN", hq(), body("userId", sponsor));
        run(com.jabiz.quizbuks.identity.BanProcesses.OPERATOR_BAN, kai, body("userId", sponsor, "reason", "Again"));
        run("QB_USER_BAN", hq(), body("userId", sponsor, "reason", "Ours"));
        assertThat(banOf(sponsor)).containsEntry("operator_id", null).containsEntry("reason", "Ours");
        assertThat(codes(refused("QB_USER_BAN", hq(), body("userId", sponsor, "reason", "Twice"), 422)))
            .containsExactly(AccountCodes.BAN_STATE);
    }

    @Test
    void sponsorsMoveBetweenOperatorsAndHeadquartersSpotChecksTheirReviews() {
        String operatorId = newOperator(unique("Kanto"));
        String reviewerId = newMember(operatorId, unique("q48-kanto"), false);
        String reviewer = member(reviewerId);
        String sponsor = newSponsor(unique("s-q48-assign"));

        // Headquarters' until assigned; the operator sees it as not theirs.
        assertThat(items("qb.operator.sponsors", reviewer, Map.of("params", Map.of("userId", sponsor))))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("mine", false)
                .containsEntry("operatorId", null));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId,
            "reason", "Signed with Kanto"));
        assertThat(items("qb.operator.sponsors", reviewer, Map.of("params", Map.of("userId", sponsor))))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("mine", true));
        assertThat(codes(refused(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId,
            "reason", "Again"), 422))).containsExactly(OperatorCodes.SAME_OPERATOR);
        assertThat(codes(refused(OperatorProcesses.ASSIGN, hq(), body("userId", newTaker(unique("t-q48-assign"), "JP"),
            "operatorId", operatorId, "reason", "No"), 422))).containsExactly(OperatorCodes.NOT_A_SPONSOR);
        assertThat(items("qb.operator.overview", reviewer, Map.of())).singleElement()
            .satisfies(row -> assertThat(count(row, "mySponsors")).isEqualTo(1));
        // Back to headquarters.
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "reason", "Contract ended"));
        assertThat(query("SELECT 1 FROM qb_sponsor_operator WHERE user_id = ?", sponsor)).isEmpty();

        // A member reviews a sponsor's application: final, and on headquarters' list of the operators' reviews.
        String applicant = user(unique("s-q48-applicant"));
        Map<String, Object> applied = apply(sponsor(applicant), "Applicant Co");
        assertThat(applied).containsEntry("reviewStatus", "PENDING");
        run("APPROVAL_DECIDE", reviewer, body("requestId", applied.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        assertThat(latest("qb_sponsor_version", "onboarding_id", "user_id = '" + applicant + "'")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("review_status", "APPROVED"));
        List<Map<String, Object>> reviews = items("qb.admin.operator-reviews", hq(),
            Map.of("params", Map.of("operatorId", operatorId, "unchecked", true)));
        assertThat(reviews).singleElement().satisfies(row -> assertThat(row).containsEntry("subject", "qb.sponsor")
            .containsEntry("decision", "APPROVE").containsEntry("title", "Applicant Co")
            .containsEntry("sponsorId", applicant).containsEntry("approverId", reviewerId));
        String decisionId = (String) reviews.getFirst().get("decisionId");

        // A problem needs a note; checked, it leaves the unchecked list.
        assertThat(codes(refused(OperatorProcesses.SPOT_CHECK, hq(), body("decisionId", decisionId,
            "result", QbOperators.CHECK_PROBLEM), 422))).containsExactly(OperatorCodes.SPOT_CHECK_NOTE);
        assertThat(run(OperatorProcesses.SPOT_CHECK, hq(), body("decisionId", decisionId, "result",
            QbOperators.CHECK_PROBLEM, "note", "Company not verified"))).containsEntry("operatorId", operatorId);
        assertThat(items("qb.admin.operator-reviews", hq(),
            Map.of("params", Map.of("operatorId", operatorId, "unchecked", true)))).isEmpty();
        run(OperatorProcesses.SPOT_CHECK, hq(), body("decisionId", decisionId, "result", QbOperators.CHECK_OK));
        assertThat(query("SELECT result FROM qb_review_spot_check WHERE decision_id = ?", decisionId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("result", QbOperators.CHECK_OK));
        // Headquarters' own decisions are not the operators'.
        String hqApplicant = user(unique("s-q48-hq-applicant"));
        Map<String, Object> hqApplied = apply(sponsor(hqApplicant), "HQ Co");
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", hqApplied.get("approvalRequestId"),
            "decision", "APPROVE"));
        String hqDecision = String.valueOf(query("SELECT decision_id FROM sys_approval_decision WHERE request_id = "
            + "?::uuid", hqApplied.get("approvalRequestId")).getFirst().get("decision_id"));
        assertThat(codes(refused(OperatorProcesses.SPOT_CHECK, hq(), body("decisionId", hqDecision,
            "result", QbOperators.CHECK_OK), 422))).containsExactly(OperatorCodes.NOT_AN_OPERATOR_DECISION);
        // Members do not spot-check.
        refused(OperatorProcesses.SPOT_CHECK, reviewer, body("decisionId", decisionId, "result", "OK"), 403);
    }
}
