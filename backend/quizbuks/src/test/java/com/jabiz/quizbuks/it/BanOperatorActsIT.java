package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.identity.BanProcesses;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.page.SponsorPageProcesses;
import com.jabiz.quizbuks.page.SponsorProductProcesses;
import com.jabiz.quizbuks.exposure.ExposureProcesses;
import com.jabiz.quizbuks.help.HelpProcesses;
import com.jabiz.quizbuks.prospect.ProspectProcesses;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.region.RegionExceptions;
import com.jabiz.quizbuks.report.ReportProcesses;
import com.jabiz.quizbuks.reward.DigitalPrizeProcesses;
import com.jabiz.quizbuks.reward.RedemptionProcesses;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-ban-coverage (docs/quizbuks/plans/impl/F-ban-coverage.md §4): a banned operator's member, a banned taker, whose
 * access token has not expired yet, is refused by every process they start: 422 {@code QB_USER_BANNED}, before
 * anything is read, and nothing is written. Unbanned, the same calls are refused for no ban. The processes are called
 * with inputs that are valid in shape (the ban comes before anything looks for the records they name); the ones
 * whose success matters (an exception of the region, a ban of a sponsor, the reward methods) are run on real records
 * and work once unbanned.
 */
class BanOperatorActsIT extends CreditNoteItSupport {

    static final String BANNED = "QB_USER_BANNED";

    /** An operator whose lead is banned, the lead's still valid session and headquarters' token. */
    record Banned(NewOperator operator, String lead, String boss) {}

    Banned banned(String label) {
        NewOperator operator = newOperatorWithLead(operatorsAdmin(), unique("Ban acts " + label), null);
        String lead = memberSession(operator.leadId(), operator.operatorId());
        String boss = bossToken();
        run("QB_USER_BAN", boss, body("userId", operator.leadId(), "reason", "Fraud"));
        return new Banned(operator, lead, boss);
    }

    void unban(Banned banned) {
        run(BanProcesses.UNBAN, banned.boss(), body("userId", banned.operator().leadId(), "note", "Cleared"));
    }

    String bossToken() {
        return tokenOf(userWithRole(unique("ban-acts-boss"), QbRoles.ADMIN_SUPER), QbRoles.ADMIN_SUPER);
    }

    /** The process refuses the banned caller with the ban and nothing else. */
    void refusedForTheBan(String process, String token, Map<String, ?> body) {
        assertThat(codes(refused(process, token, body, 422))).as(process).containsExactly(BANNED);
    }

    /** Not the ban: the call may go on to fail for what its input names (it names nothing here), or work. */
    void notRefusedForTheBan(String process, String token, Map<String, ?> body) {
        var result = call(process, token, body).expectBody(MAP).returnResult();
        assertThat(codes(result.getResponseBody())).as(process + " answered " + result.getResponseBody())
            .doesNotContain(BANNED);
    }

    /** Runs {@code calls} and asserts nothing was written meanwhile (every entity write is audited). */
    void writingNothing(Runnable calls) {
        long before = audited();
        calls.run();
        assertThat(audited()).as("audited writes").isEqualTo(before);
    }

    long audited() {
        return ((Number) query("SELECT count(*) AS n FROM sys_audit_record").getFirst().get("n")).longValue();
    }

    static String anId() {
        return UUID.randomUUID().toString();
    }

    // ---- prizes, rewards, help, AI, shared videos ---------------------------------------------------------------

    @Test
    void aBannedMemberVoidsNoPrizeAndAnswersNoHelp() {
        Banned b = banned("prizes");
        Map<String, Object> voiding = body("redemptionId", anId(), "note", "x");
        Map<String, Object> resolving = body("digitalPrizeId", anId(), "resolution", "DELIVERED");
        Map<String, Object> takedown = body("sharedVideoId", anId(), "reason", "Infringing");
        Map<String, Object> reply = body("threadId", anId(), "text", "Hello", "close", false);
        Map<String, Object> check = body("publicationId", anId(), "language", "ja");
        writingNothing(() -> {
            refusedForTheBan(RedemptionProcesses.VOID, b.lead(), voiding);
            refusedForTheBan(DigitalPrizeProcesses.RESOLVE, b.lead(), resolving);
            refusedForTheBan(SharedVideoProcesses.TAKEDOWN, b.lead(), takedown);
            refusedForTheBan(HelpProcesses.REPLY, b.lead(), reply);
            refusedForTheBan(AiProcesses.TRANSLATION_CHECK_REQUEST, b.lead(), check);
        });
        unban(b);
        notRefusedForTheBan(RedemptionProcesses.VOID, b.lead(), voiding);
        notRefusedForTheBan(DigitalPrizeProcesses.RESOLVE, b.lead(), resolving);
        notRefusedForTheBan(SharedVideoProcesses.TAKEDOWN, b.lead(), takedown);
        notRefusedForTheBan(HelpProcesses.REPLY, b.lead(), reply);
        notRefusedForTheBan(AiProcesses.TRANSLATION_CHECK_REQUEST, b.lead(), check);
    }

    // ---- the reward methods -------------------------------------------------------------------------------------

    @Test
    void aBannedMemberSetsNoRewardMethods() {
        Banned b = banned("methods");
        String sponsor = newSponsor(unique("s-ban-methods"));
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", sponsor, "operatorId",
            b.operator().operatorId(), "reason", "Ban acts"));
        Map<String, Object> set = body("userId", sponsor, "methods", List.of("PLATFORM", "SPONSOR_CASH"), "note", "x");
        writingNothing(() -> refusedForTheBan(RewardMethodProcesses.SET, b.lead(), set));
        assertThat(methodsOf(sponsor)).isEmpty();

        unban(b);
        assertThat(run(RewardMethodProcesses.SET, b.lead(), set)).containsEntry("userId", sponsor);
        assertThat(methodsOf(sponsor)).containsExactly("PLATFORM,SPONSOR_CASH");
    }

    /** The plan's §4 addition: a member removed from the team is refused although their token has not expired. */
    @Test
    void aMemberRemovedFromTheTeamSetsNoRewardMethods() {
        NewOperator operator = newOperatorWithLead(operatorsAdmin(), unique("Ban acts removed"), null);
        String sponsor = newSponsor(unique("s-removed-methods"));
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", sponsor, "operatorId", operator.operatorId(),
            "reason", "Ban acts"));
        String member = verifiedUser(unique("ban-acts-staff"));
        run(OperatorProcesses.MEMBER_ADD, operatorsAdmin(), body("operatorId", operator.operatorId(), "userId",
            member, "lead", false));
        String session = memberSession(member, operator.operatorId());
        // A member sets them.
        assertThat(run(RewardMethodProcesses.SET, session, body("userId", sponsor, "methods", List.of("PLATFORM"))))
            .containsEntry("userId", sponsor);

        run(OperatorProcesses.MEMBER_REMOVE, operatorsAdmin(), body("userId", member));
        writingNothing(() -> assertThat(codes(refused(RewardMethodProcesses.SET, session, body("userId", sponsor,
            "methods", List.of("PLATFORM", "OFFLINE_REDEEM")), 422))).containsExactly(OperatorCodes.NOT_MEMBER));
        assertThat(methodsOf(sponsor)).containsExactly("PLATFORM");
    }

    static List<Object> methodsOf(String sponsor) {
        return query("SELECT methods FROM qb_sponsor_reward_methods_version WHERE user_id = ? ORDER BY version_no",
            sponsor).stream().map(row -> row.get("methods")).toList();
    }

    // ---- prospecting --------------------------------------------------------------------------------------------

    @Test
    void aBannedMemberDoesNotProspect() {
        Banned b = banned("prospect");
        Map<String, Object> settings = body("enabled", true, "dailyCap", 5, "minScore", 10, "monthlyCostCap", 10,
            "senderName", "Ops", "senderAddress", "ops@example.org", "senderContact", "Ops, 1 Main Street");
        Map<String, Object> direction = body("name", "Bakeries", "brief", "Bakeries that run quizzes", "active", true,
            "perSearch", 5);
        Map<String, Object> approve = body("prospectId", anId(), "mailSubject", "Hello", "mailBody", "Body",
            "baseRevision", 1);
        Map<String, Object> discard = body("prospectId", anId(), "block", false);
        Map<String, Object> block = body("kind", "EMAIL", "value", "no@example.org", "reason", "Asked");
        Map<String, Object> server = body("host", "smtp.example.org", "port", 587, "tls", "STARTTLS", "username", "u",
            "password", "p", "fromAddress", "ops@example.org");
        writingNothing(() -> {
            refusedForTheBan(ProspectProcesses.SETTINGS_SAVE, b.lead(), settings);
            refusedForTheBan(ProspectProcesses.DIRECTION_SAVE, b.lead(), direction);
            refusedForTheBan(ProspectProcesses.APPROVE, b.lead(), approve);
            refusedForTheBan(ProspectProcesses.DISCARD, b.lead(), discard);
            refusedForTheBan(ProspectProcesses.BLOCK_ADD, b.lead(), block);
            refusedForTheBan(ProspectProcesses.MAIL_SERVER_SET, b.lead(), server);
            refusedForTheBan(ProspectProcesses.MAIL_SERVER_REMOVE, b.lead(), body());
        });
        unban(b);
        notRefusedForTheBan(ProspectProcesses.SETTINGS_SAVE, b.lead(), settings);
        notRefusedForTheBan(ProspectProcesses.DIRECTION_SAVE, b.lead(), direction);
        notRefusedForTheBan(ProspectProcesses.APPROVE, b.lead(), approve);
        notRefusedForTheBan(ProspectProcesses.DISCARD, b.lead(), discard);
        notRefusedForTheBan(ProspectProcesses.BLOCK_ADD, b.lead(), block);
        notRefusedForTheBan(ProspectProcesses.MAIL_SERVER_REMOVE, b.lead(), body());
    }

    // ---- exceptions for foreign videos --------------------------------------------------------------------------

    @Test
    void aBannedMemberGivesAndChangesNoRegionException() {
        Banned b = banned("region");
        String sponsor = newSponsor(unique("s-ban-region"));
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", sponsor, "operatorId",
            b.operator().operatorId(), "reason", "Ban acts"));
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Map<String, Object> add = body("kind", "SPONSOR", "sponsorId", sponsor, "reason", "Partner with a licence");
        writingNothing(() -> refusedForTheBan(RegionExceptions.ADD, b.lead(), add));
        assertThat(exceptionsOf(sponsor)).isZero();

        // One that exists, made by headquarters: the banned member can neither change nor end it.
        String exceptionId = (String) run(RegionExceptions.ADD, regionAdmin(), add).get("exceptionId");
        Map<String, Object> expiry = body("exceptionId", exceptionId, "expiresOn", today.plusDays(30).toString(),
            "reason", "Longer");
        Map<String, Object> revoke = body("exceptionId", exceptionId, "reason", "Gone");
        writingNothing(() -> {
            refusedForTheBan(RegionExceptions.EXPIRY_SET, b.lead(), expiry);
            refusedForTheBan(RegionExceptions.REVOKE, b.lead(), revoke);
        });
        assertThat(query("SELECT revoked FROM qb_region_exception WHERE exception_id = ?", exceptionId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("revoked", false));

        unban(b);
        assertThat(run(RegionExceptions.EXPIRY_SET, b.lead(), expiry)).containsEntry("exceptionId", exceptionId);
        assertThat(run(RegionExceptions.REVOKE, b.lead(), revoke)).containsEntry("revoked", true);
        assertThat(run(RegionExceptions.ADD, b.lead(), add)).containsKey("exceptionId");
    }

    static long exceptionsOf(String sponsor) {
        return ((Number) query("SELECT count(*) AS n FROM qb_region_exception WHERE owner_id = ?", sponsor)
            .getFirst().get("n")).longValue();
    }

    String regionAdmin() {
        return com.jabiz.runtime.test.TestTokens.bearer(tokens, "ban-acts-region",
            com.jabiz.quizbuks.QbPermissions.ADMIN_REGION_WRITE);
    }

    // ---- the operator's acts on publications, pages, products, reports and bans ---------------------------------

    @Test
    void aBannedMemberTakesNothingDownAndBansNoOne() {
        Banned b = banned("acts");
        String sponsor = newSponsor(unique("s-ban-acts"));
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", sponsor, "operatorId",
            b.operator().operatorId(), "reason", "Ban acts"));
        Map<String, Object> publication = body("publicationId", anId(), "reason", "Misleading");
        Map<String, Object> page = body("userId", sponsor, "reason", "Misleading");
        Map<String, Object> product = body("productId", anId(), "reason", "Misleading");
        Map<String, Object> restore = body("productId", anId());
        Map<String, Object> report = body("publicationId", anId(), "outcome", "DISMISSED");
        Map<String, Object> ban = body("userId", sponsor, "reason", "Spam");
        writingNothing(() -> {
            refusedForTheBan(PublicationAdmin.OPERATOR_PAUSE, b.lead(), publication);
            refusedForTheBan(PublicationAdmin.OPERATOR_RESUME, b.lead(), publication);
            refusedForTheBan(PublicationAdmin.OPERATOR_END, b.lead(), publication);
            refusedForTheBan(SponsorPageProcesses.OPERATOR_TAKEDOWN, b.lead(), page);
            refusedForTheBan(SponsorProductProcesses.OPERATOR_TAKEDOWN, b.lead(), product);
            refusedForTheBan(SponsorProductProcesses.OPERATOR_RESTORE, b.lead(), restore);
            refusedForTheBan(ReportProcesses.OPERATOR_RESOLVE, b.lead(), report);
            refusedForTheBan(BanProcesses.OPERATOR_BAN, b.lead(), ban);
            refusedForTheBan(BanProcesses.OPERATOR_UNBAN, b.lead(), body("userId", sponsor, "note", "x"));
        });
        assertThat(query("SELECT 1 FROM qb_user_ban_version WHERE user_id = ?::uuid", sponsor)).isEmpty();

        unban(b);
        notRefusedForTheBan(PublicationAdmin.OPERATOR_PAUSE, b.lead(), publication);
        notRefusedForTheBan(PublicationAdmin.OPERATOR_RESUME, b.lead(), publication);
        notRefusedForTheBan(PublicationAdmin.OPERATOR_END, b.lead(), publication);
        notRefusedForTheBan(SponsorPageProcesses.OPERATOR_TAKEDOWN, b.lead(), page);
        notRefusedForTheBan(SponsorProductProcesses.OPERATOR_TAKEDOWN, b.lead(), product);
        notRefusedForTheBan(SponsorProductProcesses.OPERATOR_RESTORE, b.lead(), restore);
        notRefusedForTheBan(ReportProcesses.OPERATOR_RESOLVE, b.lead(), report);
        assertThat(run(BanProcesses.OPERATOR_BAN, b.lead(), ban)).containsEntry("banned", true);
        assertThat(run(BanProcesses.OPERATOR_UNBAN, b.lead(), body("userId", sponsor, "note", "Cleared")))
            .containsEntry("banned", false);
    }

    // ---- the knowledge graphs -----------------------------------------------------------------------------------

    @Test
    void aBannedMemberCopiesAdoptsAndRetiresNoGraph() {
        Banned b = banned("trees");
        Map<String, Object> copy = body("templateTreeId", anId());
        Map<String, Object> adopt = body("treeId", anId(), "nodeIds", List.of("a"));
        Map<String, Object> retire = body("treeId", anId());
        writingNothing(() -> {
            refusedForTheBan(TreeProcesses.COPY, b.lead(), copy);
            refusedForTheBan(TreeProcesses.ADOPT, b.lead(), adopt);
            refusedForTheBan(TreeProcesses.RETIRE, b.lead(), retire);
        });
        unban(b);
        notRefusedForTheBan(TreeProcesses.COPY, b.lead(), copy);
        notRefusedForTheBan(TreeProcesses.ADOPT, b.lead(), adopt);
        notRefusedForTheBan(TreeProcesses.RETIRE, b.lead(), retire);
    }

    // ---- the taker ----------------------------------------------------------------------------------------------

    /** A banned taker's App reports no exposure: it counts towards a Sponsor's fee and the monitoring. */
    @Test
    void aBannedTakerReportsNoExposure() {
        String taker = newTaker(unique("t-ban-exposure"), "JP");
        String token = taker(taker);
        Map<String, Object> report = body("reports", List.of(body("publicationId", anId(), "kind", "OPEN",
            "surface", "VERTICAL")));
        run("QB_USER_BAN", bossToken(), body("userId", taker, "reason", "Fraud"));
        writingNothing(() -> refusedForTheBan(ExposureProcesses.REPORT, token, report));
        run(BanProcesses.UNBAN, bossToken(), body("userId", taker, "note", "Cleared"));
        assertThat(run(ExposureProcesses.REPORT, token, report)).containsKey("results");
    }
}
