package com.jabiz.runtime.push;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;

import static com.jabiz.runtime.push.WebPushCrypto.base64url;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Web Push encryption against the example of RFC 8291 section 5. */
class WebPushCryptoTest {

    // RFC 8291 section 5.
    static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6"
        + "TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    static KeyPair serverKeys() {
        return new KeyPair(WebPushCrypto.publicKey(base64url(AS_PUBLIC)), WebPushCrypto.privateKey(base64url(AS_PRIVATE)));
    }

    @Test
    void theRfcsExampleEncryptsToTheRfcsBody() {
        byte[] body = WebPushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8), base64url(UA_PUBLIC),
            base64url(AUTH_SECRET), serverKeys(), base64url(SALT));
        assertThat(base64url(body)).isEqualTo(BODY);
    }

    @Test
    void keysAreReadAndWrittenAsWebPushWritesThem() {
        ECPublicKey key = WebPushCrypto.publicKey(base64url(UA_PUBLIC));
        assertThat(base64url(WebPushCrypto.uncompressed(key))).isEqualTo(UA_PUBLIC);
        assertThat(WebPushCrypto.base64url(UA_PUBLIC + "==")).isEqualTo(base64url(UA_PUBLIC));
        assertThatThrownBy(() -> WebPushCrypto.publicKey(new byte[65])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushCrypto.privateKey(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[WebPushCrypto.MAX_PLAINTEXT + 1], base64url(UA_PUBLIC),
            base64url(AUTH_SECRET), serverKeys(), base64url(SALT))).isInstanceOf(IllegalArgumentException.class);
        // The largest message fills exactly the 4096-byte body every push service accepts.
        assertThat(WebPushCrypto.encrypt(new byte[WebPushCrypto.MAX_PLAINTEXT], base64url(UA_PUBLIC),
            base64url(AUTH_SECRET), serverKeys(), base64url(SALT))).hasSize(4096);
        assertThat(WebPushCrypto.pair(WebPushCrypto.publicKey(base64url(AS_PUBLIC)),
            WebPushCrypto.privateKey(base64url(AS_PRIVATE)))).isTrue();
        assertThat(WebPushCrypto.pair(WebPushCrypto.publicKey(base64url(UA_PUBLIC)),
            WebPushCrypto.privateKey(base64url(AS_PRIVATE)))).isFalse();
    }
}
