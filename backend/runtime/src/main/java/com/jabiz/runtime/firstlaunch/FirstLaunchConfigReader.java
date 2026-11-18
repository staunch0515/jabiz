package com.jabiz.runtime.firstlaunch;

import com.jabiz.firstlaunch.FirstLaunchAccount;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the first-launch configuration (docs/design/23-first-launch.md section 2): the main file, the hash over its
 * directory, the platform's sections ({@code version}, {@code site}, {@code allowedEmailDomains}, {@code accounts})
 * and their problems. The applications' sections are the handler's.
 */
public final class FirstLaunchConfigReader {

    /** The one version of the format there is. */
    public static final int VERSION = 1;

    /** Keys that look like a secret, anywhere in the main file: the configuration holds none (section 2). */
    static final Pattern SECRET_KEY = Pattern.compile(
        "(?i).*(password|passwd|secret|token|api[-_]?key|private[-_]?key|credential|codes).*");

    private static final Pattern ACCOUNT_KEY = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@([^@\\s]+\\.[^@\\s]+)");
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    private final YAMLMapper yaml = YAMLMapper.builder().build();
    private final FirstLaunchSettings settings;

    public FirstLaunchConfigReader(FirstLaunchSettings settings) {
        this.settings = settings;
    }

    /** The configuration and its problems; with any problem the configuration is not to be applied. */
    public record Read(FirstLaunchConfig config, List<FirstLaunchProblem> problems) {
        public Read {
            problems = List.copyOf(problems);
        }
    }

    /** Reads {@code settings.config()}. */
    public Read read() {
        Path main = Path.of(settings.config()).toAbsolutePath().normalize();
        List<FirstLaunchProblem> problems = new ArrayList<>();
        if (!Files.isRegularFile(main, LinkOption.NOFOLLOW_LINKS)) {
            problems.add(new FirstLaunchProblem(main.toString(), "the file does not exist or is not a regular file",
                "set JABIZ_FIRST_LAUNCH_CONFIG to the main file of the configuration"));
            return new Read(empty(main.getParent()), problems);
        }
        Path directory = main.getParent();
        String hash = hash(directory, problems);
        Map<String, Object> document;
        try {
            document = parse(Files.readAllBytes(main));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JacksonException | ClassCastException e) {
            problems.add(new FirstLaunchProblem(main.getFileName().toString(), "not valid YAML: " + e.getMessage(),
                "fix the file's syntax"));
            return new Read(empty(directory, hash), problems);
        }
        secretKeys(document, "", problems);
        Object version = document.get("version");
        if (!(version instanceof Number number) || number.intValue() != VERSION) {
            problems.add(new FirstLaunchProblem("version", "must be " + VERSION, "write version: " + VERSION));
        }
        String language = text(nested(document, "site"), "language");
        List<String> domains = domains(document.get("allowedEmailDomains"), problems);
        List<FirstLaunchAccount> accounts = accounts(document.get("accounts"), domains, problems);
        return new Read(new FirstLaunchConfig(hash, document, language, domains, accounts,
            new DirectoryFiles(directory, settings.maxFileBytes())), problems);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(byte[] bytes) {
        Object parsed = yaml.readValue(bytes, Object.class);
        if (parsed == null) {
            return Map.of();
        }
        return (Map<String, Object>) parsed;
    }

    private FirstLaunchConfig empty(Path directory) {
        return empty(directory, "");
    }

    private FirstLaunchConfig empty(Path directory, String hash) {
        return new FirstLaunchConfig(hash, Map.of(), null, List.of(), List.of(),
            new DirectoryFiles(directory, settings.maxFileBytes()));
    }

    /**
     * SHA-256 over every file of the directory, by relative path in order: each contributes its path, a zero byte
     * and its own SHA-256. Symbolic links and files beyond the limits are problems.
     */
    String hash(Path directory, List<FirstLaunchProblem> problems) {
        MessageDigest digest = sha256();
        long total = 0;
        int count = 0;
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> paths = walk.filter(path -> !path.equals(directory)).sorted().toList();
            for (Path path : paths) {
                String relative = relative(directory, path);
                if (Files.isSymbolicLink(path)) {
                    problems.add(new FirstLaunchProblem(relative, "is a symbolic link",
                        "copy the file into the configuration's directory instead"));
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                long size = Files.size(path);
                count++;
                total += size;
                if (size > settings.maxFileBytes()) {
                    problems.add(new FirstLaunchProblem(relative, "is larger than " + settings.maxFileBytes()
                        + " bytes (jabiz.first-launch.max-file-bytes)", "make it smaller"));
                    continue;
                }
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(sha256().digest(Files.readAllBytes(path)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (count > settings.maxFiles()) {
            problems.add(new FirstLaunchProblem(directory.toString(), "holds " + count + " files, more than "
                + settings.maxFiles() + " (jabiz.first-launch.max-files)", "keep only the configuration's files there"));
        }
        if (total > settings.maxTotalBytes()) {
            problems.add(new FirstLaunchProblem(directory.toString(), "holds " + total + " bytes, more than "
                + settings.maxTotalBytes() + " (jabiz.first-launch.max-total-bytes)", "keep only the configuration's"
                + " files there, or make them smaller"));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String relative(Path directory, Path path) {
        return directory.relativize(path).toString().replace('\\', '/');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void secretKeys(Object node, String path, List<FirstLaunchProblem> problems) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                String location = path.isEmpty() ? String.valueOf(key) : path + "." + key;
                if (SECRET_KEY.matcher(String.valueOf(key)).matches()) {
                    problems.add(new FirstLaunchProblem(location, "looks like a secret; the first-launch"
                        + " configuration holds none (it is kept with the deployment and hashed into the audit)",
                        "remove it and enter the secret later where the system asks for it"));
                }
                secretKeys(value, location, problems);
            });
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                secretKeys(list.get(i), path + "[" + i + "]", problems);
            }
        }
    }

    private static List<String> domains(Object node, List<FirstLaunchProblem> problems) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof List<?> list)) {
            problems.add(new FirstLaunchProblem("allowedEmailDomains", "must be a list of domains",
                "write allowedEmailDomains: [example.com]"));
            return List.of();
        }
        List<String> domains = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String domain = String.valueOf(list.get(i)).trim().toLowerCase(Locale.ROOT);
            if (!DOMAIN.matcher(domain).matches()) {
                problems.add(new FirstLaunchProblem("allowedEmailDomains[" + i + "]", "is not a domain",
                    "write a domain such as example.com"));
            } else {
                domains.add(domain);
            }
        }
        return domains;
    }

    private static List<FirstLaunchAccount> accounts(Object node, List<String> domains,
        List<FirstLaunchProblem> problems) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof List<?> list)) {
            problems.add(new FirstLaunchProblem("accounts", "must be a list", "write each account as an item"));
            return List.of();
        }
        if (!list.isEmpty() && domains.isEmpty()) {
            problems.add(new FirstLaunchProblem("allowedEmailDomains", "is required with accounts",
                "list the domains every account's address must be under"));
        }
        List<FirstLaunchAccount> accounts = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> emails = new LinkedHashSet<>();
        boolean report = false;
        for (int i = 0; i < list.size(); i++) {
            String at = "accounts[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> item)) {
                problems.add(new FirstLaunchProblem(at, "must be a map with key, email, name and roles",
                    "see docs/guide/first-launch.md"));
                continue;
            }
            String key = text(item, "key");
            String email = text(item, "email");
            String name = text(item, "name");
            if (key == null || !ACCOUNT_KEY.matcher(key).matches()) {
                problems.add(new FirstLaunchProblem(at + ".key", "must be 1 to 40 letters, digits, '_' or '-'",
                    "give the account a short key such as A"));
            } else if (!keys.add(key)) {
                problems.add(new FirstLaunchProblem(at + ".key", "repeats the key " + key, "give each account its own"));
            }
            if (email == null || !EMAIL.matcher(email).matches()) {
                problems.add(new FirstLaunchProblem(at + ".email", "is not an e-mail address",
                    "write the address the invitation goes to"));
            } else {
                String domain = email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT);
                if (!domains.isEmpty() && !domains.contains(domain)) {
                    problems.add(new FirstLaunchProblem(at + ".email", "is not under allowedEmailDomains",
                        "check the address, or add its domain to allowedEmailDomains"));
                }
                if (!emails.add(email.toLowerCase(Locale.ROOT))) {
                    problems.add(new FirstLaunchProblem(at + ".email", "is the address of another account",
                        "give each account its own address"));
                }
            }
            if (name == null || name.isBlank()) {
                problems.add(new FirstLaunchProblem(at + ".name", "is required", "write the person's name"));
            }
            List<String> roles = new ArrayList<>();
            if (!(item.get("roles") instanceof List<?> roleList) || roleList.isEmpty()) {
                problems.add(new FirstLaunchProblem(at + ".roles", "must list at least one role code",
                    "write roles: [ROLE_CODE]"));
            } else {
                roleList.forEach(role -> roles.add(String.valueOf(role)));
            }
            boolean reportTo = Boolean.TRUE.equals(item.get("report"));
            report |= reportTo;
            if (key != null) {
                accounts.add(new FirstLaunchAccount(key, email, name, text(item, "language"), roles, reportTo));
            }
        }
        if (!list.isEmpty() && !report) {
            problems.add(new FirstLaunchProblem("accounts", "no account has report: true",
                "mark the account the report of the first launch is mailed to"));
        }
        return accounts;
    }

    private static Map<?, ?> nested(Map<?, ?> document, String key) {
        return document.get(key) instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    /** The files of the configuration's directory; nothing outside it is read. */
    static final class DirectoryFiles implements FirstLaunchConfig.Files {

        private final Path directory;
        private final long maxBytes;

        DirectoryFiles(Path directory, long maxBytes) {
            this.directory = directory;
            this.maxBytes = maxBytes;
        }

        @Override
        public byte[] read(String relativePath) {
            Path path = resolve(relativePath);
            try {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("No file " + relativePath + " in the configuration's directory");
                }
                if (Files.size(path) > maxBytes) {
                    throw new IllegalArgumentException("The file " + relativePath + " is larger than " + maxBytes
                        + " bytes (jabiz.first-launch.max-file-bytes)");
                }
                return Files.readAllBytes(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean exists(String relativePath) {
            try {
                return Files.isRegularFile(resolve(relativePath), LinkOption.NOFOLLOW_LINKS);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private Path resolve(String relativePath) {
            if (relativePath == null || relativePath.isBlank() || relativePath.startsWith("/")
                || relativePath.contains("\\") || relativePath.contains(":")) {
                throw new IllegalArgumentException("Not a relative path: " + relativePath);
            }
            Path path = directory.resolve(relativePath).normalize();
            if (!path.startsWith(directory) || path.equals(directory)) {
                throw new IllegalArgumentException("The path " + relativePath
                    + " leaves the configuration's directory");
            }
            // A link inside the directory could still point outside it.
            for (Path part = path; part != null && !part.equals(directory); part = part.getParent()) {
                if (Files.isSymbolicLink(part)) {
                    throw new IllegalArgumentException("The path " + relativePath + " goes through a symbolic link");
                }
            }
            return path;
        }
    }
}
