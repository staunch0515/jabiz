package com.jabiz.runtime.push;

import com.nimbusds.jose.jwk.Curve;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The encryption of a Web Push message (RFC 8291 with the {@code aes128gcm} content coding of RFC 8188; decision D69):
 * the payload is encrypted for the browser's subscription (its P-256 key and authentication secret) with a key agreed
 * from a fresh key pair of this server, in one record. Pure apart from the JDK's cryptography; the caller supplies the
 * fresh key pair and salt (both random), so that the result can be checked against the RFC's example.
 */
public final class WebPushCrypto {

    /** The record size written into the header: the whole message is one record, never more than this. */
    static final int RECORD_SIZE = 4096;
    /** The largest message body every push service must accept (RFC 8030 section 7.2). */
    static final int MAX_BODY = 4096;
    /** The header before the record: salt (16), record size (4), key length (1) and this server's public key (65). */
    static final int HEADER = 16 + 4 + 1 + 65;
    /** The most plaintext a message carries: the body less the header, the tag (16) and the padding delimiter. */
    public static final int MAX_PLAINTEXT = MAX_BODY - HEADER - 16 - 1;
    private static final ECParameterSpec P256 = Curve.P_256.toECParameterSpec();

    private WebPushCrypto() {}

    /**
     * The message body: header (salt, record size, this server's public key) and the one encrypted record.
     *
     * @param plaintext  at most {@link #MAX_PLAINTEXT} bytes
     * @param uaPublic   the subscription's {@code p256dh} key, an uncompressed point of 65 bytes
     * @param authSecret the subscription's {@code auth} secret, 16 bytes
     * @param asKeys     a fresh P-256 key pair of this server, used for this message only
     * @param salt       16 random bytes, used for this message only
     */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("A push message carries at most " + MAX_PLAINTEXT + " bytes");
        }
        if (salt.length != 16 || authSecret.length < 16) {
            throw new IllegalArgumentException("The salt is 16 bytes, the authentication secret at least 16");
        }
        try {
            byte[] asPublic = uncompressed((ECPublicKey) asKeys.getPublic());
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(asKeys.getPrivate());
            agreement.doPhase(publicKey(uaPublic), true);
            byte[] ecdhSecret = agreement.generateSecret();
            // RFC 8291 section 3.4: the shared secret, mixed with the authentication secret.
            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, uaPublic,
                asPublic, new byte[] {1});
            byte[] ikm = hmac(prkKey, keyInfo);
            // RFC 8188 section 2.2 and 2.3: the content encryption key and nonce from the salt.
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, info("Content-Encoding: aes128gcm")), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, info("Content-Encoding: nonce")), 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            // The last (and only) record ends with the delimiter 0x02, without further padding.
            byte[] record = cipher.doFinal(concat(plaintext, new byte[] {2}));
            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), record);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt the push message", e);
        }
    }

    /** A P-256 public key from its uncompressed point (65 bytes, {@code 0x04 || x || y}). */
    public static ECPublicKey publicKey(byte[] point) {
        if (point.length != 65 || point[0] != 4) {
            throw new IllegalArgumentException("not an uncompressed P-256 point");
        }
        try {
            ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(point, 33, 65)));
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, P256));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("not a point of P-256");
        }
    }

    /** A P-256 private key from its scalar (32 bytes), as VAPID keys are written. */
    public static ECPrivateKey privateKey(byte[] scalar) {
        if (scalar.length != 32) {
            throw new IllegalArgumentException("not a P-256 private key (32 bytes)");
        }
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(
                new ECPrivateKeySpec(new BigInteger(1, scalar), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a P-256 private key");
        }
    }

    /** Whether the two keys are one pair: a signature made with the private key verifies with the public one. */
    public static boolean pair(ECPublicKey publicKey, ECPrivateKey privateKey) {
        try {
            byte[] probe = "jabiz VAPID key pair".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();
            java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** The uncompressed point of a P-256 public key: {@code 0x04 || x || y}, 65 bytes. */
    public static byte[] uncompressed(ECPublicKey key) {
        byte[] point = new byte[65];
        point[0] = 4;
        copy(key.getW().getAffineX(), point, 1);
        copy(key.getW().getAffineY(), point, 33);
        return point;
    }

    /** Base64url without padding, as Web Push writes keys; tolerant of padding and standard Base64 on input. */
    public static byte[] base64url(String text) {
        return Base64.getUrlDecoder().decode(text.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    public static String base64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void copy(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        int length = bytes.length - start;
        System.arraycopy(bytes, start, target, offset + 32 - length, length);
    }

    private static byte[] info(String text) {
        return concat(text.getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1});
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
