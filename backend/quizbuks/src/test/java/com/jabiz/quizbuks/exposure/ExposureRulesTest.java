package com.jabiz.quizbuks.exposure;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules of counting exposures and of the pushes (F-S13): the server's {@link ExposureRules} passes the same cases as
 * the App's {@code exposure/rules.ts} ({@code quizbuks-web/app/src/exposure/exposure-cases.json}, also read by
 * {@code exposure.test.ts}; the test runs in backend/quizbuks): ten seconds, a shorter video, opened never pushed again,
 * a horizontal video once per taker and publication on the start page, the publisher, the provider and their staff not
 * counted, the daily cap, the places of the pushes, what is charged and the reconciliation's tolerance.
 */
class ExposureRulesTest {

    private static final Path CASES = Path.of("../../quizbuks-web/app/src/exposure/exposure-cases.json");

    private static JsonNode cases() throws IOException {
        return JsonMapper.builder().build().readTree(Files.readString(CASES));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static Integer integer(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }

    private static Long number(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static ExposureRules.Card card(JsonNode node) {
        return new ExposureRules.Card(text(node.get("publisherId")), text(node.get("providerId")),
            node.get("sharedVideo").asBoolean(), node.get("vertical").asBoolean(), node.get("live").asBoolean(),
            integer(node.get("durationSec")));
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(item -> out.add(item.asString()));
        return out;
    }

    @Test
    void theSecondsToWatchAreTheParameterOrAShorterVideo() throws IOException {
        JsonNode rows = cases().get("neededSec");
        assertThat(rows.size()).isGreaterThan(0);
        rows.forEach(row -> assertThat(ExposureRules.neededSec(row.get("seconds").asInt(),
            integer(row.get("durationSec")))).isEqualTo(row.get("needed").asInt()));
    }

    @Test
    void eachReportIsDecidedAsTheAppDecidesIt() throws IOException {
        JsonNode rows = cases().get("decide");
        assertThat(rows.size()).isGreaterThan(10);
        rows.forEach(row -> assertThat(ExposureRules.decide(card(row.get("card")), text(row.get("viewer")),
            strings(row.get("staffOf")), text(row.get("surface")), row.get("reportedSec").asInt(),
            row.get("seconds").asInt(), row.get("alreadyCounted").asBoolean(), row.get("countedToday").asInt(),
            row.get("cap").asInt())).as(text(row.get("name"))).isEqualTo(text(row.get("outcome"))));
    }

    @Test
    void openedOnceIsNeverPushedAgain() throws IOException {
        JsonNode rows = cases().get("pushable");
        assertThat(rows.size()).isGreaterThan(0);
        rows.forEach(row -> assertThat(ExposureRules.pushable(card(row.get("card")), row.get("opened").asBoolean()))
            .as(text(row.get("name"))).isEqualTo(row.get("pushable").asBoolean()));
    }

    @Test
    void thePushesStandAtEveryNthScreen() throws IOException {
        cases().get("pushAt").forEach(row -> assertThat(ExposureRules.pushAt(row.get("offset").asInt(),
            row.get("every").asInt())).as(row.toString()).isEqualTo(row.get("push").asBoolean()));
    }

    @Test
    void beyondThePurchasedIsRecordedButNotCharged() throws IOException {
        cases().get("billing").forEach(row -> {
            long successes = row.get("successes").asLong();
            Long purchased = number(row.get("purchased"));
            assertThat(ExposureRules.usedUp(successes, purchased)).as(row.toString())
                .isEqualTo(row.get("usedUp").asBoolean());
            assertThat(ExposureRules.billable(successes, purchased)).as(row.toString())
                .isEqualTo(row.get("billable").asLong());
            assertThat(ExposureRules.overshoot(successes, purchased)).as(row.toString())
                .isEqualTo(row.get("overshoot").asLong());
            assertThat(ExposureRules.reconciles(successes, purchased)).as(row.toString())
                .isEqualTo(row.get("reconciles").asBoolean());
        });
        assertThat(ExposureRules.billableRank(2, 2L)).isTrue();
        assertThat(ExposureRules.billableRank(3, 2L)).isFalse();
        assertThat(ExposureRules.billableRank(3_000, null)).isTrue();
    }

    @Test
    void theParametersAreReadWithinTheirBounds() throws IOException {
        JsonNode params = cases().get("params");
        check(params.get("seconds"), ExposureRules::seconds);
        check(params.get("dailyCap"), ExposureRules::dailyCap);
        check(params.get("pushEvery"), ExposureRules::pushEvery);
        check(params.get("reportBatch"), ExposureRules::reportBatch);
        check(params.get("reportIntervalSec"), ExposureRules::reportIntervalSec);
    }

    private static void check(JsonNode pairs, Function<Integer, Integer> read) {
        assertThat(pairs.size()).isGreaterThan(0);
        pairs.forEach(pair -> assertThat(read.apply(integer(pair.get(0)))).as(pair.toString())
            .isEqualTo(pair.get(1).asInt()));
    }

    @Test
    void theSurfacesFitTheOrientation() {
        assertThat(ExposureRules.fits(ExposureRules.VERTICAL, true)).isTrue();
        assertThat(ExposureRules.fits(ExposureRules.VERTICAL, false)).isFalse();
        assertThat(ExposureRules.fits(ExposureRules.START, false)).isTrue();
        assertThat(ExposureRules.fits(ExposureRules.START, true)).isFalse();
        assertThat(ExposureRules.fits("HOME", false)).isFalse();
        assertThat(ExposureRules.excluded(null, List.of(), new ExposureRules.Card("p", "v", true, true, true, null)))
            .isTrue();
        assertThat(ExposureRules.tolerance(null)).isZero();
    }
}
