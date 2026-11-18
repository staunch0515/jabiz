package com.jabiz.quizbuks.it;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.FirstLaunchExit;
import com.jabiz.runtime.firstlaunch.FirstLaunchGate;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-LAUNCH Q3 (docs/quizbuks/plans/impl/F-LAUNCH.md section 9): the example configuration
 * ({@code first-launch/example/}) on a new database. The content phase makes the accounts, runs {@code QB_SETUP},
 * publishes the money switches at their first launch's values, the configured parameter and the approval rules,
 * makes the two operators with their leads as their own sponsors, imports each sponsor's package as that sponsor
 * (its images stored as uploaded by the sponsor), submits the pages and graphs, approves them, waits for them, then
 * the publications: the application opens with everything live, and no money moved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.first-launch.await-interval=100ms", "jabiz.events.delivery.enabled=true"})
@Import(QbFirstLaunchIT.Exits.class)
class QbFirstLaunchIT extends PostgresIntegrationTest {

    static final List<Integer> EXITS = new CopyOnWriteArrayList<>();
    private static Path directory;

    @TestConfiguration
    static class Exits {
        @Bean
        FirstLaunchExit qbFirstLaunchItExit() {
            return EXITS::add;
        }

        /**
         * Stands in for the platform's account steps of 17zz23c (P3, not merged yet): each account made without a
         * password or an invitation, as P3 makes them. Removed once P3 is merged into this line.
         */
        @Bean
        com.jabiz.runtime.firstlaunch.FirstLaunchPlatformSteps qbFirstLaunchItAccounts() {
            return new com.jabiz.runtime.firstlaunch.FirstLaunchPlatformSteps() {
                @Override
                public java.util.List<com.jabiz.firstlaunch.FirstLaunchProblem> validate(
                    com.jabiz.firstlaunch.FirstLaunchConfig config) {
                    return java.util.List.of();
                }

                @Override
                public java.util.List<com.jabiz.firstlaunch.FirstLaunchStep> plan(
                    com.jabiz.firstlaunch.FirstLaunchConfig config) {
                    return config.accounts().stream().map(account -> com.jabiz.firstlaunch.FirstLaunchStep.run(
                        com.jabiz.firstlaunch.FirstLaunchStep.accountStepKey(account.key()), "SEC_USER_CREATE",
                        results -> new com.jabiz.runtime.security.UserProcesses.CreateUserInput(
                            account.email(), account.name(), null, null, true, account.email(),
                            account.language(), false))).toList();
                }

                @Override
                public int getOrder() {
                    return 0;
                }
            };
        }
    }

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        registry.add("jabiz.first-launch.config", QbFirstLaunchIT::example);
    }

    /** The example written out, its packages made from the template with a real image. */
    static String example() {
        try {
            directory = Files.createTempDirectory("qb-first-launch");
            Files.createDirectories(directory.resolve("sponsors/tokyo"));
            Files.createDirectories(directory.resolve("sponsors/kansai"));
            Files.writeString(directory.resolve("first-launch.yml"), resource("first-launch.yml"));
            byte[] jpeg = jpeg();
            String template = resource("sponsors/package.template.json")
                .replace("__JPEG__", Base64.getEncoder().encodeToString(jpeg))
                .replace("__SIZE__", String.valueOf(jpeg.length));
            Files.writeString(directory.resolve("sponsors/tokyo/package.json"), template
                .replace("__HANDLE__", "tokyo-direct").replace("__COMPANY__", "Tokyo Direct")
                .replace("__CONTACT__", "Misaki Tanaka"));
            Files.writeString(directory.resolve("sponsors/kansai/package.json"), template
                .replace("__HANDLE__", "kansai-direct").replace("__COMPANY__", "Kansai Direct")
                .replace("__CONTACT__", "Ken Yamada"));
            return directory.resolve("first-launch.yml").toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = QbFirstLaunchIT.class.getResourceAsStream("/first-launch/example/" + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] jpeg() throws IOException {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(240, 160, 190));
        graphics.fillRect(0, 0, 64, 48);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (directory != null) {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Autowired
    FirstLaunchGate gate;

    @Autowired
    FirstLaunchRuns runs;

    private static final RequestContext SYSTEM = RequestContext.system(Locale.ENGLISH, "it");

    private FirstLaunchRun run() {
        return runs.current().contextWrite(view -> RequestContexts.put(view, SYSTEM)).block().orElseThrow();
    }

    private void awaitContent() {
        long end = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (!gate.isOpen()) {
            if (!EXITS.isEmpty() || System.nanoTime() > end) {
                throw new AssertionError("The content phase did not finish (exits " + EXITS + "): "
                    + query("SELECT kind, step_key, reason_code, reason FROM sys_first_launch_event ORDER BY event_time"));
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static List<Map<String, Object>> latest(String table, String key, String where) {
        return query("SELECT * FROM (SELECT DISTINCT ON (" + key + ") * FROM " + table + " ORDER BY " + key
            + ", effect_start_time DESC, version_no DESC) v WHERE " + where);
    }

    private static String userOf(String email) {
        return String.valueOf(latest("sec_user_version", "user_id", "email = '" + email + "'").getFirst()
            .get("user_id"));
    }

    private static Object param(String key) {
        List<Map<String, Object>> rows = latest("sys_param_version", "param_key", "param_key = '" + key + "'");
        return rows.isEmpty() ? null : rows.getFirst().get("param_value");
    }

    @Test
    void theExampleLaunchesEverythingLiveAndMovesNoMoney() {
        awaitContent();
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.CONTENT_DONE);
        assertThat(EXITS).isEmpty();

        // Two operators, the first the default, each with its lead as its own sponsor.
        List<Map<String, Object>> operators = query("SELECT * FROM qb_operator");
        assertThat(operators).extracting(row -> row.get("name"))
            .containsExactlyInAnyOrder("Tokyo Operations", "Kansai Operations");
        assertThat(operators).filteredOn(row -> Boolean.TRUE.equals(row.get("default_for_new")))
            .extracting(row -> row.get("name")).containsExactly("Tokyo Operations");
        String tokyo = userOf("b.tanaka@example.co.jp");
        String kansai = userOf("c.yamada@example.co.jp");
        assertThat(operators).extracting(row -> String.valueOf(row.get("direct_sponsor_id")))
            .containsExactlyInAnyOrder(tokyo, kansai);

        // The pages are published, the graphs in effect and the publications live, all the sponsors' own.
        assertThat(query("SELECT * FROM qb_sponsor_page")).extracting(row -> row.get("status"))
            .containsOnly("PUBLISHED").hasSize(2);
        assertThat(query("SELECT * FROM qb_tree_version WHERE status = 'ACTIVE'")).hasSize(2);
        List<Map<String, Object>> publications = latest("qb_publication_version", "publication_id", "true");
        assertThat(publications).extracting(row -> row.get("status")).containsOnly("LIVE").hasSize(2);
        assertThat(publications).extracting(row -> row.get("reward_method")).containsOnly("OFFLINE_REDEEM");
        assertThat(publications).extracting(row -> String.valueOf(row.get("sponsor_id")))
            .containsExactlyInAnyOrder(tokyo, kansai);

        // The images are stored as uploaded by the sponsors (avatar, cover and the question's image each).
        assertThat(query("SELECT uploaded_by FROM sys_file")).extracting(row -> String.valueOf(row.get("uploaded_by")))
            .hasSize(6).containsOnly(tokyo, kansai);

        // The configured parameter and a money switch took effect; a switch that waits for a decision did not.
        assertThat(String.valueOf(param("qb.ai.sponsor.daily-jobs"))).contains("5");
        assertThat(String.valueOf(param("qb.kudos.buy.enabled"))).contains("false");
        assertThat(param("qb.ai.enabled")).isNull();

        // Every approval the run asked for was decided by the first launch, level by level.
        assertThat(query("SELECT DISTINCT approver_id FROM sys_approval_decision"))
            .extracting(row -> String.valueOf(row.get("approver_id"))).allMatch(id -> id.startsWith("first-launch:"));

        // Nothing moved money (plan section 4).
        assertThat(query("SELECT 1 FROM ledger_transaction_version")).isEmpty();
    }
}
