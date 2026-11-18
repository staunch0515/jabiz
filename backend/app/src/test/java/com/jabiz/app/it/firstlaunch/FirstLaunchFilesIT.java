package com.jabiz.app.it.firstlaunch;

import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.app.it.fixture.ItFileFixtures;
import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.FirstLaunchExit;
import com.jabiz.runtime.firstlaunch.FirstLaunchFiles;
import com.jabiz.runtime.firstlaunch.FirstLaunchGate;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.security.UserProcesses;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first launch's file steps (decision D82, docs/design/23-first-launch.md section 6): a file of the
 * configuration's directory, picked out of a package, goes through the upload pipeline as the upload of an account
 * the run created. Paths that leave the directory or go through a link, content that is not what the policy allows,
 * a file too large for the policy, an undeclared policy, an account the run did not create, and any file step once
 * the content phase is over are refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.first-launch.await-interval=50ms")
@Import({FirstLaunchFilesIT.Handler.class, ItFileFixtures.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstLaunchFilesIT extends PostgresIntegrationTest {

    static final CountDownLatch RELEASE = new CountDownLatch(1);
    static final List<Integer> EXITS = new CopyOnWriteArrayList<>();
    static volatile boolean holding;
    static Path directory;

    static byte[] png(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A JPEG carrying an EXIF segment with a GPS marker, which the pipeline must not keep. */
    static byte[] jpegWithExif() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        try {
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", plain);
            byte[] jpeg = plain.toByteArray();
            byte[] payload = ("Exif\0\0" + GPS_MARKER).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(jpeg, 0, 2);
            out.write(0xFF);
            out.write(0xE1);
            out.write((payload.length + 2) >> 8);
            out.write((payload.length + 2) & 0xFF);
            out.write(payload);
            out.write(jpeg, 2, jpeg.length - 2);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The objects of the file store, by path. */
    private Set<Path> objects() throws IOException {
        try (var walk = Files.walk(filesRoot())) {
            return walk.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toSet());
        }
    }

    static final String GPS_MARKER = "GPSLatitude-first-launch-35.6812";

    @org.junit.jupiter.api.AfterAll
    static void cleanUp() throws IOException {
        if (directory != null && Files.exists(directory)) {
            try (var walk = Files.walk(directory)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        registry.add("jabiz.first-launch.config", () -> {
            try {
                directory = Files.createTempDirectory("first-launch-files");
                // The file steps store as an account of the configuration, made by an earlier step.
                Files.writeString(directory.resolve("first-launch.yml"), """
                    version: 1
                    allowedEmailDomains: [example.co.jp]
                    accounts:
                      - key: owner
                        email: owner@example.co.jp
                        name: Owner
                        roles: [ADMIN]
                        report: true
                    """);
                Files.createDirectories(directory.resolve("sponsors/hq"));
                // A package with the image inside, as the sponsor page exports it.
                Files.writeString(directory.resolve("sponsors/hq/package.json"), "{\"img1\":\""
                    + Base64.getEncoder().encodeToString(png(8)) + "\",\"fake\":\""
                    + Base64.getEncoder().encodeToString("not an image at all".getBytes()) + "\",\"gps\":\""
                    + Base64.getEncoder().encodeToString(jpegWithExif()) + "\"}");
                Files.write(directory.resolve("big.bin"), new byte[70 * 1024]);
                return directory.resolve("first-launch.yml").toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** The image under {@code key} of the package file: the handler picks it out, the platform reads the file. */
    static Function<byte[], byte[]> fromPackage(String key) {
        return bytes -> {
            String json = new String(bytes);
            int start = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
            return Base64.getDecoder().decode(json.substring(start, json.indexOf('"', start)));
        };
    }

    public record Ask() {}

    @TestConfiguration
    static class Handler {

        @Bean
        ProcessDefinition<Ask, Boolean, ProcessContext> firstLaunchItHoldFiles() {
            return ProcessDefinition.define("FL_IT_HOLD_FILES", 1, Ask.class, Boolean.class, ProcessContext.class,
                pb -> pb
                    .permissions("test.first-launch")
                    .contextFactory((start, input) -> new ProcessContext(start))
                    .compute("Hold", (metadata, ctx) -> {
                        holding = true;
                        ctx.put("done", RELEASE.getCount() == 0);
                    })
                    .outputMapper(ctx -> ctx.get("done", Boolean.class)));
        }

        @Bean
        FirstLaunchHandler firstLaunchItHandler() {
            return new FirstLaunchHandler() {
                @Override
                public String name() {
                    return "it";
                }

                @Override
                public List<FirstLaunchProblem> validate(FirstLaunchConfig config) {
                    return List.of();
                }

                @Override
                public Set<String> filePolicies() {
                    return Set.of(CommerceFiles.IMAGE, ItFileFixtures.TINY, ItFileFixtures.PRIVATE);
                }

                @Override
                public List<FirstLaunchStep> plan(FirstLaunchConfig config) {
                    return List.of(
                        FirstLaunchStep.run(FirstLaunchStep.accountStepKey("owner"), "SEC_USER_CREATE", results ->
                            new UserProcesses.CreateUserInput("fl-owner", null, null, null, true, null, null, false)),
                        FirstLaunchStep.file("img1", new FirstLaunchStep.FileSource(CommerceFiles.IMAGE,
                            "sponsors/hq/package.json", fromPackage("img1"), "picture")).asAccount("owner"),
                        FirstLaunchStep.await("hold", "FL_IT_HOLD_FILES", results -> new Ask()));
                }
            };
        }

        @Bean
        FirstLaunchExit firstLaunchItExit() {
            return EXITS::add;
        }
    }

    @Autowired
    FirstLaunchFiles files;

    @Autowired
    FirstLaunchRuns runs;

    @Autowired
    FirstLaunchGate gate;

    private FirstLaunchRun run() {
        return runs.current().contextWrite(view -> RequestContexts.put(view,
            RequestContext.system(Locale.ENGLISH, "it"))).block().orElseThrow();
    }

    private String owner() {
        return String.valueOf(query("SELECT DISTINCT user_id FROM sec_user_version WHERE user_name = 'fl-owner'")
            .getFirst().get("user_id"));
    }

    /** The configuration as read before the test changes the directory (as the runner read it at startup). */
    static FirstLaunchConfig snapshot;

    static FirstLaunchConfig read() {
        return new com.jabiz.runtime.firstlaunch.FirstLaunchConfigReader(
            new com.jabiz.runtime.firstlaunch.FirstLaunchSettings(directory.resolve("first-launch.yml").toString(),
                false, Duration.ofMinutes(10), Duration.ofSeconds(1), 1024 * 1024, 10 * 1024 * 1024, 100))
            .read().config();
    }

    private UUID store(String actor, String policy, String path, Function<byte[], byte[]> extract) {
        FirstLaunchConfig config = snapshot;
        RequestContext request = actor == null ? RequestContext.system(Locale.ENGLISH, "first-launch-probe")
            : new RequestContext(actor, null, Locale.ENGLISH, "first-launch-probe", Set.of(), Set.of());
        return files.store(run().runId(), config, FirstLaunchStep.file("probe",
            new FirstLaunchStep.FileSource(policy, path, extract, "probe")), actor, request).block();
    }

    private static void within(Duration limit, java.util.function.BooleanSupplier condition) {
        long end = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("Condition not met within " + limit);
            }
            Thread.onSpinWait();
        }
    }

    private static List<String> codes(Throwable e) {
        return ((ValidationException) e).violations().stream().map(Violation::ruleCode).toList();
    }

    @Autowired
    org.springframework.context.ApplicationContext context;

    @Test
    @Order(1)
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void theFileIsTheAccountsUploadAndOnlyTheConfigurationsLimitsHold(
        org.springframework.boot.test.system.CapturedOutput output) throws IOException {
        within(Duration.ofSeconds(60), () -> holding || !EXITS.isEmpty());
        assertThat(EXITS).isEmpty();
        snapshot = read();
        String owner = owner();

        // A dry run lists the file step with the rest.
        List<Integer> dryExits = new CopyOnWriteArrayList<>();
        com.jabiz.runtime.firstlaunch.FirstLaunchRunner dry = new com.jabiz.runtime.firstlaunch.FirstLaunchRunner(
            new org.springframework.mock.env.MockEnvironment()
                .withProperty("jabiz.first-launch.config", directory.resolve("first-launch.yml").toString())
                .withProperty("jabiz.first-launch.dry-run", "true"),
            runs, new FirstLaunchGate(), context.getBean(com.jabiz.runtime.process.ProcessExecutor.class),
            context.getBeanProvider(FirstLaunchHandler.class),
            context.getBeanProvider(com.jabiz.runtime.firstlaunch.FirstLaunchPlatformSteps.class),
            context.getBean(com.jabiz.i18n.MessageCatalog.class), context.getBean(java.time.Clock.class),
            new org.springframework.beans.factory.support.StaticListableBeanFactory(
                Map.of("exit", (FirstLaunchExit) dryExits::add)).getBeanProvider(FirstLaunchExit.class),
            context.getBean(tools.jackson.databind.json.JsonMapper.class),
            context.getBean(com.jabiz.runtime.firstlaunch.FirstLaunchPowers.class), files);
        dry.afterSingletonsInstantiated();
        dry.onApplicationEvent(null);
        assertThat(dryExits).containsExactly(0);
        assertThat(output.getOut()).contains("img1 (process (file), as owner)");

        // The step's image was stored, re-encoded, as the owner's own upload.
        Map<String, Object> stored = query("SELECT uploaded_by, content_type, policy FROM sys_file").getFirst();
        assertThat(stored).containsEntry("uploaded_by", owner).containsEntry("policy", CommerceFiles.IMAGE);
        assertThat(String.valueOf(stored.get("content_type"))).startsWith("image/");
        // Registered under the run's step, by the first launch's own process; the name stays out of the record.
        assertThat(query("SELECT p.process_name FROM op_process c JOIN op_process p ON p.process_seq_id ="
            + " c.parent_seq_id WHERE c.process_name = 'FIRST_LAUNCH_FILE_REGISTER'"))
            .extracting(row -> row.get("process_name")).containsExactly("FIRST_LAUNCH_STEP");
        assertThat(query("SELECT input_summary::text AS input FROM op_process"
            + " WHERE process_name = 'FIRST_LAUNCH_FILE_REGISTER'").getFirst().get("input").toString())
            .doesNotContain("picture");

        // An image's metadata does not survive the pipeline.
        store(owner, CommerceFiles.IMAGE, "sponsors/hq/package.json", fromPackage("gps"));
        try (var walk = Files.walk(filesRoot())) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                assertThat(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1))
                    .doesNotContain(GPS_MARKER);
            }
        }
        // A policy that keeps its metadata from readers gives a random (version 4) id.
        assertThat(store(owner, ItFileFixtures.PRIVATE, "sponsors/hq/package.json", fromPackage("gps")).version())
            .isEqualTo(4);

        long before = (Long) query("SELECT count(*) AS files FROM sys_file").getFirst().get("files");
        Set<Path> objects = objects();
        assertThatThrownBy(() -> store(owner, CommerceFiles.IMAGE, "../outside.png", Function.identity()))
            .hasMessageContaining("leaves");
        assertThatThrownBy(() -> store(owner, CommerceFiles.IMAGE, directory.resolve("big.bin").toString(),
            Function.identity())).hasMessageContaining("Not a relative path");
        assertThatThrownBy(() -> store(owner, CommerceFiles.IMAGE, "sponsors/hq/package.json", fromPackage("fake")))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(codes(e)).containsAnyOf("FILE_TYPE_NOT_ALLOWED", "FILE_INVALID"));
        assertThatThrownBy(() -> store(owner, ItFileFixtures.TINY, "big.bin", Function.identity()))
            .isInstanceOf(com.jabiz.runtime.PayloadTooLargeException.class);
        assertThatThrownBy(() -> store(owner, "it.audio", "sponsors/hq/package.json", fromPackage("img1")))
            .hasMessageContaining("does not name");
        assertThatThrownBy(() -> store(owner, CommerceFiles.IMAGE, "sponsors/hq/package.json", bytes -> null))
            .hasMessageContaining("picked nothing");
        assertThatThrownBy(() -> store(null, CommerceFiles.IMAGE, "sponsors/hq/package.json", fromPackage("img1")))
            .hasMessageContaining("as an account");
        assertThatThrownBy(() -> store(UUID.randomUUID().toString(), CommerceFiles.IMAGE, "sponsors/hq/package.json",
            fromPackage("img1"))).hasMessageContaining("was not made by this first launch");

        // The directory as it was read: a file changed or added since, or a link made since, is refused.
        Path outside = Files.createTempDirectory("outside");
        Files.write(outside.resolve("x.png"), png(4));
        Files.createSymbolicLink(directory.resolve("sponsors/linked"), outside);
        assertThatThrownBy(() -> store(owner, CommerceFiles.IMAGE, "sponsors/linked/x.png", Function.identity()))
            .hasMessageContaining("symbolic link");
        Files.write(directory.resolve("sponsors/new.png"), png(4));
        assertThatThrownBy(() -> store(owner, CommerceFiles.IMAGE, "sponsors/new.png", Function.identity()))
            .hasMessageContaining("was not in the configuration's directory when it was read");
        Files.write(directory.resolve("big.bin"), new byte[70 * 1024 + 1]);
        assertThatThrownBy(() -> store(owner, ItFileFixtures.TINY, "big.bin", Function.identity()))
            .hasMessageContaining("has changed since the configuration was read");

        // An account with a credential of its own is no longer acted as, for files either.
        execute("INSERT INTO sec_refresh_token (token_hash, family_id, user_id, issued_at, expires_at)"
            + " VALUES (?, ?, ?::uuid, now(), now() + interval '1 day')", "b".repeat(64), UUID.randomUUID(), owner);
        assertThatThrownBy(() -> store(owner, CommerceFiles.IMAGE, "sponsors/hq/package.json", fromPackage("img1")))
            .hasMessageContaining("has a credential of its own");
        // Refused steps leave no row and no stored object behind (a refused registration deletes its object).
        assertThat(query("SELECT count(*) AS files FROM sys_file").getFirst().get("files")).isEqualTo(before);
        assertThat(objects()).isEqualTo(objects);
    }

    @Test
    @Order(2)
    void afterTheContentPhaseNoFileStepWorks() {
        RELEASE.countDown();
        within(Duration.ofSeconds(30), gate::isOpen);

        assertThatThrownBy(() -> store(owner(), CommerceFiles.IMAGE, "sponsors/hq/package.json", fromPackage("img1")))
            .hasMessageContaining("content phase is over");
    }
}
