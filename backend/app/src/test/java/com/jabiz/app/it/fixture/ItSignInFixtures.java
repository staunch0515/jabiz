package com.jabiz.app.it.fixture;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFormat;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignInLoad;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in entries, guards and verified addresses of the tests (docs/design/10-security.md section 15; decision D36):
 * a guard that refuses users linked to the pseudo provider {@value #BLOCKED} and fails for {@value #FAILING} (inert
 * for everybody else, so other tests sign in as before), a process and a dataset that need a verified address, an
 * import whose rows run that process, a process that marks a user's address verified (16b-1 has no writer of its
 * own), and an ordinary entity whose code is unique regardless of case.
 */
public final class ItSignInFixtures extends BaseEntityDefinitions {

    /** Users linked to this provider are refused by the guard. */
    public static final String BLOCKED = "it-blocked";
    /** For users linked to this provider the guard throws. */
    public static final String FAILING = "it-guard-fails";
    /** The load of the guard: the user's provider links. */
    public static final String LOAD = "links";

    /** The last attempt the guard saw, by user id. */
    public static final Map<String, SignInAttempt> SEEN = new ConcurrentHashMap<>();

    public static final String NOTE_DATASET = "urn:jabiz:dataset:it:ItVerifiedNote";
    public static final String UNIQUE_CI_DATASET = "urn:jabiz:dataset:it:ItUniqueCi";
    public static final String VERIFIED_PERMISSION = "it.verified-only";

    public record NoteInput(String text) {}

    public record Done(String text) {}

    /** @param address the address to record as proven; null: the user's current one */
    public record VerifyInput(String userId, String address) {}

    public static final ProcessDefinition<NoteInput, Done, ProcessContext> VERIFIED_ONLY =
        ProcessDefinition.single("IT_VERIFIED_ONLY", 1, NoteInput.class, Done.class, (in, ctx) -> new Done(in.text()))
            .withPermissions(VERIFIED_PERMISSION)
            .withVerifiedEmail(true);

    private static final String USERS = "users";
    private static final String INPUT = "input";

    public static final ProcessDefinition<VerifyInput, Done, ProcessContext> VERIFY_EMAIL =
        ProcessDefinition.define("IT_EMAIL_VERIFY", 1, VerifyInput.class, Done.class, ProcessContext.class, pb -> pb
            .permissions("it.email-verify")
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new Done("verified"))
            .step("Load the user", QueryEntities.<ProcessContext>of(SecurityEntities.USER_DATASET,
                ctx -> Rbac.all(new QueryPredicate.Eq("userId",
                    UUID.fromString(ctx.get(INPUT, VerifyInput.class).userId())), "userId"), USERS))
            .compute("Mark the address verified", (metadata, ctx) -> {
                @SuppressWarnings("unchecked")
                EntityInstance user = ((List<EntityInstance>) ctx.get(USERS)).getFirst();
                VerifyInput input = ctx.get(INPUT, VerifyInput.class);
                Map<String, Object> changes = new LinkedHashMap<>();
                changes.put("verifiedEmail", input.address() != null ? input.address() : user.get("email"));
                changes.put("emailVerifiedAt", ctx.opTime());
                ctx.changes().update(SecurityEntities.USER, user.id(), user.version(), changes);
            }));

    public static final EntityDefinition NOTE = EntityDefinition.define("ItVerifiedNote", eb -> {
        eb.physicalTable("it_verified_note");
        eb.primaryKey("noteId");
        eb.field("noteId", semanticIdentity("f_id", "urn:jabiz:entity:it:verified-note"));
        eb.field("text", f -> f.physicalColumn("f_text").asText(200));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    public static final EntityDefinition UNIQUE_CI = EntityDefinition.define("ItUniqueCi", eb -> {
        eb.physicalTable("it_unique_ci");
        eb.primaryKey("uniqueCiId");
        eb.field("uniqueCiId", semanticIdentity("f_id", "urn:jabiz:entity:it:unique-ci"));
        eb.field("code", f -> f.physicalColumn("f_code").required(true).asText(32));
        eb.field("rowVersion", rowVersion("f_version"));
        eb.uniqueIgnoreCase("uk_it_unique_ci_code", "code");
    });

    private ItSignInFixtures() {}

    /** Refuses users linked to {@link #BLOCKED}, fails for {@link #FAILING}, remembers what it saw. */
    static final class LinkGuard implements SignInGuard {

        @Override
        public List<SignInLoad> loads() {
            return List.of(SignInLoad.of(LOAD, SecurityEntities.USER_IDENTITY_DATASET, "userId"));
        }

        @Override
        public SignInDecision check(SignInAttempt attempt) {
            SEEN.put(attempt.userId(), attempt);
            List<Object> providers = attempt.rows(LOAD).stream().map(row -> row.get("provider")).toList();
            if (providers.contains(FAILING)) {
                throw new IllegalStateException("The guard broke");
            }
            return providers.contains(BLOCKED) ? SignInDecision.refuse("blocked by the test") : SignInDecision.ALLOW;
        }
    }

    @Configuration
    static class Beans {

        @Bean
        SignInGuard itLinkGuard() {
            return new LinkGuard();
        }

        @Bean
        ProcessDefinition<NoteInput, Done, ProcessContext> itVerifiedOnlyProcess() {
            return VERIFIED_ONLY;
        }

        @Bean
        ProcessDefinition<VerifyInput, Done, ProcessContext> itEmailVerifyProcess() {
            return VERIFY_EMAIL;
        }

        @Bean
        EntityDefinition itVerifiedNoteEntity() {
            return NOTE;
        }

        @Bean
        EntityDefinition itUniqueCiEntity() {
            return UNIQUE_CI;
        }

        @Bean
        DatasetDefinition itVerifiedNoteDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(NOTE_DATASET, d -> d
                .targetEntityType("ItVerifiedNote")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool))
                .policy(p -> p.requiresVerifiedEmail()));
        }

        @Bean
        DatasetDefinition itUniqueCiDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(UNIQUE_CI_DATASET, d -> d
                .targetEntityType("ItUniqueCi")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        ImportDefinition<ImportDefinition.NoParams> itVerifiedOnlyImport() {
            return ImportDefinition.define("it.verified-only", 1)
                .file("app.import", ImportFormat.csv())
                .field("text", new SemanticKind.Text(200, false), true, "Text")
                .perRow(VERIFIED_ONLY.name(), 1, (row, params) -> new NoteInput(row.text("text")))
                .permissions("it.verified-only.import")
                .build();
        }
    }
}
