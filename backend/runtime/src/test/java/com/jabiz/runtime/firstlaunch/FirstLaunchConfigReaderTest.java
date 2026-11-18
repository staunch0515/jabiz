package com.jabiz.runtime.firstlaunch;

import com.jabiz.firstlaunch.FirstLaunchConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirstLaunchConfigReaderTest {

    @TempDir
    Path directory;

    private FirstLaunchConfigReader.Read read(long maxFileBytes) {
        return new FirstLaunchConfigReader(new FirstLaunchSettings(directory.resolve("first-launch.yml").toString(),
            false, Duration.ofMinutes(1), Duration.ofSeconds(1), maxFileBytes, 1_000_000, 100)).read();
    }

    private void write(String path, String content) throws IOException {
        Path file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void readsThePlatformsSections() throws IOException {
        write("first-launch.yml", """
            version: 1
            site:
              language: ja
            allowedEmailDomains: [Example.co.jp]
            accounts:
              - key: B
                email: b@example.co.jp
                name: 田中
                language: ja
                roles: [QB_OPERATOR]
              - key: A
                email: a@EXAMPLE.co.jp
                name: 鈴木
                roles: [QB_ADMIN_SUPER, QB_OPERATOR]
                report: true
            quizbuks:
              anything: here
            """);

        FirstLaunchConfigReader.Read read = read(1000);

        assertThat(read.problems()).isEmpty();
        FirstLaunchConfig config = read.config();
        assertThat(config.language()).isEqualTo("ja");
        assertThat(config.allowedEmailDomains()).containsExactly("example.co.jp");
        assertThat(config.accounts()).extracting(account -> account.key()).containsExactly("B", "A");
        assertThat(config.account("A").orElseThrow().report()).isTrue();
        assertThat(config.account("A").orElseThrow().roles()).containsExactly("QB_ADMIN_SUPER", "QB_OPERATOR");
        assertThat(config.document()).containsKey("quizbuks");
        // An account prints its key, never its address.
        assertThat(config.toString()).doesNotContain("example.co.jp");
    }

    @Test
    void theHashCoversEveryFileOfTheDirectory() throws IOException {
        write("first-launch.yml", "version: 1\n");
        write("sponsors/hq/package.json", "{}");
        String first = read(1000).config().hash();

        assertThat(read(1000).config().hash()).isEqualTo(first).hasSize(64);
        write("sponsors/hq/package.json", "{ }");
        assertThat(read(1000).config().hash()).isNotEqualTo(first);
        write("sponsors/hq/package.json", "{}");
        write("sponsors/extra.json", "{}");
        assertThat(read(1000).config().hash()).isNotEqualTo(first);
    }

    @Test
    void secretsLinksAndLargeFilesAreProblems() throws IOException {
        write("first-launch.yml", """
            version: 1
            mail:
              smtpPassword: x
            prizes:
              - codes: [A, B]
            """);
        write("big.bin", "x".repeat(2000));
        Path outside = Files.createTempFile("outside", ".txt");
        Files.createSymbolicLink(directory.resolve("link.txt"), outside);

        FirstLaunchConfigReader.Read read = read(1000);

        assertThat(read.problems()).extracting(problem -> problem.location())
            .contains("mail.smtpPassword", "prizes[0].codes", "big.bin", "link.txt");
    }

    @Test
    void filesAreReadOnlyFromInsideTheDirectory() throws IOException {
        write("first-launch.yml", "version: 1\n");
        write("sponsors/hq/package.json", "{}");
        Path outside = Files.createTempFile("outside", ".txt");
        Files.createSymbolicLink(directory.resolve("sponsors/link.json"), outside);
        FirstLaunchConfig.Files files = read(1000).config().files();

        assertThat(new String(files.read("sponsors/hq/package.json"))).isEqualTo("{}");
        assertThat(files.exists("sponsors/hq/package.json")).isTrue();
        assertThat(files.exists("../" + outside.getFileName())).isFalse();
        assertThatThrownBy(() -> files.read("../" + outside.getFileName())).hasMessageContaining("leaves");
        assertThatThrownBy(() -> files.read(outside.toString())).hasMessageContaining("Not a relative path");
        assertThatThrownBy(() -> files.read("sponsors/link.json")).hasMessageContaining("symbolic link");
        assertThatThrownBy(() -> files.read("sponsors/missing.json")).hasMessageContaining("No file");
    }

    @Test
    void aMissingOrBrokenFileIsAProblem() throws IOException {
        assertThat(read(1000).problems()).extracting(problem -> problem.problem())
            .containsExactly("the file does not exist or is not a regular file");

        write("first-launch.yml", "version: [1\n");
        assertThat(read(1000).problems()).extracting(problem -> problem.problem())
            .anySatisfy(problem -> assertThat(problem).startsWith("not valid YAML"));
    }
}
