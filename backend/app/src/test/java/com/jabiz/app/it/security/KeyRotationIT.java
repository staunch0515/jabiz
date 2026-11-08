package com.jabiz.app.it.security;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.context.RequestContext;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.security.rotation.KeyRotationChecks;
import com.jabiz.runtime.security.rotation.KeyRotationProcesses;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.Base32;
import com.jabiz.security.MfaSecretCipher;
import com.jabiz.security.SealedField;
import com.jabiz.security.SealedFields;
import com.jabiz.security.SealedSecrets;
import com.jabiz.security.Totp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Key rotation (decision D78; ROADMAP 17zw): this context runs with new MFA and seal keys and the test keys as the
 * retired ones. Values stored under the retired keys still open; {@code KEY_ROTATION_REKEY} stores them again under
 * the current keys through the ordinary write path, leaves a value that does not open as it is, and then finds nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.mfa.key=bmV4dC1tZmEta2V5LW9mLXRoZS1rZXktcm90YXRpb24tdGVzdHMtMDAwMDAy",
    "jabiz.security.mfa.previous-keys=" + KeyRotationIT.OLD_MFA,
    "jabiz.security.seal.key=bmV4dC1zZWFsLWtleS1vZi10aGUta2V5LXJvdGF0aW9uLXRlc3RzLTAwMDAy",
    "jabiz.security.seal.previous-keys=" + KeyRotationIT.OLD_SEAL})
class KeyRotationIT extends SecurityItSupport {

    /** The test configuration's keys (config/application.properties), retired here. */
    static final String OLD_MFA = "dGVzdC1vbmx5LW1mYS1rZXktb2YtdGhlLWphYml6LWludGVncmF0aW9uLXRlc3RzLTA=";
    static final String OLD_SEAL = "dGVzdC1vbmx5LXNlYWwta2V5LW9mLXRoZS1qYWJpei1pbnRlZ3JhdGlvbi10ZXN0cy0wMDA=";
    private static final String PASSWORD = "correct horse battery";

    /** A sealed field of the demo application for these tests: the warehouse name, sealed for the code. */
    @TestConfiguration
    static class Sealed {
        @Bean
        SealedFields itSealedFields() {
            return () -> List.of(SealedField.of(CommerceEntities.WAREHOUSE, "warehouseName", "warehouseCode"));
        }
    }

    @Autowired
    ProcessExecutor processes;

    @Autowired
    MfaSecretCipher mfa;

    @Autowired
    SecretSealer sealer;

    @Autowired
    KeyRotationChecks checks;

    @Test
    void sealedFieldsMustBeUpdatableSensitiveTextOfAnExistingEntity() {
        // The demo's declaration (warehouse name: not sensitive) is a warning; a rotation in progress is one too.
        assertThat(checks.check()).extracting(CheckProblem::severity).containsOnly(CheckProblem.Severity.WARNING);
        assertThat(checks.declarations(List.of(
                SealedField.of(CommerceEntities.WAREHOUSE, "warehouseCode", "warehouseCode"),
                SealedField.of("NoSuchEntity", "x", "y"),
                SealedField.of(CommerceEntities.WAREHOUSE, "noSuchField", "warehouseCode"),
                SealedField.of(CommerceEntities.WAREHOUSE, "warehouseName", "warehouseCode"),
                SealedField.of(CommerceEntities.WAREHOUSE, "warehouseName", "warehouseCode"))))
            .extracting(CheckProblem::severity, CheckProblem::message)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.ERROR, "an immutable field cannot be stored "
                    + "again under a new key: declare it mutable (it stays sensitive and written by processes only)"),
                org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.ERROR, "no such entity"),
                org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.ERROR, "no such field"),
                org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.WARNING, "a sealed field should be "
                    + "sensitive (f.sensitive()): read APIs return it otherwise"),
                org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.ERROR, "the sealed field is declared twice"));
    }

    private static final MfaSecretCipher OLD_MFA_CIPHER = new MfaSecretCipher(Base64.getDecoder().decode(OLD_MFA));
    private static final SealedSecrets OLD_SEALER = new SealedSecrets(Base64.getDecoder().decode(OLD_SEAL));

    private KeyRotationProcesses.RekeyOutput rekey() {
        return processes.run(KeyRotationProcesses.REKEY, new KeyRotationProcesses.RekeyInput(null),
                ExecutionOptions.NONE)
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it-rekey")))
            .block().output();
    }

    /** Enrols a user, then stores the secret again under the retired key as a new version (as before the change). */
    private String enrolledUnderTheRetiredKey(String name) {
        String userId = userWith(name, PASSWORD);
        String bearer = bearerOf(signIn(name, PASSWORD));
        String secret = (String) post("/api/auth/mfa/enroll", bearer, Map.of())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("secret");
        post("/api/auth/mfa/enroll/confirm", bearer, Map.of("code", code(secret))).expectStatus().isOk();
        clock.advance(Duration.ofSeconds(30));
        String retired = OLD_MFA_CIPHER.encrypt(Base32.decode(secret), userId, new byte[MfaSecretCipher.IV_BYTES]);
        // Test set-up only: a version as the enrolment would have written it under the retired key.
        execute("INSERT INTO sec_user_mfa_version (user_mfa_id, version_no, effect_start_time, created_time,"
            + " process_seq_id, is_deleted, user_id, secret, confirmed, confirmed_time, confirmed_step, recovery_codes)"
            + " SELECT user_mfa_id, version_no + 1, effect_start_time + interval '1 second', created_time,"
            + " process_seq_id, is_deleted, user_id, ?, confirmed, confirmed_time, confirmed_step, recovery_codes"
            + " FROM sec_user_mfa_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1", retired, userId);
        clock.advance(Duration.ofSeconds(30));
        return secret;
    }

    private String code(String secret) {
        return Totp.code(Base32.decode(secret), Totp.step(clock.instant()));
    }

    private String currentSecretOf(String userName) {
        return (String) query("SELECT m.secret FROM sec_user_mfa_version m JOIN sec_user_version u"
            + " ON u.user_id = m.user_id WHERE u.user_name = ? ORDER BY m.version_no DESC LIMIT 1", userName)
            .getFirst().get("secret");
    }

    private String warehouse(String code, String name) {
        post("/api/datasets/" + CommerceEntities.WAREHOUSE_DATASET + "/commit", bearer("*"), Map.of("changes",
            List.of(Map.of("action", "INSERT", "attributes", Map.of("warehouseCode", code, "warehouseName", name,
                "active", true))))).expectStatus().isOk();
        return code;
    }

    private String nameOf(String code) {
        return (String) query("SELECT warehouse_name FROM warehouse_version WHERE warehouse_code = ?"
            + " ORDER BY version_no DESC LIMIT 1", code).getFirst().get("warehouse_name");
    }

    private static String code() {
        return "K" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String sealedUnderTheRetiredKey(String secret, String context) {
        return OLD_SEALER.seal(secret.getBytes(StandardCharsets.UTF_8), context, new byte[SealedSecrets.IV_BYTES]);
    }

    @Test
    void valuesUnderTheRetiredKeysOpenAndAreStoredAgainUnderTheCurrentOnes() {
        String user = unique("rotating");
        String secret = enrolledUnderTheRetiredKey(user);
        assertThat(MfaSecretCipher.keyIdOf(currentSecretOf(user))).isEqualTo(OLD_MFA_CIPHER.keyId());
        String sealed = code();
        warehouse(sealed, sealedUnderTheRetiredKey("the shop's password", sealed));
        // Sealed for another context than the declared one: it does not open, now or later.
        String foreign = code();
        warehouse(foreign, sealedUnderTheRetiredKey("someone else's", "another context"));

        // Before the re-keying the retired key still opens the second factor: the user signs in with a code.
        Map<String, Object> challenge = signIn(user, PASSWORD);
        assertThat(challenge.get("status")).isEqualTo("MFA_REQUIRED");
        post("/api/auth/challenge/verify", null, Map.of("challenge", challenge.get("challenge"),
            "code", code(secret))).expectStatus().isOk();
        clock.advance(Duration.ofSeconds(30));

        KeyRotationProcesses.RekeyOutput first = rekey();
        assertThat(first.mfaSecrets()).isEqualTo(1);
        assertThat(first.sealedValues()).isEqualTo(1);
        assertThat(first.unreadable()).isEqualTo(1);
        assertThat(first.more()).isFalse();
        assertThat(MfaSecretCipher.keyIdOf(currentSecretOf(user))).isEqualTo(mfa.keyId());
        assertThat(SealedSecrets.keyIdOf(nameOf(sealed))).isEqualTo(sealer.keyId());
        assertThat(sealer.open(nameOf(sealed), sealed)).isEqualTo("the shop's password");
        assertThat(SealedSecrets.keyIdOf(nameOf(foreign))).as("left as it was").isEqualTo(OLD_SEALER.keyId());

        // After it: the same code generator still signs in, and nothing is left but the value that does not open.
        Map<String, Object> again = signIn(user, PASSWORD);
        post("/api/auth/challenge/verify", null, Map.of("challenge", again.get("challenge"),
            "code", code(secret))).expectStatus().isOk();
        KeyRotationProcesses.RekeyOutput second = rekey();
        assertThat(second.mfaSecrets()).isZero();
        assertThat(second.sealedValues()).isZero();
        assertThat(second.unreadable()).isEqualTo(1);
        // A value that does not open is still under the retired key: it cannot be removed yet (D78 item 8).
        assertThat(second.undeclaredSealed()).isEmpty();
        assertThat(second.retiredKeysRemovable()).isFalse();
        // The re-keying went through the write path: new versions, audited with the secret masked.
        assertThat(query("SELECT count(*) AS n FROM warehouse_version WHERE warehouse_code = ?", sealed)
            .getFirst().get("n")).isEqualTo(2L);
    }
}
