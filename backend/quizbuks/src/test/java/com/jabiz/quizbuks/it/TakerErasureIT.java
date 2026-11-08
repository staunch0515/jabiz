package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.certification.CertificationProcesses;
import com.jabiz.runtime.test.TestTokens;
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

    @Test
    void anErasedTakersCertificatesAndPublicNameGo() {
        String cat = payee("t-q77-cat-" + UUID.randomUUID().toString().substring(0, 8));
        String name = "Cat Q77 " + UUID.randomUUID().toString().substring(0, 8);
        String handle = "cat-" + UUID.randomUUID().toString().substring(0, 8);
        run("QB_PROFILE_SAVE", taker(cat), body("displayName", name, "countryCode", "JP", "preferredCurrency",
            "JPY"));
        run(CertificationProcesses.GRANT, certificationGranter(), body("userId", cat, "certification", "SPONSOR_APPLY", "reason",
            "Met at the fair"));
        String key = issuedBefore(cat, "SPONSOR_APPLY", name, "en");
        run("QB_SETTINGS_SAVE", taker(cat), body("publicHandle", handle, "publicPage", true));
        List<Object> runs = query("SELECT run_id FROM sys_document_run WHERE layout_id = 'qb.certificate' AND "
            + "document_no = ?", key).stream().map(row -> row.get("run_id")).toList();
        assertThat(runs).hasSize(1);

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", cat, "requestedBy", "it-support"));
        deliverEvents();

        assertThat(query("SELECT 1 FROM qb_certificate WHERE user_id = ?", cat)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_user_settings WHERE user_id = ?", cat)).isEmpty();
        // The certificate's archived document is purged with the rest: QuizBuks keeps only the receipts' and the
        // settlement statements' for ten years (platform 17zd, TopUpConfig.qbDocumentRunRetention), so its PDF is emptied
        // (the column takes no null) and her name and page name are found nowhere.
        assertThat(query("SELECT octet_length(pdf) AS size FROM sys_document_run WHERE run_id = ?::uuid",
            String.valueOf(runs.getFirst()))).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("size")).longValue()).isZero());
        for (String needle : List.of(name, handle)) {
            assertThat(occurrences(needle)).as("where %s still is", needle).isEmpty();
        }
        // The key, which leads to nobody once the certificate is gone: the document's number and its issued event.
        assertThat(occurrences(key)).allMatch(where -> where.equals("sys_document_run.document_no")
            || where.equals("sys_outbox_event.payload"));
    }

    /** F-S9: the topics a taker liked and their strict mode go with the account, from the audit records too. */
    @Test
    void anErasedTakersLikedTopicsGo() {
        String dee = payee("t-fs9-dee-" + UUID.randomUUID().toString().substring(0, 8));
        String eve = payee("t-fs9-eve-" + UUID.randomUUID().toString().substring(0, 8));
        String code = "fs9-erase-" + UUID.randomUUID().toString().substring(0, 8);
        run("QB_TOPIC_ADD", TestTokens.bearer(tokens, "topic-admin", QbPermissions.ADMIN_TOPIC_WRITE),
            body("topicId", code, "nameZh", "抹除", "nameJa", "消去", "nameEn", "Erase"));
        for (String id : List.of(dee, eve)) {
            run("QB_SETTINGS_SAVE", taker(id), body("likedTopics", List.of(code), "strictTopics", true));
        }

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", dee, "requestedBy", "it-support"));
        deliverEvents();

        assertThat(query("SELECT 1 FROM qb_user_settings WHERE user_id = ?", dee)).isEmpty();
        assertThat(query("SELECT changes FROM sys_audit_record WHERE entity_type = 'QbUserSettings' AND entity_id = ?",
            dee)).isNotEmpty().allSatisfy(row -> assertThat(String.valueOf(row.get("changes"))).doesNotContain(code));
        // Another taker's are untouched.
        assertThat(query("SELECT liked_topics, strict_topics FROM qb_user_settings WHERE user_id = ?", eve))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("liked_topics", code)
                .containsEntry("strict_topics", true));
    }

    @Test
    void anErasedTakersMessagesLoseTheirTextAndTheirBlocksGo() {

        // Q82 (D-Q82-11): the conversation stays for the sponsor (holding only her id), what she wrote goes, what
        // the sponsor wrote stays; the sponsors she blocked are no longer recorded.
        String sponsor = newSponsor("s-q82-" + UUID.randomUUID().toString().substring(0, 8));
        credit(sponsor, 1_000);
        String publication = livePublication(sponsor, "Q82 erased", Map.of("regions", List.of("GLOBAL")));
        String dee = newTaker("t-q82-dee-" + UUID.randomUUID().toString().substring(0, 8), "JP");
        play(dee, publication, true, true);
        // Letters only: a random UUID now and then holds a run of digits the messages refuse as a phone number.
        String hers = "Dee asks about the prize " + letters();
        String theirs = "The sponsor answers " + letters();
        String id = (String) run("QB_CONVERSATION_OPEN", taker(dee), body("publicationId", publication, "body", hers))
            .get("conversationId");
        advance(Duration.ofMinutes(1));
        run("QB_CONVERSATION_SEND", sponsorToken(sponsor), body("conversationId", id, "body", theirs));
        run("QB_CONVERSATION_BLOCK", taker(dee), body("conversationId", id));

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", dee, "requestedBy", "it-support"));
        deliverEvents();

        assertThat(query("SELECT 1 FROM qb_conversation WHERE conversation_id = ?", id)).hasSize(1);
        assertThat(query("SELECT 1 FROM qb_conversation_block WHERE taker_id = ?", dee)).isEmpty();
        assertThat(query("SELECT sender, body FROM qb_conversation_message WHERE conversation_id = ? ORDER BY "
            + "created_at", id)).extracting(row -> row.get("body")).containsExactly(null, theirs);
        assertThat(occurrences(hers)).as("where her message still is").isEmpty();
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

    /** A random word of letters (a UUID's hexadecimal digits, each turned into a letter). */
    private static String letters() {
        StringBuilder word = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").toCharArray()) {
            word.append(Character.isDigit(c) ? (char) ('g' + (c - '0')) : c);
        }
        return word.toString();
    }
}
