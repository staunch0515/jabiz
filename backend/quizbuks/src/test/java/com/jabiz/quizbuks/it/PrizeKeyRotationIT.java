package com.jabiz.quizbuks.it;

import com.jabiz.context.RequestContext;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.reward.DigitalCodeProcesses;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.security.rotation.KeyRotationChecks;
import com.jabiz.runtime.security.rotation.KeyRotationProcesses;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.security.SealedSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The digital prizes' sealed fields under a new seal key (platform decision D78): this context runs with a new
 * {@code JABIZ_SEAL_KEY} and the test configuration's as the retired one. A shared prize's link on the publication and
 * its card, a winner's prize and a pool's codes (the publisher's and a provider's) stored under the retired key open
 * for their declared context; the re-keying stores each again under the current key, through the write path.
 */
@TestPropertySource(properties = {
    "jabiz.security.seal.key=bmV4dC1zZWFsLWtleS1vZi10aGUtcXVpemJ1a3Mta2V5LXJvdGF0aW9uLTAwMDI=",
    "jabiz.security.seal.previous-keys=" + PrizeKeyRotationIT.OLD_SEAL})
class PrizeKeyRotationIT extends PlayItSupport {

    /** The test configuration's seal key (config/application.properties), retired here. */
    static final String OLD_SEAL = "dGVzdC1vbmx5LXNlYWwta2V5LW9mLXRoZS1xdWl6YnVrcy10ZXN0cy0wMDAwMDAw";
    private static final SealedSecrets OLD_SEALER = new SealedSecrets(Base64.getDecoder().decode(OLD_SEAL));
    private static final String LINK = "https://capitals.example/premium?code=ROTATE-2026";

    @Autowired
    ProcessExecutor processes;

    @Autowired
    SecretSealer sealer;

    @Autowired
    KeyRotationChecks checks;

    private static String retired(String secret, String context) {
        return OLD_SEALER.seal(secret.getBytes(StandardCharsets.UTF_8), context, new byte[SealedSecrets.IV_BYTES]);
    }

    private KeyRotationProcesses.RekeyOutput rekey() {
        return processes.run(KeyRotationProcesses.REKEY, new KeyRotationProcesses.RekeyInput(null),
                ExecutionOptions.NONE)
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it-rekey")))
            .block().output();
    }

    private void allowDigital(String sponsor) {
        run(RewardMethodProcesses.SET, TestTokens.bearer(tokens, "fs-seal-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ), body("userId", sponsor, "methods",
                List.of(RewardMethod.PLATFORM, RewardMethod.DIGITAL_PRIZE), "note", "D78 test"));
    }

    private Map<String, Object> digitalDraft(String sponsorToken, String name, String delivery) {
        Instant now = clock.instant();
        Map<String, Object> save = complete(version(sponsorToken, name + " quiz"), name, now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("rewardMethod", RewardMethod.DIGITAL_PRIZE);
        save.put("digitalDelivery", delivery);
        save.put("digitalDescription", "A month of Premium");
        return save;
    }

    /**
     * Test set-up only: appends to an append-only table a copy of the row's latest version holding {@code sealed}, as
     * a process would have written it under the retired key.
     */
    private void appendVersion(String table, String key, String id, String column, String sealed) {
        // Every column but the generated ones (the row's own id).
        String columns = String.join(", ", query("SELECT column_name FROM information_schema.columns WHERE "
            + "table_schema = current_schema() AND table_name = ? AND is_identity = 'NO' AND is_generated = 'NEVER' "
            + "ORDER BY ordinal_position", table).stream().map(row -> (String) row.get("column_name")).toList());
        execute("INSERT INTO " + table + " (" + columns + ") SELECT " + columns + " FROM jsonb_populate_record(NULL::"
            + table + ", (SELECT to_jsonb(v) || jsonb_build_object('version_no', v.version_no + 1, "
            + "'effect_start_time', v.effect_start_time + interval '1 second', '" + column + "', ?::text) FROM "
            + "(SELECT * FROM " + table + " WHERE " + key + " = ?::uuid ORDER BY version_no DESC LIMIT 1) v))",
            sealed, id);
    }

    private String latest(String table, String key, String id, String column) {
        return (String) query("SELECT " + column + " FROM " + table + " WHERE " + key + " = ?::uuid ORDER BY "
            + "version_no DESC LIMIT 1", id).getFirst().get(column);
    }

    private long versions(String table, String key, String id) {
        return (Long) query("SELECT count(*) AS n FROM " + table + " WHERE " + key + " = ?::uuid", id).getFirst()
            .get("n");
    }

    private void assertCurrent(String sealed, String context, String secret) {
        assertThat(SealedSecrets.keyIdOf(sealed)).isEqualTo(sealer.keyId());
        assertThat(sealer.open(sealed, context)).isEqualTo(secret);
    }

    @Test
    void theDeclarationsAreValid() {
        assertThat(checks.check()).filteredOn(problem -> problem.severity() == CheckProblem.Severity.ERROR).isEmpty();
        assertThat(checks.check()).extracting(CheckProblem::message)
            .noneMatch(message -> message.contains("declared twice") || message.contains("sensitive"));
    }

    @Test
    void thePrizesSealedUnderTheRetiredKeyOpenAndAreStoredAgainUnderTheCurrentOne() {
        // A shared prize, live, and won once.
        String sponsor = newSponsor(unique("s-d78-shared"));
        allowDigital(sponsor);
        String sponsorToken = sponsorToken(sponsor);
        Map<String, Object> shared = digitalDraft(sponsorToken, "D78 shared", DigitalDelivery.SHARED);
        shared.put("digitalContent", LINK);
        String publicationId = (String) run("QB_PUBLICATION_SAVE", sponsorToken, shared).get("publicationId");
        assertThat(submit(sponsorToken, publicationId)).containsEntry("status", "LIVE");
        String taker = newTaker(unique("t-d78"), "JP");
        String attemptId = (String) play(taker, publicationId, true, true).get("attemptId");
        String prizeId = String.valueOf(query("SELECT digital_prize_id FROM qb_digital_prize_version WHERE "
            + "attempt_id = ? LIMIT 1", attemptId).getFirst().get("digital_prize_id"));

        // A pool with two codes: one the publisher's, one (set up here) loaded by a digital product's provider.
        String poolSponsor = newSponsor(unique("s-d78-pool"));
        allowDigital(poolSponsor);
        String poolToken = sponsorToken(poolSponsor);
        Map<String, Object> pooled = digitalDraft(poolToken, "D78 pool", DigitalDelivery.POOL);
        String poolId = (String) run("QB_PUBLICATION_SAVE", poolToken, pooled).get("publicationId");
        run(DigitalCodeProcesses.LOAD, poolToken, body("publicationId", poolId, "pastedCodes", "D78-0001\nD78-0002"));
        List<Map<String, Object>> codes = query("SELECT code_id, seq, fulfiller_id FROM qb_digital_code WHERE "
            + "publication_id = ?::uuid ORDER BY seq", poolId);
        assertThat(codes).hasSize(2).allSatisfy(code -> assertThat(code.get("fulfiller_id")).isNull());
        String provider = newSponsor(unique("s-d78-provider"));

        // Everything as it was stored under the retired key, each for the context its process sealed it for.
        appendVersion("qb_publication_version", "publication_id", publicationId, "sealed_digital_content",
            retired(LINK, sponsor));
        execute("UPDATE qb_publication_card SET sealed_digital_content = ? WHERE publication_id::text = ?",
            retired(LINK, sponsor), publicationId);
        appendVersion("qb_digital_prize_version", "digital_prize_id", prizeId, "sealed_content",
            retired(LINK, sponsor));
        execute("UPDATE qb_digital_code SET sealed_content = ? WHERE code_id::text = ?",
            retired("D78-0001", poolSponsor), String.valueOf(codes.get(0).get("code_id")));
        execute("UPDATE qb_digital_code SET sealed_content = ?, fulfiller_id = ? WHERE code_id::text = ?",
            retired("D78-0002", provider), provider, String.valueOf(codes.get(1).get("code_id")));
        // The appended versions take effect a second after the ones they copy.
        clock.advance(Duration.ofSeconds(30));
        long publicationVersions = versions("qb_publication_version", "publication_id", publicationId);
        long prizeVersions = versions("qb_digital_prize_version", "digital_prize_id", prizeId);

        KeyRotationProcesses.RekeyOutput first = rekey();
        assertThat(first.sealedValues()).isGreaterThanOrEqualTo(5);
        assertThat(first.unreadable()).isZero();
        assertCurrent(latest("qb_publication_version", "publication_id", publicationId, "sealed_digital_content"),
            sponsor, LINK);
        assertCurrent((String) query("SELECT sealed_digital_content FROM qb_publication_card WHERE publication_id = "
            + "?::uuid", publicationId).getFirst().get("sealed_digital_content"), sponsor, LINK);
        assertCurrent(latest("qb_digital_prize_version", "digital_prize_id", prizeId, "sealed_content"), sponsor,
            LINK);
        List<Map<String, Object>> rekeyed = query("SELECT sealed_content FROM qb_digital_code WHERE publication_id = "
            + "?::uuid ORDER BY seq", poolId);
        assertCurrent((String) rekeyed.get(0).get("sealed_content"), poolSponsor, "D78-0001");
        assertCurrent((String) rekeyed.get(1).get("sealed_content"), provider, "D78-0002");
        // The append-only tables got a new version each, nothing was updated in place.
        assertThat(versions("qb_publication_version", "publication_id", publicationId))
            .isEqualTo(publicationVersions + 1);
        assertThat(versions("qb_digital_prize_version", "digital_prize_id", prizeId)).isEqualTo(prizeVersions + 1);

        // The winner still opens the prize, and nothing is left under the retired key.
        Map<String, Object> opened = run("QB_DIGITAL_PRIZE_OPEN", taker(taker), body("digitalPrizeId", prizeId));
        assertThat(String.valueOf(opened)).contains("ROTATE-2026");
        KeyRotationProcesses.RekeyOutput second = rekey();
        assertThat(second.sealedValues()).isZero();
        assertThat(second.unreadable()).isZero();
    }
}
