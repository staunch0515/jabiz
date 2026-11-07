package com.jabiz.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SealedSecretsTest {

    private static final byte[] KEY = "a-test-key-of-thirty-two-bytes--".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "another-key-of-thirty-two-bytes-".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IV = new byte[SealedSecrets.IV_BYTES];

    @Test
    void aSecretOpensForItsContextUnderItsKeyOnly() {
        SealedSecrets sealer = new SealedSecrets(KEY);
        String stored = sealer.seal("hunter2".getBytes(StandardCharsets.UTF_8), "sponsor-1", IV);
        assertThat(stored).startsWith("s1:" + sealer.keyId() + ":");
        assertThat(SealedSecrets.isSealed(stored)).isTrue();
        assertThat(new String(sealer.open(stored, "sponsor-1"), StandardCharsets.UTF_8)).isEqualTo("hunter2");
        assertThatThrownBy(() -> sealer.open(stored, "sponsor-2"))
            .isInstanceOf(SealedSecrets.UnreadableSecretException.class).hasMessageContaining("context");
        assertThatThrownBy(() -> new SealedSecrets(OTHER).open(stored, "sponsor-1"))
            .isInstanceOf(SealedSecrets.UnreadableSecretException.class).hasMessageContaining("another key");
        assertThatThrownBy(() -> sealer.open(stored.substring(0, stored.length() - 2) + "AA", "sponsor-1"))
            .isInstanceOf(SealedSecrets.UnreadableSecretException.class);
        assertThatThrownBy(() -> sealer.open("v1:x:y:z", "sponsor-1"))
            .isInstanceOf(SealedSecrets.UnreadableSecretException.class).hasMessageContaining("Not a sealed");
    }

    @Test
    void theKeyIsDerivedUnderItsOwnLabelSoTheMfaKeyIsAnotherKey() {
        // The same material gives the MFA cipher another key id: a TOTP secret never opens as a sealed secret.
        assertThat(new SealedSecrets(KEY).keyId()).isNotEqualTo(new MfaSecretCipher(KEY).keyId());
        assertThat(SealedSecrets.isSealed(new MfaSecretCipher(KEY).encrypt(new byte[4], "u", IV))).isFalse();
    }

    @Test
    void shortKeysBlankContextsAndWrongIvsAreRefused() {
        assertThatThrownBy(() -> new SealedSecrets(new byte[16])).isInstanceOf(IllegalArgumentException.class);
        SealedSecrets sealer = new SealedSecrets(KEY);
        assertThatThrownBy(() -> sealer.seal(new byte[1], " ", IV)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sealer.seal(new byte[1], "c", new byte[4])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valuesUnderARetiredKeyStillOpenAndNewOnesUseTheCurrentKey() {
        byte[] oldKey = "an-old-key-of-these-tests-0000000001".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] newKey = "a-new-key-of-these-tests-00000000002".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SealedSecrets before = new SealedSecrets(oldKey);
        String stored = before.seal("secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "context-1",
            new byte[SealedSecrets.IV_BYTES]);
        SealedSecrets rotating = new SealedSecrets(newKey, java.util.List.of(oldKey));
        // Decision D78: the retired key still opens what it stored; the current key stores.
        assertThat(new String(rotating.open(stored, "context-1"), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("secret");
        String renewed = rotating.seal("secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "context-1",
            new byte[SealedSecrets.IV_BYTES]);
        assertThat(SealedSecrets.keyIdOf(renewed)).isEqualTo(rotating.keyId());
        assertThat(SealedSecrets.keyIdOf(stored)).isEqualTo(before.keyId()).isEqualTo(rotating.previousKeyIds().getFirst());
        assertThat(stored).startsWith(SealedSecrets.storedPrefix(before.keyId()));
        assertThat(SealedSecrets.keyIdOf("not stored")).isNull();
        assertThat(SealedSecrets.keyIdOf(null)).isNull();
        // The retired key opens nothing new, and once it is removed the old value no longer opens.
        assertThatThrownBy(() -> before.open(renewed, "context-1"))
            .isInstanceOf(SealedSecrets.UnreadableSecretException.class);
        assertThatThrownBy(() -> new SealedSecrets(newKey).open(stored, "context-1"))
            .isInstanceOf(SealedSecrets.UnreadableSecretException.class);
        // The context still binds under a retired key.
        assertThatThrownBy(() -> rotating.open(stored, "context-2"))
            .isInstanceOf(SealedSecrets.UnreadableSecretException.class);
        assertThatThrownBy(() -> new SealedSecrets(newKey, java.util.List.of(newKey))).hasMessageContaining("current key");
        assertThatThrownBy(() -> new SealedSecrets(newKey, java.util.List.of(new byte[8])))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
