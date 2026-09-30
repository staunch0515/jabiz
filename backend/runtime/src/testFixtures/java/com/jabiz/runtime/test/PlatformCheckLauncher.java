package com.jabiz.runtime.test;

import com.jabiz.runtime.check.PlatformCheckMain;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Runs {@link PlatformCheckMain} against a fresh schema of the test database ({@link PostgresTestDatabase}: the
 * server of {@code JABIZ_TEST_DB_URL}, else a Testcontainers {@code postgres:16}), so the check sees exactly what the
 * migrations create. The schema is dropped afterwards.
 */
public final class PlatformCheckLauncher {

    private PlatformCheckLauncher() {}

    /**
     * @param extraArgs further Spring Boot arguments, for example other template locations
     */
    public static int run(Class<?> application, List<String> extraArgs, PrintStream out) {
        String schema = newSchema();
        List<String> args = new ArrayList<>(databaseArgs(schema));
        // The check serves no requests, but the application refuses to start without a token key, an integrity key
        // and a second-factor key: throwaway ones.
        args.add("--jabiz.security.jwt.secret=" + throwawayKey());
        args.add("--jabiz.integrity.key=" + throwawayKey());
        args.add("--jabiz.security.mfa.key=" + throwawayKey());
        // Nor without a file storage directory when it declares file policies: an empty throwaway one.
        Path files;
        try {
            files = Files.createTempDirectory("jabiz-check-files-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        args.add("--jabiz.files.local.root=" + files);
        args.addAll(extraArgs);
        try {
            return PlatformCheckMain.run(application, args, out);
        } finally {
            PostgresTestDatabase.get().dropSchema(schema);
            try {
                Files.deleteIfExists(files);
            } catch (IOException ignored) {
                // an empty temporary directory left behind
            }
        }
    }

    private static String throwawayKey() {
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /** A schema name for one run; the schema is created by Flyway. */
    public static String newSchema() {
        return "pc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Spring Boot arguments that point R2DBC and Flyway at the schema of the test database. */
    public static List<String> databaseArgs(String schema) {
        PostgresTestDatabase db = PostgresTestDatabase.get();
        return List.of(
            "--spring.r2dbc.url=" + db.r2dbcUrl() + "?schema=" + schema,
            "--spring.r2dbc.username=" + db.username(),
            "--spring.r2dbc.password=" + db.password(),
            "--spring.flyway.url=" + db.jdbcUrl(),
            "--spring.flyway.user=" + db.username(),
            "--spring.flyway.password=" + db.password(),
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
            "--spring.flyway.create-schemas=true",
            "--spring.flyway.output-query-results=false");
    }

    /** {@code args[0]} is the application class, the rest are further Spring Boot arguments. */
    public static void main(String[] args) throws ClassNotFoundException {
        Class<?> application = Class.forName(args[0]);
        System.exit(run(application, List.of(args).subList(1, args.length), System.out));
    }
}
