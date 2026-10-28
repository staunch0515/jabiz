package com.jabiz.app.it.security;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.document.DocumentLayoutRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.erasure.AccountErasure;
import com.jabiz.runtime.erasure.ErasureChecks;
import com.jabiz.runtime.erasure.ErasurePurge;
import com.jabiz.runtime.erasure.PersonalDataErasers;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.job.JobDefinition;
import com.jabiz.security.erasure.ErasedEntity;
import com.jabiz.security.erasure.PersonalDataEraser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Account erasure (docs/design/10-security.md section 17; decision D62): the erasure disables the user and clears its
 * data through the ordinary write path, the purge overwrites what history and append-only records copied on the
 * maintenance connection, and afterwards none of the person's name, address or customer name is left anywhere in the
 * database, while the seals still verify (the overwritten rows count as ERASED) and a change past the guard is still
 * found.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ErasureIT extends SecurityItSupport {

    @DynamicPropertySource
    static void maintenance(DynamicPropertyRegistry registry) {
        // The tests' login is the maintenance login too: a member of jabiz_maintenance (granted below).
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
    }

    @Autowired
    ProcessExecutor executor;

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    EntityDefinitionRegistry entities;

    @Autowired
    DocumentLayoutRegistry layouts;

    @Autowired
    JobRunner jobs;

    @Autowired
    JobDefinition<ErasurePurge.PurgeInput> erasurePurgeJob;

    @BeforeEach
    void maintenanceRole() {
        execute("DO $$ BEGIN CREATE ROLE jabiz_maintenance; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        execute("DO $$ BEGIN EXECUTE 'GRANT jabiz_maintenance TO ' || quote_ident(current_user);"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
    }

    @Test
    void anErasedAccountLeavesNoPersonalDataAndTheSealsStillVerify() {
        String name = unique("eve");
        String email = name + "@example.com";
        String display = "Eve " + UUID.randomUUID();
        String customerName = "Customer " + UUID.randomUUID();
        String userId = create(name, display, email);
        Map<String, Object> session = signIn(name, "eve-password-1");
        insert(CommerceEntities.CUSTOMER_DATASET, Map.of("customerCode", unique("C"), "customerName", customerName,
            "userId", userId), null);
        long sealNo = sealNo(seal());

        Map<String, Object> erased = erase(userId);
        String erasureId = (String) erased.get("erasureId");
        Map<String, Object> user = query("SELECT * FROM sec_user_version WHERE user_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", userId).getFirst();
        assertThat(user).containsEntry("enabled", false).containsEntry("email", null)
            .containsEntry("display_name", null).containsEntry("password_hash", null);
        assertThat(user.get("erased_at")).isNotNull();
        // The sessions are over and the account no longer signs in.
        post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken"))).expectStatus()
            .isUnauthorized();
        login(name, "eve-password-1").expectStatus().isUnauthorized();
        assertThat(query("SELECT * FROM sys_erasure WHERE erasure_id = ?::uuid", erasureId)).hasSize(1);

        // The purge follows the event, on the maintenance connection.
        deliverer.deliverPending().block();
        assertThat(query("SELECT row_count FROM sys_erasure_purge WHERE erasure_id = ?::uuid", erasureId))
            .extracting(row -> ((Number) row.get("row_count")).intValue()).first().satisfies(count ->
                assertThat(count).isPositive());
        assertThat(query("SELECT user_name FROM sec_user_version WHERE user_id = ?::uuid", userId))
            .extracting(row -> row.get("user_name")).containsOnly("erased-" + userId);

        // Nothing of the person is left, in any table, column or version.
        for (String needle : List.of(name, email, display, customerName)) {
            assertThat(occurrences(needle)).as("where %s still is", needle).isEmpty();
        }

        // The seals verify: the overwritten rows are accounted for, nothing is reported.
        Map<String, Object> verified = verify(sealNo);
        assertThat(verified).containsEntry("intact", true);
        assertThat(((Number) verified.get("erasedCount")).intValue()).isPositive();
        // A change past the guard to an overwritten row is still a change.
        long rowId = ((Number) query("SELECT row_id FROM sec_user_version WHERE user_id = ?::uuid"
            + " ORDER BY version_no LIMIT 1", userId).getFirst().get("row_id")).longValue();
        bypassingTheGuard("UPDATE sec_user_version SET display_name = 'back again' WHERE row_id = " + rowId);
        Map<String, Object> tampered = verify(sealNo);
        assertThat(tampered).containsEntry("intact", false);
        assertThat(problems(tampered)).extracting(p -> p.get("kind"), p -> p.get("table"), p -> p.get("key"))
            .contains(tuple("MODIFIED", "sec_user_version", "[" + rowId + "]"));
        // Entries that lead to the changed row's digest - plain SHA-256, anyone can compute them - do not account for
        // it without a purge signed with the integrity key.
        String forged = UUID.randomUUID().toString();
        withSealSettings(
            "INSERT INTO sys_erasure (erasure_id, user_id, erased_time, requested_by, process_seq_id)"
                + " SELECT '" + forged + "', '" + userId + "', now(), 'forger', max(process_seq_id) FROM op_process",
            "INSERT INTO sys_erasure_purge (erasure_id, purged_time, row_count, process_seq_id, signature, key_id)"
                + " VALUES ('" + forged + "', now(), 1, 1, repeat('0', 64), 'forged')",
            "INSERT INTO sys_erasure_item (erasure_id, table_name, row_key, columns, digest_before, digest_after)"
                + " SELECT '" + forged + "', i.table_name, i.row_key, ARRAY['display_name'], x.digest_after,"
                + " " + com.jabiz.runtime.integrity.IntegrityStore.digest("t", "st.columns")
                + " FROM sys_integrity_item i JOIN sys_integrity_seal_table st ON st.seal_no = i.seal_no"
                + " AND st.table_name = i.table_name JOIN sys_erasure_item x ON x.table_name = i.table_name"
                + " AND x.row_key = i.row_key JOIN sec_user_version t ON t.row_id = " + rowId
                + " WHERE i.table_name = 'sec_user_version' AND i.row_key = '[" + rowId + "]'");
        assertThat(problems(verify(sealNo))).extracting(p -> p.get("kind"), p -> p.get("table"), p -> p.get("key"))
            .contains(tuple("MODIFIED", "sec_user_version", "[" + rowId + "]"));
        bypassingTheGuard("UPDATE sec_user_version SET display_name = NULL WHERE row_id = " + rowId);

        // Again: the purge has nothing left to do, the erasure is refused.
        ErasurePurge.PurgeOutput again = asRequest(system(), executor.execute(ErasurePurge.PURGE,
            new ErasurePurge.PurgeInput(UUID.fromString(erasureId)))).block();
        assertThat(again.purged()).isFalse();
        assertThat(ruleOf(() -> erase(userId))).isEqualTo("ACCOUNT_ERASED");

        // The address is free again.
        create(unique("eve"), "Eve again", email);
    }

    @Test
    void anotherUsersRecordsUnderAnAddressTheErasedUserOnceHadStay() {
        String shared = unique("shared") + "@example.com";
        String first = unique("first");
        String firstId = create(first, "First", shared);
        // The first user moves to another address; a second user then takes the first one, and signs in with it.
        Map<String, Object> current = query("SELECT version_no FROM sec_user_version WHERE user_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", firstId).getFirst();
        post("/api/datasets/" + com.jabiz.runtime.security.SecurityEntities.USER_DATASET + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "UPDATE", "id", firstId, "version",
                current.get("version_no"), "attributes", Map.of("email", unique("moved") + "@example.com")))))
            .expectStatus().isOk();
        String secondId = create(unique("second"), "Second", shared);
        // Mail of nobody's to the address, sent after the second user took it (a sign-up check, say).
        execute("INSERT INTO sys_mail_message (message_id, template, category, user_id, address, locale, params,"
            + " created_time, process_seq_id, version) SELECT ?, 'it.notice', 'TRANSACTIONAL', NULL, ?, 'en', '{}',"
            + " now(), max(process_seq_id), 1 FROM op_process", UUID.randomUUID().toString(), shared);
        execute("INSERT INTO sys_mail_address_optout (opt_out_id, address, template, opted_out_time, version)"
            + " VALUES (?, ?, 'it.notice', now(), 1)", UUID.randomUUID().toString(), shared);

        erase(firstId);
        deliverer.deliverPending().block();

        assertThat(query("SELECT * FROM sys_erasure_purge p JOIN sys_erasure e USING (erasure_id)"
            + " WHERE e.user_id = ?::uuid", firstId)).hasSize(1);
        assertThat(query("SELECT email FROM sec_user_version WHERE user_id = ?::uuid", secondId))
            .extracting(row -> row.get("email")).containsOnly(shared);
        assertThat(query("SELECT count(*) AS n FROM sys_mail_message WHERE address = ?", shared).getFirst()
            .get("n")).isEqualTo(1L);
        assertThat(query("SELECT count(*) AS n FROM sys_mail_address_optout WHERE address = ?", shared).getFirst()
            .get("n")).isEqualTo(1L);
        assertThat(occurrences(first)).isEmpty();
    }

    @Test
    void theOptOutsOfTheUsersAddressGoAndAPurgeWhoseEventWasMissedRunsFromTheJob() {
        String name = unique("late");
        String email = name + "@example.com";
        String userId = create(name, "Late", email);
        execute("INSERT INTO sys_mail_address_optout (opt_out_id, address, template, opted_out_time, version)"
            + " VALUES (?, ?, 'it.notice', now(), 1)", UUID.randomUUID().toString(), email);
        String erasureId = erase(userId).get("erasureId").toString();
        assertThat(query("SELECT * FROM sys_mail_address_optout WHERE address = ?", email)).isEmpty();

        // The event is not delivered; the daily job finds the erasure without a purge.
        clock.advance(java.time.Duration.ofDays(1).plusMinutes(17));
        assertThat(jobs.run(erasurePurgeJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(query("SELECT * FROM sys_erasure_purge WHERE erasure_id = ?::uuid", erasureId)).hasSize(1);
        assertThat(occurrences(email)).isEmpty();
    }

    @Test
    void aLegalHoldOnTheUserKeepsTheAccount() {
        String name = unique("held");
        String userId = create(name, "Held", name + "@example.com");
        post("/api/processes/LEGAL_HOLD_PLACE/latest", bearer("legal.hold.write"), Map.of("name", "litigation",
            "reason", "court order", "entityType", "SecUser", "ids", List.of(userId))).expectStatus().isOk();
        assertThat(ruleOf(() -> erase(userId))).isEqualTo("LEGAL_HOLD");
        assertThat(query("SELECT * FROM sys_erasure WHERE user_id = ?::uuid", userId)).isEmpty();
        signIn(name, "eve-password-1");
    }

    @Test
    void theErasersAreCheckedAtStartup() {
        assertThat(checks(() -> List.of(ErasedEntity.keep(CommerceEntities.CUSTOMER, "userId", "customerName"))))
            .isEmpty();
        assertThat(checks(
            () -> List.of(ErasedEntity.delete("NoSuchEntity", "userId")),
            () -> List.of(ErasedEntity.clear(CommerceEntities.CUSTOMER, "userId", "customerName"),
                ErasedEntity.keep(CommerceEntities.CUSTOMER, "owner", "nickname", "customerId")),
            () -> List.of(ErasedEntity.keep("Product", "productId", "unitPrice").documents("no-layout"))))
            .extracting(p -> p.location(), p -> p.message())
            .contains(
                tuple("PersonalDataEraser NoSuchEntity", "entity NoSuchEntity is not declared"),
                tuple("PersonalDataEraser Customer", "field customerName is cleared on erasure, so it must be optional"
                    + " and changeable; declare the entity delete(...) or keep(...)"),
                tuple("PersonalDataEraser Customer", "declared more than once"),
                tuple("PersonalDataEraser Customer", "user field owner is not a field of Customer"),
                tuple("PersonalDataEraser Customer", "field nickname is not a field of Customer"),
                tuple("PersonalDataEraser Customer", "field customerId is the primary key: rows keep their keys"
                    + " (decision D62 item 4)"),
                tuple("PersonalDataEraser Product", "field unitPrice is required and not text: the purge has nothing"
                    + " to overwrite it with"),
                tuple("PersonalDataEraser Product", "document layout no-layout is not declared"));
    }

    // ================= helpers =================

    private String create(String userName, String displayName, String email) {
        Map<String, Object> input = new HashMap<>();
        input.put("userName", userName);
        input.put("displayName", displayName);
        input.put("password", "eve-password-1");
        input.put("email", email);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) post("/api/processes/SEC_USER_CREATE/latest", admin(),
            input).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("output");
        String userId = (String) output.get("userId");
        // A role, so that the entry accepts the user.
        assign(userId, createRole(unique("ROLE"), "audit.read"), null);
        return userId;
    }

    private Map<String, Object> erase(String userId) {
        AccountErasure.EraseOutput result = asRequest(system(), executor.execute(AccountErasure.ERASE,
            new AccountErasure.EraseInput(UUID.fromString(userId), "it-support"))).block();
        return Map.of("erasureId", result.erasureId());
    }

    private static String ruleOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessRuleViolationException e) {
            return e.violations().stream().map(Violation::ruleCode).findFirst().orElse(null);
        }
        throw new AssertionError("not refused");
    }

    private List<com.jabiz.runtime.check.CheckProblem> checks(PersonalDataEraser... erasers) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < erasers.length; i++) {
            beans.addBean("eraser" + i, erasers[i]);
        }
        return new ErasureChecks(new PersonalDataErasers(beans.getBeanProvider(PersonalDataEraser.class)), entities,
            layouts).check();
    }

    /** Every table and text or JSON column of the schema that holds the value, as {@code table.column}. */
    private static List<String> occurrences(String needle) {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> column : query("SELECT table_name::text AS t, column_name::text AS c"
            + " FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND data_type IN ('text', 'character varying', 'character', 'jsonb', 'json')")) {
            String table = (String) column.get("t");
            String name = (String) column.get("c");
            String sql = "SELECT count(*) AS n FROM \"" + table + "\" WHERE \"" + name + "\"::text ILIKE ?";
            if (((Number) query(sql, "%" + needle + "%").getFirst().get("n")).longValue() > 0) {
                found.add(table + "." + name);
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> seal() {
        Map<String, Object> input = new HashMap<>();
        input.put("scheduledTime", null);
        return (Map<String, Object>) post("/api/processes/INTEGRITY_SEAL/latest", admin(), input).expectStatus()
            .isOk().expectBody(MAP).returnResult().getResponseBody().get("output");
    }

    private static long sealNo(Map<String, Object> output) {
        return ((Number) output.get("sealNo")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verify(long fromSeal) {
        return (Map<String, Object>) post("/api/processes/INTEGRITY_VERIFY/latest", admin(),
            Map.of("fromSeal", fromSeal)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("output");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> problems(Map<String, Object> verified) {
        return (List<Map<String, Object>>) get("/api/integrity/checks/" + verified.get("checkNo"),
            bearer("integrity.read")).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("problems");
    }

    /** The statements on one connection with the settings the seal digests rows under. */
    private static void withSealSettings(String... statements) {
        try (java.sql.Connection connection = DB.connect(schema());
             java.sql.Statement sql = connection.createStatement()) {
            sql.execute("SET TimeZone = 'UTC'; SET IntervalStyle = 'iso_8601'; SET extra_float_digits = 1");
            for (String statement : statements) {
                assertThat(sql.executeUpdate(statement)).as(statement).isEqualTo(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void bypassingTheGuard(String statement) {
        try (java.sql.Connection connection = DB.connect(schema());
             java.sql.Statement sql = connection.createStatement()) {
            sql.execute("SET session_replication_role = replica");
            sql.execute(statement);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RequestContext system() {
        return RequestContext.system(Locale.ENGLISH, "it-erasure");
    }
}
