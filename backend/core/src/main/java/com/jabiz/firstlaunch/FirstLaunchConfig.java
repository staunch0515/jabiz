package com.jabiz.firstlaunch;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The first-launch configuration as read from {@code JABIZ_FIRST_LAUNCH_CONFIG} (docs/design/23-first-launch.md
 * section 2): the main file, already parsed, the parts the platform itself uses, and the files beside it.
 *
 * @param hash               the configuration's hash: SHA-256 over the main file and every file of its directory
 *                           (hex), recorded by the run, the log and the audit
 * @param document           the whole main file, parsed (maps, lists, strings, numbers, booleans); applications read
 *                           their own sections from it
 * @param language           {@code site.language}: the language of the log's report and the report mail
 * @param allowedEmailDomains {@code allowedEmailDomains}: every account's address must be under one of them
 * @param accounts           {@code accounts}, in the order written
 * @param files              the files of the configuration's directory
 */
public record FirstLaunchConfig(String hash, Map<String, Object> document, String language,
    List<String> allowedEmailDomains, List<FirstLaunchAccount> accounts, Files files) {

    public FirstLaunchConfig {
        Objects.requireNonNull(hash, "hash must not be null");
        document = document == null ? Map.of() : document;
        allowedEmailDomains = allowedEmailDomains == null ? List.of() : List.copyOf(allowedEmailDomains);
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        Objects.requireNonNull(files, "files must not be null");
    }

    /** The account with this key, if the configuration has one. */
    public Optional<FirstLaunchAccount> account(String key) {
        return accounts.stream().filter(account -> account.key().equals(key)).findFirst();
    }

    /** The files of the configuration's directory, by path relative to it. */
    public interface Files {

        /**
         * The bytes of the file at {@code relativePath} (forward slashes, no {@code ..}, inside the directory).
         *
         * @throws IllegalArgumentException when the path leaves the directory, the file does not exist or is larger
         *                                  than {@code jabiz.first-launch.max-file-bytes}
         */
        byte[] read(String relativePath);

        /** Whether a file exists at {@code relativePath} inside the directory. */
        boolean exists(String relativePath);
    }

    @Override
    public String toString() {
        return "FirstLaunchConfig[hash=" + hash + ", accounts=" + accounts + "]";
    }
}
