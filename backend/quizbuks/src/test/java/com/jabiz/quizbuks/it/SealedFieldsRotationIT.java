package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.security.rotation.KeyRotationChecks;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.SealedSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuizBuks's sealed fields (docs/quizbuks/plans/impl/F-F14-sealed-fields.md, platform decision D78): this context
 * runs with a new seal key and the tests' key as the retired one. A mail server password and a Shopify discount code
 * sealed under the retired key, each for the context its process seals it for, are stored again under the current
 * key by the job {@code jabiz.key-rotation} and still open; nothing is left over.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.seal.key=bmV4dC1zZWFsLWtleS1vZi10aGUtcXVpemJ1a3Mta2V5LXJvdGF0aW9uLTAwMDAw",
    "jabiz.security.seal.previous-keys=" + SealedFieldsRotationIT.OLD_SEAL})
class SealedFieldsRotationIT extends QbItSupport {

    /** The test configuration's seal key (config/application.properties), retired here. */
    static final String OLD_SEAL = "dGVzdC1vbmx5LXNlYWwta2V5LW9mLXRoZS1xdWl6YnVrcy10ZXN0cy0wMDAwMDAw";
    private static final SealedSecrets OLD_SEALER = new SealedSecrets(Base64.getDecoder().decode(OLD_SEAL));
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    JobRunner jobs;

    @Autowired
    @Qualifier("keyRotationJob")
    JobDefinition<?> rotation;

    @Autowired
    SecretSealer sealer;

    @Autowired
    KeyRotationChecks checks;

    private static String sealedUnderTheRetiredKey(String secret, String context) {
        byte[] iv = new byte[SealedSecrets.IV_BYTES];
        RANDOM.nextBytes(iv);
        return OLD_SEALER.seal(secret.getBytes(StandardCharsets.UTF_8), context, iv);
    }

    /** Test set-up only: a mail server as {@code QB_MAIL_SERVER_SET} would have stored it under the retired key. */
    private String mailServer(String senderKey, String password) {
        execute("INSERT INTO qb_sponsor_mail_server (sponsor_id, host, port, tls, username, sealed_password,"
            + " from_address, version) VALUES (?, 'smtp.example.com', 465, 'SMTPS', 'shop', ?, 'shop@example.com', 1)",
            senderKey, sealedUnderTheRetiredKey(password, senderKey));
        return senderKey;
    }

    /** Test set-up only: a discount code as the pool's refill would have stored it under the retired key. */
    private String discountCode(String domain, String code) {
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO qbs_discount_code (code_id, batch_id, shop_domain, seq, sealed_code, hint, status,"
            + " version) VALUES (?, ?, ?, 1, ?, 'AB12', 'AVAILABLE', 1)",
            id, UUID.randomUUID().toString(), domain, sealedUnderTheRetiredKey(code, domain));
        return id;
    }

    private String column(String sql, Object key) {
        return (String) query(sql, key).getFirst().values().iterator().next();
    }

    @Test
    void theDeclarationsPassTheStartupCheck() {
        // A rotation in progress is a warning; the declarations themselves raise nothing.
        assertThat(checks.check()).extracting(CheckProblem::severity).containsOnly(CheckProblem.Severity.WARNING);
    }

    @Test
    void theRotationJobStoresQuizbuksSealedValuesAgainUnderTheCurrentKey() {
        String sponsor = mailServer(UUID.randomUUID().toString(), "the shop's password");
        String prospect = mailServer("prospect:" + UUID.randomUUID(), "the owner's password");
        String domain = "k" + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT)
            + ".myshopify.com";
        String code = discountCode(domain, "QB-SAVE-10-XYZ");
        String passwordOf = "SELECT sealed_password FROM qb_sponsor_mail_server WHERE sponsor_id = ?";
        String codeOf = "SELECT sealed_code FROM qbs_discount_code WHERE code_id = ?";
        assertThat(SealedSecrets.keyIdOf(column(passwordOf, sponsor))).isEqualTo(OLD_SEALER.keyId());

        clock.advance(Duration.ofMinutes(10));
        assertThat(jobs.run(rotation, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);

        for (String key : new String[] {sponsor, prospect}) {
            assertThat(SealedSecrets.keyIdOf(column(passwordOf, key))).isEqualTo(sealer.keyId());
        }
        assertThat(sealer.open(column(passwordOf, sponsor), sponsor)).isEqualTo("the shop's password");
        assertThat(sealer.open(column(passwordOf, prospect), prospect)).isEqualTo("the owner's password");
        assertThat(SealedSecrets.keyIdOf(column(codeOf, code))).isEqualTo(sealer.keyId());
        assertThat(sealer.open(column(codeOf, code), domain)).isEqualTo("QB-SAVE-10-XYZ");
        // Through the write path: the ordinary tables' versions moved on.
        assertThat(query("SELECT version FROM qbs_discount_code WHERE code_id = ?", code).getFirst().get("version"))
            .isEqualTo(2L);

        // Nothing is left under the retired key: a later run finds nothing.
        String before = column(codeOf, code);
        clock.advance(Duration.ofMinutes(10));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobs.run(rotation, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(column(codeOf, code)).isEqualTo(before);
    }
}
