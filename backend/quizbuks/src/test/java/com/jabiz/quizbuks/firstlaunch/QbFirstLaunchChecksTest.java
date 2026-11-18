package com.jabiz.quizbuks.firstlaunch;

import com.jabiz.firstlaunch.FirstLaunchAccount;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.quizbuks.setup.FirstLaunchParams;
import com.jabiz.quizbuks.setup.FirstLaunchSwitches;
import com.jabiz.quizbuks.setup.QbApprovalRules;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-LAUNCH Q3: the checks of the QuizBuks sections (plan section 6.2) find every problem at once, with its place;
 * the example's package passes them; the controls the run publishes are the money switches that take effect, the
 * settable parameters and the approval rules, never an amount or a switch that waits for a decision.
 */
@SuppressWarnings("unchecked")
class QbFirstLaunchChecksTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant LAUNCH = Instant.parse("2026-11-18T00:00:00Z");

    private static String template() {
        try (InputStream in = QbFirstLaunchChecksTest.class.getResourceAsStream(
            "/first-launch/example/sponsors/package.template.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("__JPEG__", "/9j/AAAA")
                .replace("__SIZE__", "4").replace("__HANDLE__", "shop").replace("__COMPANY__", "Shop")
                .replace("__CONTACT__", "Ann");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FirstLaunchConfig config(Map<String, Object> document) {
        List<FirstLaunchAccount> accounts = List.of(
            new FirstLaunchAccount("A", "a@example.co.jp", "A", "ja", List.of("QB_ADMIN_SUPER"), true),
            new FirstLaunchAccount("B", "b@example.co.jp", "B", "ja", List.of("QB_OPERATOR"), false),
            new FirstLaunchAccount("C", "c@example.co.jp", "C", "ja", List.of("QB_OPERATOR"), false));
        return new FirstLaunchConfig("0".repeat(64), document, "en", List.of("example.co.jp"), accounts,
            new FirstLaunchConfig.Files() {
                @Override
                public byte[] read(String relativePath) {
                    return template().getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public boolean exists(String relativePath) {
                    return relativePath.endsWith("package.json");
                }
            });
    }

    private static Map<String, Object> publication(String name, String method, String end) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("rewardMethod", method);
        extra.put("redeemDescription", "At the till.");
        extra.put("startAt", "launch");
        extra.put("endAt", end);
        return Map.of(name, extra);
    }

    private static Map<String, Object> valid() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("operators", List.of(
            Map.of("key", "tokyo", "name", "Tokyo", "default", true, "lead", "B", "members", List.of("A")),
            Map.of("key", "kansai", "name", "Kansai", "lead", "C")));
        document.put("sponsors", List.of(
            Map.of("operator", "tokyo", "package", "sponsors/tokyo/package.json", "rewardMethods",
                List.of("OFFLINE_REDEEM"), "publications", publication("Spring quiz", "OFFLINE_REDEEM",
                    "2026-12-31T23:59+09:00")),
            Map.of("operator", "kansai", "package", "sponsors/kansai/package.json", "rewardMethods",
                List.of("OFFLINE_REDEEM"), "publications", publication("Spring quiz", "OFFLINE_REDEEM",
                    "2026-12-31T23:59+09:00"))));
        document.put("params", Map.of(QbParams.AI_SPONSOR_DAILY_JOBS, 5));
        return document;
    }

    private static List<QbFirstLaunchChecks.Problem> check(Map<String, Object> document) {
        FirstLaunchConfig config = config(document);
        return QbFirstLaunchChecks.check(config, QbFirstLaunchConfig.of(config),
            path -> config.files().exists(path) ? QbPackages.read(config.files().read(path), JSON) : null, LAUNCH);
    }

    private static List<String> codes(List<QbFirstLaunchChecks.Problem> problems) {
        return problems.stream().map(QbFirstLaunchChecks.Problem::code).toList();
    }

    @Test
    void theExampleHasNoProblem() {
        assertThat(check(valid())).isEmpty();
    }

    @Test
    void everyProblemIsFoundAtOnceWithItsPlace() {
        Map<String, Object> document = new LinkedHashMap<>(valid());
        document.put("operators", List.of(
            Map.of("key", "tokyo", "name", "", "default", true, "lead", "Z", "members", List.of("B")),
            Map.of("key", "tokyo", "name", "Kansai", "default", true, "lead", "C", "members", List.of("C")),
            Map.of("key", "osaka", "name", "Osaka", "lead", "C")));
        document.put("sponsors", List.of(
            Map.of("operator", "tokyo", "package", "sponsors/tokyo/package.json", "rewardMethods",
                List.of("OFFLINE_REDEEM", "PLATFORM"), "publications", publication("Spring quiz", "PLATFORM",
                    "2028-12-31T23:59+09:00")),
            Map.of("operator", "nowhere", "package", "sponsors/none.json", "rewardMethods",
                List.of("OFFLINE_REDEEM"), "publications", publication("Autumn", "OFFLINE_REDEEM", "soon"))));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(QbParams.KUDOS_BUY_ENABLED, true);
        params.put(QbParams.REWARD_DEFAULT_METHODS, "PLATFORM");
        params.put("qb.no.such", "x");
        params.put(QbParams.REVIEW_SPONSOR_AUTO, "yes");
        document.put("params", params);

        List<QbFirstLaunchChecks.Problem> problems = check(document);

        assertThat(codes(problems)).contains("operatorName", "account", "operatorKey", "accountTwice",
            "operatorSponsor", "operatorDefault", "rewardMethods", "rewardMethod", "running", "sponsorOperator",
            "packageMissing", "paramFixed", "paramRefused", "paramNotSettable", "paramValue");
        assertThat(problems).anySatisfy(problem -> {
            assertThat(problem.code()).isEqualTo("account");
            assertThat(problem.location()).isEqualTo("operators[0].lead");
            assertThat(problem.params()).containsEntry("key", "Z");
        });
        assertThat(problems).anySatisfy(problem -> assertThat(problem.location())
            .isEqualTo("sponsors[0].publications.Spring quiz.rewardMethod"));
    }

    @Test
    void anAccountBelongsToOneOperatorOnly() {
        Map<String, Object> document = new LinkedHashMap<>(valid());
        document.put("operators", List.of(
            Map.of("key", "tokyo", "name", "Tokyo", "default", true, "lead", "B", "members", List.of("A")),
            Map.of("key", "kansai", "name", "Kansai", "lead", "C", "members", List.of("B"))));
        assertThat(check(document)).anySatisfy(problem -> {
            assertThat(problem.code()).isEqualTo("accountTwice");
            assertThat(problem.location()).isEqualTo("operators[1].members[0]");
        });
    }

    @Test
    void parameterValuesAreCheckedAsTheProposalChecksThem() {
        Map<String, Object> document = new LinkedHashMap<>(valid());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(QbParams.AI_SPONSOR_DAILY_JOBS, "lots");
        params.put(QbParams.SUPPORT_EMAIL, "help@elsewhere.example");
        document.put("params", params);
        List<QbFirstLaunchChecks.Problem> problems = check(document);
        assertThat(problems).anySatisfy(problem -> {
            assertThat(problem.code()).isEqualTo("paramValue");
            assertThat(problem.location()).isEqualTo("params." + QbParams.AI_SPONSOR_DAILY_JOBS);
        });
        assertThat(codes(problems)).contains("supportEmail");
        params.put(QbParams.SUPPORT_EMAIL, "help@example.co.jp");
        params.put(QbParams.AI_SPONSOR_DAILY_JOBS, 5);
        assertThat(check(document)).isEmpty();
    }

    @Test
    void misspeltKeysAndWrongShapesAreRefused() {
        Map<String, Object> document = new LinkedHashMap<>(valid());
        List<Object> operators = new ArrayList<>((List<?>) document.get("operators"));
        Map<String, Object> tokyo = new LinkedHashMap<>((Map<String, Object>) operators.get(0));
        tokyo.put("membres", List.of());
        operators.set(0, tokyo);
        document.put("operators", operators);
        List<Object> sponsors = new ArrayList<>((List<?>) document.get("sponsors"));
        sponsors.set(1, Map.of("operator", "kansai", "package", "sponsors/kansai/package.json", "rewardMethods",
            List.of("OFFLINE_REDEEM"), "publications", Map.of("Spring quiz", Map.of("rewardMethod",
                "OFFLINE_REDEEM", "redeemDescription", "x", "startAt", "launch", "endAt", "2026-12-31T23:59+09:00",
                "endsAt", "2027-01-01T00:00+09:00"))));
        document.put("sponsors", sponsors);
        document.put("params", List.of("qb.ai.sponsor.daily-jobs"));
        List<QbFirstLaunchChecks.Problem> problems = check(document);
        assertThat(problems).extracting(QbFirstLaunchChecks.Problem::location).contains("operators[0].membres",
            "sponsors[1].publications.Spring quiz.endsAt", "params");
        assertThat(codes(problems)).contains("unknownKey", "shape");
    }

    @Test
    void aPublicationThatMovesMoneyOrIsUnconfiguredIsRefused() {
        Map<String, Object> document = new LinkedHashMap<>(valid());
        List<Object> sponsors = new ArrayList<>((List<?>) document.get("sponsors"));
        sponsors.set(0, Map.of("operator", "tokyo", "package", "sponsors/tokyo/package.json", "rewardMethods",
            List.of("OFFLINE_REDEEM"), "publications", publication("Other", "OFFLINE_REDEEM",
                "2026-12-31T23:59+09:00")));
        document.put("sponsors", sponsors);
        assertThat(codes(check(document))).contains("publicationMissing", "publicationUnknown");

        String paid = template().replace("\"budgetCap\": null", "\"budgetCap\": 1000")
            .replace("\"sharedVideo\": null", "\"sharedVideo\": \"v1\"");
        QbPackages.Package pkg = QbPackages.read(paid, JSON);
        FirstLaunchConfig config = config(valid());
        List<QbFirstLaunchChecks.Problem> problems = QbFirstLaunchChecks.check(config, QbFirstLaunchConfig.of(config),
            path -> pkg, LAUNCH);
        assertThat(codes(problems)).contains("money", "audience");
    }

    @Test
    void aPackageWithALinkOrAMailServerIsRefused() {
        String linked = template().replace("\"cover\": \"cover\"", "\"cover\": { \"url\": \"https://img.example/c.jpg\" }")
            .replace("\"products\": []", "\"products\": [], \"mailServer\": { \"host\": \"smtp.example\", \"port\": 587, "
                + "\"tls\": \"STARTTLS\", \"username\": \"shop\", \"fromAddress\": \"shop@example.com\" }");
        QbPackages.Package pkg = QbPackages.read(linked, JSON);
        FirstLaunchConfig config = config(valid());
        assertThat(codes(QbFirstLaunchChecks.check(config, QbFirstLaunchConfig.of(config), path -> pkg, LAUNCH)))
            .contains("link", "profileMail");
        QbPackages.Package broken = QbPackages.read("{ not json", JSON);
        assertThat(codes(QbFirstLaunchChecks.check(config, QbFirstLaunchConfig.of(config), path -> broken, LAUNCH)))
            .contains("packageRead");
    }

    @Test
    void theFilesOfEachStepAreItsImagesUnderTheirPolicies() {
        QbPackages.Package pkg = QbPackages.read(template(), JSON);
        Map<String, List<QbPackages.Upload>> uploads = pkg.uploads();
        assertThat(uploads.values().stream().flatMap(List::stream).map(QbPackages.Upload::policy))
            .containsExactlyInAnyOrder("qb.sponsor-avatar", "qb.sponsor-page-image", "qb.content.image");
        uploads.values().stream().flatMap(List::stream)
            .forEach(upload -> assertThat(pkg.bytes(upload)).as(upload.key()).isNotEmpty());
    }

    @Test
    void theRunPublishesNoAmountAndNoSwitchThatWaits() {
        QbFirstLaunch handler = new QbFirstLaunch(JSON, null, java.time.Clock.systemUTC());
        var keys = handler.publishableControlKeys();
        QbApprovalRules.ALL.forEach(rule -> assertThat(keys).contains(rule.code()));
        assertThat(keys).contains(QbParams.KUDOS_BUY_ENABLED, QbParams.REVIEW_SPONSOR_AUTO,
            QbParams.REVIEW_PUBLICATION_AUTO);
        FirstLaunchSwitches.ALL.stream().filter(row -> row.prerequisite() != null)
            .forEach(row -> assertThat(keys).doesNotContain(row.key()));
        // An amount only at the value the money switches' table gives for the launch (the fees at 0).
        keys.stream().filter(key -> FirstLaunchParams.verdict(key) == FirstLaunchParams.Verdict.AMOUNT)
            .forEach(key -> assertThat(QbFirstLaunch.switchesAtLaunch()).as(key).containsKey(key));
        // Every parameter the run publishes is one QB_SETUP proposes.
        keys.stream().filter(key -> QbParams.ALL.stream().anyMatch(param -> param.key().equals(key)))
            .forEach(key -> assertThat(QbParams.CONTROLLED).as(key).contains(key));
    }
}
