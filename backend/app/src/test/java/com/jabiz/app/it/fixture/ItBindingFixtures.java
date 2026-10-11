package com.jabiz.app.it.fixture;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignInLoad;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Values bound as the type of their field (ROADMAP 16b fix; QuizBuks Q2 platform gaps 15 and 16): marks on users kept
 * in a text column and in a uuid column referring to the platform user, a sign-in guard that loads them by either
 * column, and datasets scoped to the caller by either column. The guard refuses users with a mark whose reason is
 * {@value #BLOCK} and is inert for everybody else, so other tests sign in as before.
 */
public final class ItBindingFixtures extends BaseEntityDefinitions {

    public static final String MARK_DATASET = "urn:jabiz:dataset:it:ItAccountMark";
    public static final String MINE_BY_REF_DATASET = "urn:jabiz:dataset:it:ItAccountMark:mine-by-ref";
    public static final String MINE_BY_TEXT_DATASET = "urn:jabiz:dataset:it:ItAccountMark:mine-by-text";

    /** Loads of the guard: the user's marks by the text column and by the reference. */
    public static final String BY_TEXT = "marksByText";
    public static final String BY_REF = "marksByRef";

    /** Marks of this reason refuse the sign-in. */
    public static final String BLOCK = "block";

    /** The rows each load gave the guard at the last attempt, by user id. */
    public static final Map<String, Map<String, List<Map<String, Object>>>> SEEN = new ConcurrentHashMap<>();

    public static final EntityDefinition MARK = EntityDefinition.define("ItAccountMark", eb -> {
        eb.physicalTable("it_account_mark");
        eb.primaryKey("markId");
        eb.field("markId", semanticIdentity("f_id", "urn:jabiz:entity:it:account-mark"));
        eb.field("userText", f -> f.physicalColumn("f_user_text").asText(64));
        eb.field("userRef", f -> f.physicalColumn("f_user_ref").asReference(SecurityEntities.USER));
        eb.field("reason", f -> f.physicalColumn("f_reason").asText(200));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    private ItBindingFixtures() {}

    /** Refuses users marked {@link #BLOCK} by either column; remembers what it saw. */
    static final class MarkGuard implements SignInGuard {

        @Override
        public List<SignInLoad> loads() {
            return List.of(SignInLoad.of(BY_TEXT, MARK_DATASET, "userText"),
                SignInLoad.of(BY_REF, MARK_DATASET, "userRef"));
        }

        @Override
        public SignInDecision check(SignInAttempt attempt) {
            SEEN.put(attempt.userId(), Map.of(BY_TEXT, attempt.rows(BY_TEXT), BY_REF, attempt.rows(BY_REF)));
            boolean blocked = Stream.concat(attempt.rows(BY_TEXT).stream(), attempt.rows(BY_REF).stream())
                .anyMatch(row -> BLOCK.equals(row.get("reason")));
            return blocked ? SignInDecision.refuse("marked by the test") : SignInDecision.ALLOW;
        }
    }

    @Configuration
    static class Beans {

        @Bean
        SignInGuard itMarkGuard() {
            return new MarkGuard();
        }

        @Bean
        EntityDefinition itAccountMarkEntity() {
            return MARK;
        }

        @Bean
        DatasetDefinition itAccountMarkDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(MARK_DATASET, d -> d
                .targetEntityType("ItAccountMark")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }

        /** The caller's marks by the reference: the actor id (text) is compared with a uuid column. */
        @Bean
        DatasetDefinition itMyMarksByRefDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(MINE_BY_REF_DATASET, d -> d
                .targetEntityType("ItAccountMark")
                .permissions("it.read", "it.write")
                .scope(s -> s.fromContext("userRef", "actorId", RequestContext::actorId))
                .storage(s -> s.connectionPoolRef(pool)));
        }

        /** The caller's marks by the text column. */
        @Bean
        DatasetDefinition itMyMarksByTextDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(MINE_BY_TEXT_DATASET, d -> d
                .targetEntityType("ItAccountMark")
                .permissions("it.read", "it.write")
                .scope(s -> s.fromContext("userText", "actorId", RequestContext::actorId))
                .storage(s -> s.connectionPoolRef(pool)));
        }
    }
}
