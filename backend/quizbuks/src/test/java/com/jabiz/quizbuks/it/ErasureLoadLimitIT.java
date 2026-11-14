package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.reward.CashRewardStatus;
import com.jabiz.quizbuks.reward.RedemptionStatus;
import com.jabiz.quizbuks.wallet.TransferStatus;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.erasure.ErasureNotices;
import com.jabiz.security.erasure.ErasureNotice;
import com.jabiz.security.erasure.ErasureSubject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M15 a (docs/quizbuks/plans/impl/F-M15.md §3, §10, H4): the notices before an account's deletion read counts, not
 * rows, so a user with more history than a row load may hold ({@link ErasureSubject#MAX_ROWS}) can still ask. Each load
 * gets {@value #HISTORY} rows, most of them finished: asking does not fail and the notice counts only the open ones.
 *
 * <p>The rows are written straight into the tables (the point is the notices' read, not 150 approvals): every required
 * column gets a plain value of its type, the ones the loads read are given.
 */
class ErasureLoadLimitIT extends PlayItSupport {

    /** More than a row load may read. */
    private static final int HISTORY = ErasureSubject.MAX_ROWS + 50;

    @Autowired
    ErasureNotices notices;

    @DynamicPropertySource
    static void deletion(DynamicPropertyRegistry registry) {
        // Sponsors may ask here (off by default: it needs the maintenance connection, given as in the erasure ITs).
        registry.add("jabiz.security.entries.sponsor.account-deletion", () -> "true");
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
    }

    @Test
    void aSponsorWithManyPublicationsAndCashRewardsCanStillAsk() {
        String sponsor = newSponsor(unique("s-fm15a-many"));
        int live = 2;
        // The publications themselves (what the notice read before F-M15 a), and their cards (what it reads now).
        rows("qb_publication_version", "QbPublication", HISTORY, null, n -> Map.of("publication_id", "ids.id",
            "publisher_kind", "'SPONSOR'", "sponsor_id", text(sponsor), "reward_method", "'SPONSOR_CASH'",
            "audience", "'PUBLIC'",
            "status", "CASE WHEN n <= " + live + " THEN 'LIVE' ELSE 'ENDED' END"));
        rows("qb_publication_card", null, HISTORY, "SELECT row_number() OVER () AS n, publication_id AS id "
            + "FROM qb_publication_version WHERE sponsor_id = " + text(sponsor), n -> Map.of(
            "publisher_kind", "'SPONSOR'", "publisher_id", text(sponsor), "reward_method", "'SPONSOR_CASH'",
            "audience", "'PUBLIC'", "status", "(SELECT p.status FROM qb_publication_version p "
                + "WHERE p.publication_id = ids.id)"));
        int owed = 3;
        rows("qb_cash_reward_version", "QbCashReward", HISTORY, null, n -> Map.of("cash_reward_id", "ids.id",
            "publisher_kind", "'SPONSOR'", "sponsor_id", text(sponsor), "user_id", text(UUID.randomUUID().toString()),
            "status", "CASE WHEN n <= " + owed + " THEN '" + CashRewardStatus.DUE + "' ELSE '"
                + CashRewardStatus.CONFIRMED + "' END"));

        Map<String, ErasureNotice> found = noticesOf(sponsor);
        assertThat(found.get("qb.publications-end").params()).containsEntry("count", (long) live);
        assertThat(found.get("qb.cash-rewards-open").params()).containsEntry("count", (long) owed);
    }

    @Test
    void aTakerWithManyWithdrawalsRewardsAndVouchersCanStillAsk() {
        String taker = newTaker(unique("t-fm15a-many"), null);
        int reviewing = 1;
        // At most one transfer of a user is not finished (a partial unique index): one under review, the rest
        // rejected (nothing posted).
        rows("qb_transfer", null, HISTORY, null, n -> Map.of(
            "user_id", text(taker), "method", "'STRIPE'", "amount", "1000", "fee", "0", "net", "1000",
            "status", "CASE WHEN n <= " + reviewing + " THEN '" + TransferStatus.REVIEW + "' ELSE '"
                + TransferStatus.REJECTED + "' END"));
        int owed = 2;
        rows("qb_cash_reward_version", "QbCashReward", HISTORY, null, n -> Map.of("cash_reward_id", "ids.id",
            "publisher_kind", "'SPONSOR'", "sponsor_id", text(UUID.randomUUID().toString()), "user_id", text(taker),
            "status", "CASE WHEN n <= " + owed + " THEN '" + CashRewardStatus.OVERDUE + "' ELSE '"
                + CashRewardStatus.CONFIRMED + "' END"));
        int vouchers = 4;
        rows("qb_redemption_version", "QbRedemption", HISTORY, null, n -> Map.of("redemption_id", "ids.id",
            "publisher_kind", "'SPONSOR'", "sponsor_id", text(UUID.randomUUID().toString()), "user_id", text(taker),
            "status", "CASE WHEN n <= " + vouchers + " THEN '" + RedemptionStatus.DUE + "' ELSE '"
                + RedemptionStatus.REDEEMED + "' END"));

        Map<String, ErasureNotice> found = noticesOf(taker);
        assertThat(found.get("qb.withdrawal-in-progress").params()).containsEntry("count", (long) reviewing);
        assertThat(found.get("qb.rewards-unclaimed").params()).containsEntry("count", (long) (owed + vouchers));
    }

    @Test
    void aSponsorWithManyInvoicesPrepaymentsAndTopUpsIsToldOfTheOpenOnes() {
        String sponsor = newSponsor(unique("s-fm15a-fees"));
        int unpaid = 2;
        // 1,000 each, 300 of them paid and 100 credited: 600 owed each.
        rows("qb_fee_invoice", null, HISTORY, null, n -> Map.of(
            "sponsor_id", text(sponsor), "period", "to_char(DATE '2010-01-01' + n * interval '1 month', 'YYYY-MM')",
            "billing_mode", "'PREPAID'", "amount", "1000", "tax", "0", "total", "1000",
            "paid_amount", "CASE WHEN n <= " + unpaid + " THEN 300 ELSE 0 END",
            "credit_applied", "CASE WHEN n <= " + unpaid + " THEN 100 ELSE 0 END",
            "status", "CASE WHEN n <= " + unpaid + " THEN 'PARTIAL' ELSE 'VOID' END"));
        int open = 1;
        rows("qb_prepayment", null, HISTORY, null, n -> Map.of(
            "sponsor_id", text(sponsor), "purpose", "'DISTRIBUTION'", "mode", "'PREPAID'",
            "amount", "2000", "remaining", "CASE WHEN n <= " + open + " THEN 1500 ELSE 0 END",
            "status", "CASE WHEN n <= " + open + " THEN 'OPEN' ELSE 'RELEASED' END",
            "released_at", "CASE WHEN n <= " + open + " THEN NULL ELSE TIMESTAMPTZ '2026-01-02T00:00:00Z' END"));
        int waiting = 1;
        rows("qb_topup", null, HISTORY, null, n -> Map.of(
            "sponsor_id", text(sponsor), "method", "'BANK'", "amount", "5000", "requested_amount", "5000",
            "target", "'CASH'", "transfer_code", "left(md5(CAST(ids.id AS text)), 10)",
            "status", "CASE WHEN n <= " + waiting + " THEN 'AWAITING' ELSE 'CANCELLED' END"));

        Map<String, ErasureNotice> found = noticesOf(sponsor);
        assertThat(found.get("qb.fee-unpaid").params()).containsEntry("count", (long) unpaid)
            .containsEntry("amount", String.valueOf(unpaid * 600));
        assertThat(found.get("qb.deposits-held").params()).containsEntry("count", (long) open)
            .containsEntry("amount", "1500");
        assertThat(found).containsKey("qb.gift-frozen");
        assertThat(found.get("qb.topup-pending").params()).containsEntry("count", (long) waiting);
    }

    @Test
    void aSponsorWithManyAiCreditsClosedIsToldWhatTheOpenOneHolds() {
        // F-M15c: one open AI credit a sponsor (V124's index), the others released: the sum reads the open one only.
        String sponsor = newSponsor(unique("s-fm15c-ai"));
        rows("qb_prepayment", null, HISTORY, null, n -> Map.of(
            "sponsor_id", text(sponsor), "purpose", "'AI'", "mode", "'PREPAID'",
            "amount", "2000", "remaining", "CASE WHEN n = 1 THEN 700 ELSE 0 END",
            "status", "CASE WHEN n = 1 THEN 'OPEN' ELSE 'RELEASED' END",
            "released_at", "CASE WHEN n = 1 THEN NULL ELSE TIMESTAMPTZ '2026-01-02T00:00:00Z' END"));
        assertThat(noticesOf(sponsor).get("qb.ai-credit").params()).containsEntry("amount", "700");
    }

    /**
     * Review L4: through the real processes (submitted and live, paused by the Sponsor, ended by an administrator, a
     * draft), the cards follow the publications' states, so counting running cards counts running publications.
     */
    @Test
    void theCardsFollowThePublicationsThroughTheirProcesses() {
        String sponsor = newSponsor(unique("s-fm15a-cards"));
        String token = sponsorToken(sponsor);
        livePublication(sponsor, "FM15a live");
        String paused = livePublication(sponsor, "FM15a paused");
        String ended = livePublication(sponsor, "FM15a ended");
        run("QB_PUBLICATION_PAUSE", token, body("publicationId", paused));
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fm15a-content", QbPermissions.ADMIN_CONTENT_WRITE),
            body("publicationId", ended, "reason", "F-M15 a"));
        String draft = (String) run("QB_PUBLICATION_SAVE", token, body("name", "FM15a draft"))
            .get("publicationId");

        List<Map<String, Object>> publications = latest("qb_publication_version", "publication_id",
            "sponsor_id = " + text(sponsor));
        assertThat(publications).hasSize(4);
        for (Map<String, Object> publication : publications) {
            List<Map<String, Object>> card = query("SELECT status FROM qb_publication_card WHERE publication_id = "
                + "CAST(? AS uuid)", String.valueOf(publication.get("publication_id")));
            if (String.valueOf(publication.get("publication_id")).equals(draft)) {
                assertThat(card).as("a draft has no card").isEmpty();
            } else {
                assertThat(card).singleElement().satisfies(row -> assertThat(row.get("status"))
                    .isEqualTo(publication.get("status")));
            }
        }
        long running = publications.stream()
            .filter(publication -> PublicationAdmin.ENDABLE.contains(String.valueOf(publication.get("status"))))
            .count();
        assertThat(running).isEqualTo(2);
        assertThat(noticesOf(sponsor).get("qb.publications-end").params()).containsEntry("count", running);
    }

    /**
     * Review L5: through the Sponsor's own session, as the page asks: the state lists the notices; asking without
     * acknowledging them is refused (422, one violation per notice), with all of them acknowledged it is accepted.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aSponsorWithManyPublicationsAsksThroughTheirSessionAndAcknowledgesTheNotices() {
        String name = unique("s-fm15a-asks");
        String sponsor = newSponsor(name);
        rows("qb_publication_version", "QbPublication", HISTORY, null, n -> Map.of("publication_id", "ids.id",
            "publisher_kind", "'SPONSOR'", "sponsor_id", text(sponsor), "reward_method", "'SPONSOR_CASH'",
            "audience", "'PUBLIC'", "status", "CASE WHEN n = 1 THEN 'LIVE' ELSE 'ENDED' END"));
        rows("qb_publication_card", null, HISTORY, "SELECT row_number() OVER () AS n, publication_id AS id "
            + "FROM qb_publication_version WHERE sponsor_id = " + text(sponsor), n -> Map.of(
            "publisher_kind", "'SPONSOR'", "publisher_id", text(sponsor), "reward_method", "'SPONSOR_CASH'",
            "audience", "'PUBLIC'", "status", "(SELECT p.status FROM qb_publication_version p "
                + "WHERE p.publication_id = ids.id)"));
        // A session at the Sponsor entry (as the page has); the request still checks the password.
        String bearer = "Bearer " + tokens.issue(new Actor(sponsor, null, Set.of(),
            Set.copyOf(QbRoles.of(QbRoles.SPONSOR).permissions()), clock.instant(), null, "sponsor", true)).token();

        Map<String, Object> status = client.get().uri("/api/auth/account/deletion")
            .header(HttpHeaders.AUTHORIZATION, bearer).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(status).containsEntry("available", true);
        List<String> codes = ((List<Map<String, Object>>) status.get("notices")).stream()
            .map(notice -> String.valueOf(notice.get("code"))).toList();
        assertThat(codes).contains("qb.publications-end");

        Map<String, Object> unread = ask(bearer, List.of()).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(codes(unread)).contains("ACCOUNT_DELETION_NOTICES", "erasure.notice.qb.publications-end");
        assertThat(query("SELECT * FROM sys_account_deletion WHERE user_id = CAST(? AS uuid)", sponsor)).isEmpty();

        ask(bearer, codes).expectStatus().isOk();
        assertThat(query("SELECT * FROM sys_account_deletion WHERE user_id = CAST(? AS uuid)", sponsor)).hasSize(1);
    }

    private WebTestClient.ResponseSpec ask(String bearer, List<String> acknowledged) {
        Map<String, Object> body = new HashMap<>();
        body.put("currentPassword", PASSWORD);
        body.put("acknowledgedNotices", acknowledged);
        return client.post().uri("/api/auth/account/deletion").header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    /** The notices the user is shown before asking, by code (the same read the request makes). */
    Map<String, ErasureNotice> noticesOf(String userId) {
        List<ErasureNotice> all = asTestRequest(notices.of(userId)).block();
        assertThat(all).isNotNull();
        return all.stream().collect(Collectors.toMap(ErasureNotice::code, Function.identity(), (a, b) -> a,
            LinkedHashMap::new));
    }

    static String text(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * {@code count} rows of {@code table}, numbered {@code n} from 1: the values given are SQL expressions (they may
     * use {@code n} and the key {@code ids.id}); every other required column without a default gets a plain value of
     * its type. The keys are new, or those {@code idsSql} selects (columns {@code n} and {@code id}). With
     * {@code entity}, the keys are registered as its instances under the latest operation; a temporal table
     * ({@code *_version}) gets version 1.
     */
    static void rows(String table, String entity, int count, String idsSql,
        Function<Integer, Map<String, String>> values) {
        Map<String, String> given = new LinkedHashMap<>(values.apply(0));
        List<Map<String, Object>> columns = query("SELECT column_name, data_type, character_maximum_length "
            + "FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? "
            + "AND is_nullable = 'NO' AND column_default IS NULL AND is_identity = 'NO' AND is_generated = 'NEVER' "
            + "ORDER BY ordinal_position", table);
        String key = String.valueOf(query("SELECT a.attname FROM pg_index i JOIN pg_attribute a "
            + "ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0] WHERE i.indrelid = CAST(? AS regclass) "
            + "AND i.indisprimary", table).getFirst().get("attname"));
        if (table.endsWith("_version")) {
            given.put("version_no", "1");
            given.put("process_seq_id", "(SELECT max(process_seq_id) FROM op_process)");
        }
        // A temporal table's key is the entity's id, given (its primary key is the row's number); else the primary key.
        if (!given.containsValue("ids.id")) {
            given.put(key, "ids.id");
        }
        List<String> names = new ArrayList<>();
        List<String> exprs = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String name = String.valueOf(column.get("column_name"));
            names.add(name);
            exprs.add(given.containsKey(name) ? given.remove(name) : plain(String.valueOf(column.get("data_type")),
                (Number) column.get("character_maximum_length")));
        }
        given.forEach((name, expr) -> {
            names.add(name);
            exprs.add(expr);
        });
        String ids = "it_ids_" + Math.abs(System.nanoTime() % 1_000_000);
        // An ordinary table: every statement here has a connection of its own.
        if (idsSql == null) {
            execute("CREATE TABLE " + ids + " AS SELECT n, gen_random_uuid() AS id "
                + "FROM generate_series(1, CAST(? AS int)) n", count);
        } else {
            execute("CREATE TABLE " + ids + " AS " + idsSql);
        }
        if (entity != null) {
            execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT id, ?, "
                + "(SELECT max(process_seq_id) FROM op_process) FROM " + ids, entity);
        }
        execute("INSERT INTO " + table + " (" + String.join(", ", names) + ") SELECT " + String.join(", ", exprs)
            + " FROM " + ids + " ids");
        execute("DROP TABLE " + ids);
    }

    private static String plain(String type, Number length) {
        return switch (type) {
            case "uuid" -> "gen_random_uuid()";
            case "boolean" -> "false";
            // Amounts must mostly be positive.
            case "smallint", "integer", "bigint", "numeric", "real", "double precision" -> "1";
            case "date" -> "DATE '2026-01-01'";
            case "timestamp with time zone", "timestamp without time zone" -> "TIMESTAMPTZ '2026-01-01T00:00:00Z'";
            case "jsonb", "json" -> "'{}'";
            // Unique where a column must be (the new key's text, cut to the column's length).
            default -> length == null ? "'x' || CAST(ids.id AS text)"
                : length.intValue() < 8 ? "'x'" : "left('x' || CAST(ids.id AS text), " + length.intValue() + ")";
        };
    }
}
