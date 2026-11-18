package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.firstlaunch.FirstLaunchConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The shape of a first-launch configuration (decision D83): a SHA-256 over the main file's content without the
 * accounts' addresses and over every other file of its directory. Two configurations of the same shape differ at most
 * in the accounts' addresses - the one change the waiting phase takes from the configuration.
 */
@Component
public class ConfigShape {

    private final String config;

    @Autowired
    public ConfigShape(Environment environment) {
        this(environment.getProperty("jabiz.first-launch.config", ""));
    }

    ConfigShape(String config) {
        this.config = config == null ? "" : config.trim();
    }

    /** The shape of {@code config}, as hex. */
    public String of(FirstLaunchConfig config) {
        MessageDigest digest = sha256();
        digest.update(canonical(withoutAddresses(config.document())).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        if (!this.config.isEmpty()) {
            Path main = Path.of(this.config).toAbsolutePath().normalize();
            Path directory = main.getParent();
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !path.equals(main)).forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            files.sort((a, b) -> relative(directory, a).compareTo(relative(directory, b)));
            for (Path file : files) {
                digest.update(relative(directory, file).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try {
                    digest.update(sha256().digest(Files.readAllBytes(file)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String relative(Path directory, Path file) {
        return directory.relativize(file).toString().replace('\\', '/');
    }

    /** The document with every account's {@code email} left out. */
    private static Object withoutAddresses(Map<String, Object> document) {
        Map<String, Object> copy = new TreeMap<>(document);
        if (copy.get("accounts") instanceof List<?> accounts) {
            List<Object> stripped = new ArrayList<>();
            for (Object account : accounts) {
                if (account instanceof Map<?, ?> fields) {
                    Map<Object, Object> kept = new TreeMap<>((a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
                    kept.putAll(fields);
                    kept.remove("email");
                    stripped.add(kept);
                } else {
                    stripped.add(account);
                }
            }
            copy.put("accounts", stripped);
        }
        return copy;
    }

    /** A canonical text of parsed YAML: maps by sorted key, lists in order, scalars typed. */
    private static String canonical(Object value) {
        StringBuilder text = new StringBuilder();
        write(value, text);
        return text.toString();
    }

    private static void write(Object value, StringBuilder text) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
            text.append('{');
            sorted.forEach((key, item) -> {
                text.append(quoted(key)).append(':');
                write(item, text);
                text.append(',');
            });
            text.append('}');
        } else if (value instanceof List<?> list) {
            text.append('[');
            list.forEach(item -> {
                write(item, text);
                text.append(',');
            });
            text.append(']');
        } else if (value == null) {
            text.append("null");
        } else {
            text.append(value.getClass().getSimpleName()).append(quoted(String.valueOf(value)));
        }
    }

    private static String quoted(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
