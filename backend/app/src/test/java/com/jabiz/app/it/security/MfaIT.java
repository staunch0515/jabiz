package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.security.Base32;
import com.jabiz.security.Totp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second factor, step-up and idle sessions (docs/design/10-security.md sections 9 to 11; decision D28; ROADMAP
 * phase 14g-1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class MfaIT extends SecurityItSupport {

    private static final String PASSWORD = "correct horse battery";

    /** A user with a role, enrolled through the API; returns the TOTP secret. */
    private String enrolled(String name, String... permissions) {
        userWith(name, PASSWORD, permissions);
        String bearer = bearerOf(signIn(name, PASSWORD));
        Map<String, Object> enrollment = post("/api/auth/mfa/enroll", bearer, Map.of())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        String secret = (String) enrollment.get("secret");
        post("/api/auth/mfa/enroll/confirm", bearer, Map.of("code", code(secret))).expectStatus().isOk();
        // The next code: the confirmation used the current one.
        clock.advance(Duration.ofSeconds(30));
        return secret;
    }

    private String code(String secret) {
        return Totp.code(Base32.decode(secret), Totp.step(clock.instant()));
    }

    private String challenge(String name) {
        Map<String, Object> first = signIn(name, PASSWORD);
        assertThat(first).containsEntry("status", "MFA_REQUIRED").containsEntry("accessToken", null)
            .containsEntry("refreshToken", null);
        return (String) first.get("challenge");
    }

    private Map<String, Object> verify(String challenge, String code) {
        return post("/api/auth/challenge/verify", null, Map.of("challenge", challenge, "code", code))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private void refused(String challenge, String code) {
        Map<String, Object> problem = post("/api/auth/challenge/verify", null,
            Map.of("challenge", challenge, "code", code))
            .expectStatus().isUnauthorized().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(problem)).isEqualTo("LOGIN_FAILED");
    }

    private static List<Map<String, Object>> records(String userId) {
        return query("SELECT attempt_no, outcome, factor, mfa_step, failure_count, locked_until "
            + "FROM sec_login_record_version WHERE user_id = ?::uuid ORDER BY attempt_no", userId);
    }

    private String userId(String name) {
        return String.valueOf(query("SELECT user_id FROM sec_user_version WHERE user_name = ?", name)
            .getFirst().get("user_id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aUserEnrolsAndThenSignsInWithTheSecondFactor() {
        String name = unique("amy");
        String userId = userWith(name, PASSWORD, "p");
        String bearer = bearerOf(signIn(name, PASSWORD));
        assertThat(get("/api/auth/mfa", bearer).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("enrolled", false).containsEntry("pending", false);

        Map<String, Object> enrollment = post("/api/auth/mfa/enroll", bearer, Map.of())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        String secret = (String) enrollment.get("secret");
        assertThat((String) enrollment.get("otpauthUri")).startsWith("otpauth://totp/jabiz:" + name + "?secret="
            + secret);
        assertThat(get("/api/auth/mfa", bearer).expectBody(MAP).returnResult().getResponseBody())
            .containsEntry("pending", true);
        // Stored encrypted: the table does not hold the secret.
        assertThat(String.valueOf(query("SELECT secret FROM sec_user_mfa_version WHERE user_id = ?::uuid", userId)
            .getFirst().get("secret"))).startsWith("v1:").doesNotContain(secret);

        Map<String, Object> wrong = post("/api/auth/mfa/enroll/confirm", bearer, Map.of("code", "000000"))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(wrong)).isEqualTo("MFA_CODE_INVALID");
        List<String> recovery = (List<String>) post("/api/auth/mfa/enroll/confirm", bearer,
            Map.of("code", code(secret))).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("recoveryCodes");
        assertThat(recovery).hasSize(10);
        assertThat(get("/api/auth/mfa", bearer).expectBody(MAP).returnResult().getResponseBody())
            .containsEntry("enrolled", true).containsEntry("recoveryCodesLeft", 10);
        // A confirmed second factor is not replaced by enrolling again.
        assertThat(ruleCode(post("/api/auth/mfa/enroll", bearer, Map.of()).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody())).isEqualTo("MFA_ALREADY_ENROLLED");

        clock.advance(Duration.ofSeconds(30));
        String challenge = challenge(name);
        // A challenge is no access token.
        get("/api/auth/me", "Bearer " + challenge).expectStatus().isUnauthorized();
        Map<String, Object> session = verify(challenge, code(secret));
        assertThat(session).containsEntry("status", "SIGNED_IN").containsEntry("userId", userId);
        Map<String, Object> me = get("/api/auth/me", bearerOf(session)).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(me.get("mfaAt")).isNotNull();
        assertThat(me).containsEntry("idleTimeoutSeconds", 900);
        // The refreshed session keeps its second factor.
        Map<String, Object> refreshed = post("/api/auth/refresh", null, Map.of("refreshToken",
            session.get("refreshToken"))).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(get("/api/auth/me", bearerOf(refreshed)).expectBody(MAP).returnResult().getResponseBody()
            .get("mfaAt")).isEqualTo(me.get("mfaAt"));

        // The same code cannot be used twice, not even under a new challenge.
        String used = code(secret);
        refused(challenge(name), used);
        assertThat(records(userId)).extracting(r -> r.get("outcome")).containsSubsequence("MFA_REQUIRED",
            "SUCCESS", "MFA_REQUIRED", "MFA_FAILED");
        assertThat(records(userId)).filteredOn(r -> "SUCCESS".equals(r.get("outcome")) && r.get("mfa_step") != null)
            .singleElement().satisfies(r -> assertThat(r).containsEntry("factor", "TOTP"));
    }

    @Test
    void wrongCodesCountTowardsTheLockLikeWrongPasswords() {
        String name = unique("bob");
        String secret = enrolled(name, "p");
        String challenge = challenge(name);
        for (int i = 0; i < 5; i++) {
            refused(challenge, "000000");
        }
        refused(challenge, code(secret));
        assertThat(records(userId(name))).extracting(r -> r.get("outcome"))
            .endsWith("MFA_FAILED", "MFA_FAILED", "MFA_FAILED", "MFA_FAILED", "MFA_FAILED", "LOCKED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRecoveryCodeWorksOnce() {
        String name = unique("cai");
        userWith(name, PASSWORD, "p");
        String bearer = bearerOf(signIn(name, PASSWORD));
        String secret = (String) post("/api/auth/mfa/enroll", bearer, Map.of()).expectBody(MAP).returnResult()
            .getResponseBody().get("secret");
        List<String> recovery = (List<String>) post("/api/auth/mfa/enroll/confirm", bearer,
            Map.of("code", code(secret))).expectBody(MAP).returnResult().getResponseBody().get("recoveryCodes");

        Map<String, Object> session = verify(challenge(name), recovery.get(3).toLowerCase());
        assertThat(get("/api/auth/mfa", bearerOf(session)).expectBody(MAP).returnResult().getResponseBody())
            .containsEntry("recoveryCodesLeft", 9);
        refused(challenge(name), recovery.get(3));
        assertThat(records(userId(name))).filteredOn(r -> "SUCCESS".equals(r.get("outcome"))).last()
            .satisfies(r -> assertThat(r).containsEntry("factor", "RECOVERY_CODE"));
    }

    @Test
    void aLaterSignInReplacesTheChallenge() {
        String name = unique("dan");
        String secret = enrolled(name, "p");
        String first = challenge(name);
        String second = challenge(name);
        refused(first, code(secret));
        assertThat(verify(second, code(secret))).containsEntry("status", "SIGNED_IN");
        // Once used, the challenge is spent.
        clock.advance(Duration.ofSeconds(30));
        refused(second, code(secret));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRoleRequiringASecondFactorMakesItsHoldersEnrol() {
        String name = unique("eve");
        String userId = createUser(name, PASSWORD);
        String roleId = String.valueOf(insert(SecurityEntities.ROLE_DATASET, Map.of("roleCode", unique("TREASURY"),
            "labels", Map.of("en", "Treasury"), "enabled", true, "requireMfa", true), null).get("id"));
        insert(SecurityEntities.ROLE_PERMISSION_DATASET, Map.of("roleId", roleId, "permission", "p"), null);
        assign(userId, roleId, null);

        Map<String, Object> first = signIn(name, PASSWORD);
        assertThat(first).containsEntry("status", "MFA_ENROLLMENT_REQUIRED").containsEntry("accessToken", null);
        String challenge = (String) first.get("challenge");
        // An enrolment challenge completes no sign-in.
        refused(challenge, "000000");
        String secret = (String) post("/api/auth/challenge/enroll", null, Map.of("challenge", challenge))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("secret");
        List<String> recovery = (List<String>) post("/api/auth/challenge/enroll/confirm", null,
            Map.of("challenge", challenge, "code", code(secret))).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody().get("recoveryCodes");
        assertThat(recovery).hasSize(10);
        // The operation shows the user as who enrolled.
        assertThat(query("SELECT DISTINCT o.actor_id FROM sec_user_mfa_version m JOIN op_process o "
            + "ON o.process_seq_id = m.process_seq_id WHERE m.user_id = ?::uuid", userId))
            .extracting(r -> r.get("actor_id")).containsExactly(userId);

        clock.advance(Duration.ofSeconds(30));
        assertThat(verify(challenge(name), code(secret))).containsEntry("status", "SIGNED_IN");
    }

    @Test
    void aSessionWithoutASecondFactorEndsWhenARoleStartsToRequireOne() {
        String name = unique("fay");
        String userId = createUser(name, PASSWORD);
        String roleId = createRole(unique("R"), "p");
        assign(userId, roleId, null);
        Map<String, Object> session = signIn(name, PASSWORD);
        assertThat(session).containsEntry("status", "SIGNED_IN");

        Map<String, Object> role = get("/api/datasets/" + SecurityEntities.ROLE_DATASET + "/entities/" + roleId,
            admin()).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/datasets/" + SecurityEntities.ROLE_DATASET + "/commit", admin(), Map.of("changes", List.of(Map.of(
            "action", "UPDATE", "id", roleId, "version", role.get("version"),
            "attributes", Map.of("requireMfa", true))))).expectStatus().isOk();
        post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken")))
            .expectStatus().isUnauthorized();
    }

    @Test
    void operationsRequiringASecondFactorAskForARecentOne() {
        String name = unique("gil");
        String secret = enrolled(name, "security.user.create", "security.user.read");
        Map<String, Object> session = verify(challenge(name), code(secret));
        Map<String, Object> newUser = Map.of("userName", unique("new"), "password", PASSWORD);
        post("/api/processes/SEC_USER_CREATE/latest", bearerOf(session), newUser).expectStatus().isOk();

        // Ten minutes later the second factor is too old for administration.
        clock.advance(Duration.ofMinutes(11));
        Map<String, Object> problem = post("/api/processes/SEC_USER_CREATE/latest", bearerOf(session),
            Map.of("userName", unique("new"), "password", PASSWORD))
            .expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(problem)).isEqualTo("MFA_REQUIRED");

        assertThat(ruleCode(post("/api/auth/step-up", bearerOf(session), Map.of("code", "000000"))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("MFA_CODE_INVALID");
        Map<String, Object> stepped = post("/api/auth/step-up", bearerOf(session), Map.of("code", code(secret)))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/processes/SEC_USER_CREATE/latest", "Bearer " + stepped.get("accessToken"),
            Map.of("userName", unique("new"), "password", PASSWORD)).expectStatus().isOk();
    }

    @Test
    void administrationWithoutASecondFactorIsRefusedEverywhere() {
        String noMfa = TestTokens.withoutMfa(tokens, "it-admin", "*");
        assertThat(ruleCode(post("/api/processes/SEC_USER_CREATE/latest", noMfa,
            Map.of("userName", unique("x"), "password", PASSWORD)).expectStatus().isForbidden()
            .expectBody(MAP).returnResult().getResponseBody())).isEqualTo("MFA_REQUIRED");
        assertThat(ruleCode(post("/api/datasets/" + SecurityEntities.ROLE_DATASET + "/commit", noMfa,
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("roleCode", unique("R"),
                "labels", Map.of("en", "R"), "enabled", true)))))
            .expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody())).isEqualTo("MFA_REQUIRED");
        assertThat(ruleCode(post("/api/entities/" + SecurityEntities.ROLE, noMfa, Map.of("roleCode", unique("R"),
            "labels", Map.of("en", "R"), "enabled", true)).expectStatus().isForbidden().expectBody(MAP)
            .returnResult().getResponseBody())).isEqualTo("MFA_REQUIRED");
        // Reading and other work need none.
        post("/api/datasets/" + SecurityEntities.ROLE_DATASET + "/query", noMfa, Map.of("limit", 1))
            .expectStatus().isOk();

        // The catalog tells clients in advance.
        List<Map<String, Object>> catalog = get("/api/meta/processes", noMfa).expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(catalog).filteredOn(p -> "SEC_USER_CREATE".equals(p.get("name"))).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("requiresMfa", true));
        assertThat(catalog).filteredOn(p -> "ADD_ENTITY".equals(p.get("name"))).isEmpty();
    }

    @Test
    void theGenericEntityProcessesAskForASecondFactorToo() {
        String noMfa = TestTokens.withoutMfa(tokens, "it-admin", "*");
        assertThat(ruleCode(post("/api/processes/ADD_ENTITY/latest", noMfa, Map.of("entityType", SecurityEntities.ROLE,
            "attributes", Map.of("roleCode", unique("R"), "labels", Map.of("en", "R"), "enabled", true)))
            .expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody())).isEqualTo("MFA_REQUIRED");
    }

    @Test
    void nobodyEnrolsASecondFactorForSomeoneElse() {
        String victim = createUser(unique("pat"), PASSWORD);
        post("/api/processes/SEC_MFA_ENROLL_BEGIN/latest", admin(), Map.of("userId", victim))
            .expectStatus().isForbidden();
        assertThat(query("SELECT count(*) AS n FROM sec_user_mfa_version WHERE user_id = ?::uuid", victim)
            .getFirst().get("n")).isEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theConfirmingCodeDoesNotWorkAgainAtSignIn() {
        String name = unique("quinn");
        userWith(name, PASSWORD, "p");
        String bearer = bearerOf(signIn(name, PASSWORD));
        String secret = (String) post("/api/auth/mfa/enroll", bearer, Map.of()).expectBody(MAP).returnResult()
            .getResponseBody().get("secret");
        String code = code(secret);
        post("/api/auth/mfa/enroll/confirm", bearer, Map.of("code", code)).expectStatus().isOk();
        refused(challenge(name), code);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRecoveryCodeIsNotSpentOnARefusedSignIn() {
        String name = unique("rae");
        String userId = createUser(name, PASSWORD);
        String roleId = createRole(unique("R"), "p");
        assign(userId, roleId, null);
        String bearer = bearerOf(signIn(name, PASSWORD));
        String secret = (String) post("/api/auth/mfa/enroll", bearer, Map.of()).expectBody(MAP).returnResult()
            .getResponseBody().get("secret");
        List<String> recovery = (List<String>) post("/api/auth/mfa/enroll/confirm", bearer,
            Map.of("code", code(secret))).expectBody(MAP).returnResult().getResponseBody().get("recoveryCodes");
        String challenge = challenge(name);
        // The role goes away between the two steps.
        Map<String, Object> role = get("/api/datasets/" + SecurityEntities.ROLE_DATASET + "/entities/" + roleId,
            admin()).expectBody(MAP).returnResult().getResponseBody();
        post("/api/datasets/" + SecurityEntities.ROLE_DATASET + "/commit", admin(), Map.of("changes", List.of(Map.of(
            "action", "UPDATE", "id", roleId, "version", role.get("version"),
            "attributes", Map.of("enabled", false))))).expectStatus().isOk();
        refused(challenge, recovery.getFirst());
        assertThat(String.valueOf(query("SELECT recovery_codes FROM sec_user_mfa_version WHERE user_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", userId).getFirst().get("recovery_codes")).split(",")).hasSize(10);
    }

    @Test
    void stepUpNeedsAnEnrolledUser() {
        String name = unique("hal");
        userWith(name, PASSWORD, "p");
        assertThat(ruleCode(post("/api/auth/step-up", bearerOf(signIn(name, PASSWORD)), Map.of("code", "123456"))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("MFA_NOT_ENROLLED");
    }

    @Test
    void anAdministratorResetsASecondFactor() {
        String name = unique("ida");
        enrolled(name, "p");
        String userId = userId(name);
        post("/api/processes/SEC_MFA_RESET/latest", admin(), Map.of("userId", userId, "reason", "phone lost"))
            .expectStatus().isOk();
        assertThat(signIn(name, PASSWORD)).containsEntry("status", "SIGNED_IN");
        assertThat(ruleCode(post("/api/processes/SEC_MFA_RESET/latest", admin(),
            Map.of("userId", userId, "reason", "again")).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody())).isEqualTo("MFA_NOT_ENROLLED");
        // The user may enrol again.
        post("/api/auth/mfa/enroll", bearerOf(signIn(name, PASSWORD)), Map.of()).expectStatus().isOk();
    }

    @Test
    void theEnrolmentProcessesRunOnlyThroughTheirOwnEndpoints() {
        String userId = createUser(unique("jo"), PASSWORD);
        post("/api/processes/SEC_MFA_ENROLL_BEGIN/latest", bearer("security.user.read"), Map.of("userId", userId))
            .expectStatus().isForbidden();
        post("/api/processes/SPONSOR_MFA_VERIFY/latest", bearer("security.user.read"),
            Map.of("userId", userId, "code", "123456")).expectStatus().isForbidden();
        post("/api/auth/challenge/enroll", null, Map.of("challenge", "not-a-token")).expectStatus().isUnauthorized();
    }

    @Test
    void aSecretCopiedToAnotherUserDoesNotWork() {
        String name = unique("kim");
        String secret = enrolled(name, "p");
        String other = unique("lou");
        enrolled(other, "p");
        // Someone with database access copies kim's sealed secret into a new version of lou's row.
        execute("INSERT INTO sec_user_mfa_version (user_mfa_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, is_deleted, user_id, secret, confirmed, confirmed_time, recovery_codes) "
            + "SELECT o.user_mfa_id, o.version_no + 1, o.effect_start_time + interval '1 second', o.created_time, "
            + "o.process_seq_id, false, o.user_id, k.secret, true, o.confirmed_time, o.recovery_codes "
            + "FROM sec_user_mfa_version o, sec_user_mfa_version k "
            + "WHERE o.user_id = ?::uuid AND k.user_id = ?::uuid ORDER BY o.version_no DESC, k.version_no DESC "
            + "LIMIT 1", userId(other), userId(name));
        refused(challenge(other), code(secret));
    }

    @Test
    void anIdleSessionCannotBeRefreshed() {
        String name = unique("max");
        userWith(name, PASSWORD, "p");
        Map<String, Object> session = signIn(name, PASSWORD);
        // Active: refreshed shortly after the access token expired.
        clock.advance(Duration.ofMinutes(29));
        Map<String, Object> next = post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken")))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        // Idle: fifteen minutes past the access token's expiry.
        clock.advance(Duration.ofMinutes(30));
        post("/api/auth/refresh", null, Map.of("refreshToken", next.get("refreshToken")))
            .expectStatus().isUnauthorized();
    }

    @Test
    void codesAndSecretsStayOutOfTheOperationRecords() {
        String name = unique("oli");
        String secret = enrolled(name, "p");
        String code = code(secret);
        verify(challenge(name), code);
        List<Map<String, Object>> summaries = query("SELECT process_name, input_summary::text AS summary "
            + "FROM op_process WHERE process_name IN ('SPONSOR_MFA_VERIFY', 'SEC_MFA_ENROLL_CONFIRM') "
            + "AND input_summary::text LIKE ?", "%" + userId(name) + "%");
        assertThat(summaries).extracting(r -> r.get("process_name")).contains("SPONSOR_MFA_VERIFY",
            "SEC_MFA_ENROLL_CONFIRM");
        assertThat(summaries).allSatisfy(r -> assertThat(String.valueOf(r.get("summary")))
            .contains("\"mfaCode\": \"***\"").doesNotContain(code));
        assertThat(query("SELECT count(*) AS n FROM op_process_result WHERE output::text LIKE ?", "%" + secret + "%")
            .getFirst().get("n")).isEqualTo(0L);
    }

    @Test
    void theSecondFactorTablesAreOnlyEverInsertedInto() {
        String name = unique("ned");
        SqlStatementLog.STATEMENTS.clear();
        String secret = enrolled(name, "p");
        verify(challenge(name), code(secret));
        assertThat(SqlStatementLog.STATEMENTS).isNotEmpty()
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase()).startsWith("UPDATE"))
            .noneSatisfy(sql -> assertThat(sql.trim().toUpperCase()).startsWith("DELETE"));
    }
}
