package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q71 (docs/quizbuks/plans/Q71-taker-erasure.md; platform decision D62): erasing a taker's account removes their
 * "about me", bank account, the invitations they claimed and their place on a sponsor's staff; their withdrawals stay
 * as records with the holder's name and account number emptied (V52), their attempts stay with their id only.
 */
class TakerErasureIT extends PayoutItSupport {

    /** Her name as her bank holds it (half-width katakana): found nowhere once she is erased. */
    private static final String ANN_HOLDER = "ﾐﾅﾐ ﾊﾅｺ";
    private static final AtomicInteger NUMBERS = new AtomicInteger(9_004_000);

    @DynamicPropertySource
    static void maintenance(DynamicPropertyRegistry registry) {
        // The tests' login is the maintenance login too (a member of jabiz_maintenance, granted below), for the purge.
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
    }

    @BeforeEach
    void maintenanceRoleAndBankWithdrawals() {
        execute("DO $$ BEGIN CREATE ROLE jabiz_maintenance; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        execute("DO $$ BEGIN EXECUTE 'GRANT jabiz_maintenance TO ' || quote_ident(current_user);"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
        param(QbParams.WITHDRAWAL_BANK_ENABLED, "true");
        param(QbParams.WITHDRAWAL_BANK_FEE, "200");
        param(QbParams.TRANSFER_THRESHOLD, "1000");
    }

    @Test
    void anErasedTakersPersonalDataGoesAndTheRecordsStayWithoutIt() {
        String ann = payee("t-q71-ann-" + UUID.randomUUID().toString().substring(0, 8));
        String bob = payee("t-q71-bob-" + UUID.randomUUID().toString().substring(0, 8));
        String annNumber = number();
        String bobNumber = number();
        for (Object[] taker : new Object[][] {{ann, annNumber, ANN_HOLDER}, {bob, bobNumber, "ﾔﾏﾀﾞ ﾀﾛｳ"}}) {
            String id = (String) taker[0];
            run("QB_BANK_ACCOUNT_SAVE", taker(id), account((String) taker[1], (String) taker[2]));
            run("QB_PAYOUT_METHOD_SET", taker(id), body("method", "BANK"));
            run("QB_PROFILE_ATTRIBUTES_SAVE", taker(id), body("profileAnswers",
                List.of(answer("gender", List.of("FEMALE")))));
        }
        advance(Duration.ofHours(73));
        earn(ann, 3000);
        // A withdrawal under way: it stays a record, the copy of the bank account in it no longer names her.
        String transferId = String.valueOf(run("QB_WITHDRAWAL_REQUEST", taker(ann), body("amount", 2000))
            .get("transferId"));
        // An invitation she claimed (her address, given by a sponsor) and her place on a sponsor's staff.
        String sponsor = newSponsor("s-q71-" + UUID.randomUUID().toString().substring(0, 8));
        String publication = livePublication(sponsor, "Invited quiz");
        String annAddress = "ann-" + UUID.randomUUID() + "@example.com";
        invitation(publication, sponsor, annAddress, ann);
        invitation(publication, sponsor, "bob-" + UUID.randomUUID() + "@example.com", bob);
        staff(sponsor, ann);

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", ann, "requestedBy", "it-support"));
        deliverEvents();

        assertThat(query("SELECT 1 FROM qb_profile_attribute WHERE user_id = ?", ann)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_bank_account WHERE user_id = ?", ann)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_invitation WHERE user_id = ?", ann)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_sponsor_staff WHERE user_id = ?", ann)).isEmpty();
        // The withdrawal is kept, without who was paid; its amount and state are the accounts'.
        assertThat(transfer(transferId)).containsEntry("account_holder", null)
            .containsEntry("account_number", null).containsEntry("user_id", ann).containsEntry("status", "REVIEW");
        // Nothing of hers is left anywhere: her account number and address, in no table, version or audit record.
        for (String needle : List.of(annNumber, ANN_HOLDER, annAddress)) {
            assertThat(occurrences(needle)).as("where %s still is", needle).isEmpty();
        }
        // Another taker is untouched.
        assertThat(query("SELECT 1 FROM qb_profile_attribute WHERE user_id = ?", bob)).isNotEmpty();
        assertThat(query("SELECT account_number FROM qb_bank_account WHERE user_id = ?", bob))
            .singleElement().satisfies(row -> assertThat(row.get("account_number")).isEqualTo(bobNumber));
        assertThat(query("SELECT 1 FROM qb_invitation WHERE user_id = ?", bob)).isNotEmpty();
    }

    private static Map<String, Object> account(String number, String holder) {
        return body("bankCode", "０００１", "bankName", "みずほ銀行", "branchCode", "001", "branchName", "東京営業部",
            "accountType", "ORDINARY", "bankAccountNumber", number, "accountHolder", holder);
    }

    /** Every text or JSON column of the schema that holds the value, as {@code table.column}. */
    private static List<String> occurrences(String needle) {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> column : query("SELECT table_name::text AS t, column_name::text AS c"
            + " FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND data_type IN ('text', 'character varying', 'character', 'jsonb', 'json')")) {
            String sql = "SELECT count(*) AS n FROM \"" + column.get("t") + "\" WHERE \"" + column.get("c")
                + "\"::text ILIKE ?";
            if (((Number) query(sql, "%" + needle + "%").getFirst().get("n")).longValue() > 0) {
                found.add(column.get("t") + "." + column.get("c"));
            }
        }
        return found;
    }

    private static String number() {
        return String.valueOf(NUMBERS.incrementAndGet());
    }

    private static Map<String, Object> answer(String key, List<String> options) {
        Map<String, Object> answer = new HashMap<>();
        answer.put("key", key);
        answer.put("options", options);
        answer.put("number", null);
        return answer;
    }

    /** An invitation the taker claimed (the invitation flow itself is {@code InvitationIT}'s). */
    private static void invitation(String publication, String sponsor, String email, String userId) {
        execute("INSERT INTO qb_invitation (invitation_id, publication_id, publisher_kind, publisher_id, email, status,"
            + " added_at, sent_count, user_id, version) VALUES ('" + UUID.randomUUID() + "', '" + publication
            + "', 'SPONSOR', '" + sponsor + "', '" + email + "', 'STARTED', now(), 1, '" + userId + "', 1)");
    }

    /** The taker on the sponsor's staff (the staff flow itself is {@code SponsorStaffIT}'s). */
    private static void staff(String sponsor, String userId) {
        execute("INSERT INTO qb_sponsor_staff (staff_id, sponsor_id, user_id, added_at, added_by, version) VALUES ('"
            + UUID.randomUUID() + "', '" + sponsor + "', '" + userId + "', now(), '" + sponsor + "', 1)");
    }
}
