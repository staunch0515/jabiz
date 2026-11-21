package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.firstlaunch.FirstLaunchAccount;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The shape of a configuration: everything but the accounts' addresses (decision D83). */
class ConfigShapeTest {

    @TempDir
    Path directory;

    private FirstLaunchConfig config(String email, String note) throws IOException {
        Path main = directory.resolve("first-launch.yml");
        Files.writeString(main, "changes with every edit: " + email + note);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", 1);
        document.put("note", note);
        document.put("accounts", List.of(Map.of("key", "A", "email", email, "roles", List.of("R"))));
        return new FirstLaunchConfig("hash-" + email + note, document, "en", List.of("example.com"),
            List.of(new FirstLaunchAccount("A", email, "A", null, List.of("R"), true)), new FirstLaunchConfig.Files() {
                @Override
                public byte[] read(String relativePath) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean exists(String relativePath) {
                    return false;
                }
            });
    }

    @Test
    void onlyTheAddressesMayDiffer() throws IOException {
        ConfigShape shape = new ConfigShape(directory.resolve("first-launch.yml").toString());
        Files.writeString(directory.resolve("package.json"), "{\"a\":1}");
        String first = shape.of(config("a@example.com", "x"));
        assertThat(shape.of(config("other@example.com", "x"))).isEqualTo(first);
        assertThat(shape.of(config("a@example.com", "y"))).isNotEqualTo(first);
        Files.writeString(directory.resolve("package.json"), "{\"a\":2}");
        assertThat(shape.of(config("a@example.com", "x"))).isNotEqualTo(first);
        Files.createDirectories(directory.resolve("sponsors"));
        Files.writeString(directory.resolve("package.json"), "{\"a\":1}");
        Files.writeString(directory.resolve("sponsors/more.json"), "{}");
        assertThat(shape.of(config("a@example.com", "x"))).isNotEqualTo(first);
    }
}
