package com.jabiz.runtime.integrity;

import com.jabiz.integrity.IntegrityKey;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The integrity key is required outside the dev profile (docs/design/21-audit-retention.md section 2.4). */
class IntegrityKeySettingsTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
        "an-integrity-key-of-thirty-two-bytes-at-least".getBytes(StandardCharsets.UTF_8));

    @Test
    void withoutAKeyOnlyTheDevProfileStarts() {
        assertThatThrownBy(() -> IntegritySettings.Beans.key("", false))
            .hasMessageContaining("JABIZ_INTEGRITY_KEY");
        assertThatThrownBy(() -> IntegritySettings.Beans.key(null, false))
            .hasMessageContaining("JABIZ_INTEGRITY_KEY");
        assertThat(IntegritySettings.Beans.key(" ", true)).isEqualTo(new IntegrityKey(
            IntegritySettings.Beans.DEVELOPMENT_KEY.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theKeyIsBase64OfAtLeast32Bytes() {
        assertThat(IntegritySettings.Beans.key(KEY, false)).isEqualTo(IntegritySettings.Beans.key(" " + KEY, true));
        assertThatThrownBy(() -> IntegritySettings.Beans.key("not base64!", false))
            .hasMessageContaining("not valid Base64");
        assertThatThrownBy(() -> IntegritySettings.Beans.key(Base64.getEncoder().encodeToString(new byte[31]), false))
            .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void settingsMustBePositive() {
        assertThatThrownBy(() -> new IntegritySettings(0, 1, "c", "c")).hasMessageContaining("positive");
        assertThat(new IntegritySettings(1, 1, "c", "v").sealCron()).isEqualTo("c");
    }
}
