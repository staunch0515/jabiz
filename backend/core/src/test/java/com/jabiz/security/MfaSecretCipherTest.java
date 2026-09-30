package com.jabiz.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaSecretCipherTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] IV = new byte[MfaSecretCipher.IV_BYTES];
    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void decryptsForTheSameUserOnly() {
        MfaSecretCipher cipher = new MfaSecretCipher(KEY);
        String stored = cipher.encrypt(SECRET, "user-1", IV);
        assertThat(stored).startsWith("v1:" + cipher.keyId() + ":").doesNotContain(Base32.encode(SECRET));
        assertThat(cipher.decrypt(stored, "user-1")).isEqualTo(SECRET);
        assertThatThrownBy(() -> cipher.decrypt(stored, "user-2"))
            .isInstanceOf(MfaSecretCipher.UnreadableSecretException.class);
    }

    @Test
    void recognisesAnotherKeyAlteredAndMalformedSecrets() {
        MfaSecretCipher cipher = new MfaSecretCipher(KEY);
        String stored = cipher.encrypt(SECRET, "user-1", IV);
        byte[] otherKey = Arrays.copyOf(KEY, KEY.length);
        otherKey[0] = 'X';
        MfaSecretCipher other = new MfaSecretCipher(otherKey);
        assertThat(other.keyId()).isNotEqualTo(cipher.keyId());
        assertThatThrownBy(() -> other.decrypt(stored, "user-1"))
            .isInstanceOf(MfaSecretCipher.UnreadableSecretException.class).hasMessageContaining("another key");

        String[] parts = stored.split(":");
        char last = parts[3].charAt(0);
        String altered = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + (last == 'A' ? 'B' : 'A')
            + parts[3].substring(1);
        assertThatThrownBy(() -> cipher.decrypt(altered, "user-1"))
            .isInstanceOf(MfaSecretCipher.UnreadableSecretException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:x", "user-1"))
            .isInstanceOf(MfaSecretCipher.UnreadableSecretException.class);
        assertThatThrownBy(() -> cipher.decrypt(null, "user-1"))
            .isInstanceOf(MfaSecretCipher.UnreadableSecretException.class);
        assertThatThrownBy(() -> cipher.decrypt(parts[0] + ":" + parts[1] + ":!!:" + parts[3], "user-1"))
            .isInstanceOf(MfaSecretCipher.UnreadableSecretException.class);
    }

    @Test
    void refusesShortKeysAndBadIvs() {
        assertThatThrownBy(() -> new MfaSecretCipher(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        MfaSecretCipher cipher = new MfaSecretCipher(KEY);
        assertThatThrownBy(() -> cipher.encrypt(SECRET, "u", new byte[4])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encrypt(SECRET, " ", IV)).isInstanceOf(IllegalArgumentException.class);
    }
}
