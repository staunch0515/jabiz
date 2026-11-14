package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.reward.CashRewardStatus;
import com.jabiz.quizbuks.reward.RedemptionStatus;
import com.jabiz.quizbuks.wallet.TransferStatus;
import com.jabiz.runtime.erasure.ErasureNotices;
import com.jabiz.security.erasure.ErasureNotice;
import com.jabiz.security.erasure.ErasureSubject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Test
    void aSponsorWithManyPublicationsAndCashRewardsCanStillAsk() {
        String sponsor = newSponsor(unique("s-fm15a-many"));
        int live = 2;
        rows("qb_publication_card", null, HISTORY, n -> Map.of(
            "publisher_kind", "'SPONSOR'", "publisher_id", text(sponsor),
            "status", "CASE WHEN n <= " + live + " THEN 'LIVE' ELSE 'ENDED' END"));
        int owed = 3;
        rows("qb_cash_reward_version", "QbCashReward", HISTORY, n -> Map.of(
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
        int sending = 1;
        rows("qb_transfer", null, HISTORY, n -> Map.of(
            "user_id", text(taker),
            "status", "CASE WHEN n <= " + sending + " THEN '" + TransferStatus.SENDING + "' ELSE '"
                + TransferStatus.SENT + "' END"));
        int owed = 2;
        rows("qb_cash_reward_version", "QbCashReward", HISTORY, n -> Map.of(
            "publisher_kind", "'SPONSOR'", "sponsor_id", text(UUID.randomUUID().toString()), "user_id", text(taker),
            "status", "CASE WHEN n <= " + owed + " THEN '" + CashRewardStatus.OVERDUE + "' ELSE '"
                + CashRewardStatus.CONFIRMED + "' END"));
        int vouchers = 4;
        rows("qb_redemption_version", "QbRedemption", HISTORY, n -> Map.of(
            "publisher_kind", "'SPONSOR'", "sponsor_id", text(UUID.randomUUID().toString()), "user_id", text(taker),
            "status", "CASE WHEN n <= " + vouchers + " THEN '" + RedemptionStatus.DUE + "' ELSE '"
                + RedemptionStatus.REDEEMED + "' END"));

        Map<String, ErasureNotice> found = noticesOf(taker);
        assertThat(found.get("qb.withdrawal-in-progress").params()).containsEntry("count", (long) sending);
        assertThat(found.get("qb.rewards-unclaimed").params()).containsEntry("count", (long) (owed + vouchers));
    }

    /** The notices the user is shown before asking, by code (the same read the request makes). */
    Map<String, ErasureNotice> noticesOf(String userId) {
        List<ErasureNotice> all = notices.of(userId).block();
        assertThat(all).isNotNull();
        return all.stream().collect(Collectors.toMap(ErasureNotice::code, Function.identity(), (a, b) -> a,
            LinkedHashMap::new));
    }

    static String text(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * {@code count} rows of {@code table}, numbered {@code n} from 1: the values given are SQL expressions (they may
     * use {@code n}); every other required column without a default gets a plain value of its type. A temporal table
     * ({@code entity} not null) gets version 1 of new instances, registered under the latest operation.
     */
    static void rows(String table, String entity, int count, Function<Integer, Map<String, String>> values) {
        Map<String, String> given = new LinkedHashMap<>(values.apply(0));
        List<Map<String, Object>> columns = query("SELECT column_name, data_type, character_maximum_length "
            + "FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? "
            + "AND is_nullable = 'NO' AND column_default IS NULL ORDER BY ordinal_position", table);
        String key = String.valueOf(query("SELECT a.attname FROM pg_index i JOIN pg_attribute a "
            + "ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0] WHERE i.indrelid = CAST(? AS regclass) "
            + "AND i.indisprimary", table).getFirst().get("attname"));
        if (entity != null) {
            given.put("version_no", "1");
            given.put("process_seq_id", "(SELECT max(process_seq_id) FROM op_process)");
        }
        given.put(key, "ids.id");
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
        execute("CREATE TEMP TABLE " + ids + " AS SELECT n, gen_random_uuid() AS id FROM generate_series(1, CAST(? AS int)) n",
            count);
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
            case "smallint", "integer", "bigint", "numeric", "real", "double precision" -> "0";
            case "date" -> "DATE '2026-01-01'";
            case "timestamp with time zone", "timestamp without time zone" -> "TIMESTAMPTZ '2026-01-01T00:00:00Z'";
            case "jsonb", "json" -> "'{}'";
            default -> length != null && length.intValue() < 8 ? "'x'" : "'x' || n";
        };
    }
}
