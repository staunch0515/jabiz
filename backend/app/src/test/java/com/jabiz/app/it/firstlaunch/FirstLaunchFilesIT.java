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

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        registry.add("jabiz.first-launch.config", () -> {
            try {
                directory = Files.createTempDirectory("first-launch-files");
                Files.writeString(directory.resolve("first-launch.yml"), "version: 1\n");
                Files.createDirectories(directory.resolve("sponsors/hq"));
                // A package with the image inside, as the sponsor page exports it.
                Files.writeString(directory.resolve("sponsors/hq/package.json"), "{\"img1\":\""
                    + Base64.getEncoder().encodeToString(png(8)) + "\",\"fake\":\""
                    + Base64.getEncoder().encodeToString("not an image at all".getBytes()) + "\"}");
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
                    return Set.of(CommerceFiles.IMAGE, ItFileFixtures.TINY);
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

    private UUID store(String actor, String policy, String path, Function<byte[], byte[]> extract) {
        FirstLaunchConfig config = new com.jabiz.runtime.firstlaunch.FirstLaunchConfigReader(
            new com.jabiz.runtime.firstlaunch.FirstLaunchSettings(directory.resolve("first-launch.yml").toString(),
                false, Duration.ofMinutes(10), Duration.ofSeconds(1), 1024 * 1024, 10 * 1024 * 1024, 100))
            .read().config();
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

    @Test
    @Order(1)
    void theFileIsTheAccountsUploadAndOnlyTheConfigurationsLimitsHold() throws IOException {
        within(Duration.ofSeconds(60), () -> holding || !EXITS.isEmpty());
        assertThat(EXITS).isEmpty();
        String owner = owner();

        // The step's image was stored, re-encoded, as the owner's own upload.
        Map<String, Object> stored = query("SELECT uploaded_by, content_type, policy FROM sys_file").getFirst();
        assertThat(stored).containsEntry("uploaded_by", owner).containsEntry("policy", CommerceFiles.IMAGE);
        assertThat(String.valueOf(stored.get("content_type"))).startsWith("image/");
        // The file's name stays out of the operation's record.
        assertThat(query("SELECT input_summary::text AS input FROM op_process WHERE process_name = 'FILE_REGISTER'")
            .getFirst().get("input").toString()).doesNotContain("picture");

        assertThatThrownBy(() -> store(owner, CommerceFiles.IMAGE, "../outside.png", Function.identity()))
            .hasMessageContaining("leaves");
        // (A link in the directory at startup stops the start: the configuration's hash refuses it. One made since
        // is refused when read.)
        Path outside = Files.createTempFile("outside", ".png");
        Files.write(outside, png(4));
        Files.createSymbolicLink(directory.resolve("sponsors/link.png"), outside);
        assertThatThrownBy(() -> store(owner, CommerceFiles.IMAGE, "sponsors/link.png", Function.identity()))
            .hasMessageContaining("symbolic link");
        assertThatThrownBy(() -> store(owner, CommerceFiles.IMAGE, "sponsors/hq/package.json", fromPackage("fake")))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(codes(e)).containsAnyOf("FILE_TYPE_NOT_ALLOWED", "FILE_INVALID"));
        assertThatThrownBy(() -> store(owner, ItFileFixtures.TINY, "big.bin", Function.identity()))
            .isInstanceOf(com.jabiz.runtime.PayloadTooLargeException.class);
        assertThatThrownBy(() -> store(owner, "it.private", "sponsors/hq/package.json", fromPackage("img1")))
            .hasMessageContaining("does not name");
        assertThatThrownBy(() -> store(UUID.randomUUID().toString(), CommerceFiles.IMAGE, "sponsors/hq/package.json",
            fromPackage("img1"))).hasMessageContaining("was not made by this first launch");
        assertThat(query("SELECT count(*) AS files FROM sys_file").getFirst().get("files")).isEqualTo(1L);
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
