package com.jabiz.runtime.test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A passkey authenticator in software for the tests (decision D71): it answers the platform's options as a browser
 * with a platform authenticator would ({@code PublicKeyCredential.toJSON()}), with an ES256 key per passkey, "none"
 * attestation, and switches to answer wrongly (another origin, no user verification, a counter that goes back).
 */
public final class TestAuthenticator {

    public static final String ORIGIN = "http://localhost";

    /** One passkey the authenticator keeps. */
    public static final class Credential {
        public final byte[] id;
        final KeyPair keys;
        final String userHandle;
        final String rpId;
        public int counter;

        Credential(byte[] id, KeyPair keys, String userHandle, String rpId) {
            this.id = id;
            this.keys = keys;
            this.userHandle = userHandle;
            this.rpId = rpId;
        }

        public String id() {
            return B64.encodeToString(id);
        }
    }

    /** How to answer: rightly by default. */
    public record Answer(String origin, boolean userVerified, Integer counter, boolean synced, String rpId) {
        public static Answer right() {
            return new Answer(ORIGIN, true, null, true, null);
        }

        public Answer origin(String other) {
            return new Answer(other, userVerified, counter, synced, rpId);
        }

        public Answer unverified() {
            return new Answer(origin, false, counter, synced, rpId);
        }

        public Answer counter(int value) {
            return new Answer(origin, userVerified, value, synced, rpId);
        }

        public Answer deviceBound() {
            return new Answer(origin, userVerified, counter, false, rpId);
        }
    }

    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final Base64.Decoder D64 = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    public final List<Credential> credentials = new ArrayList<>();

    /** Creates a passkey for the creation options ({@code options.publicKey}). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(Map<String, Object> options, Answer answer) {
        Map<String, Object> rp = (Map<String, Object>) options.get("rp");
        Map<String, Object> user = (Map<String, Object>) options.get("user");
        String rpId = answer.rpId() != null ? answer.rpId() : (String) rp.get("id");
        byte[] id = new byte[32];
        RANDOM.nextBytes(id);
        Credential credential = new Credential(id, keyPair(), (String) user.get("id"), rpId);
        credentials.add(credential);
        return attest(credential, (String) options.get("challenge"), answer);
    }

    /** Answers creation options with a passkey it has already (to register one passkey twice). */
    public Map<String, Object> createAgain(Map<String, Object> options, Credential credential) {
        return attest(credential, (String) options.get("challenge"), Answer.right());
    }

    private Map<String, Object> attest(Credential credential, String challenge, Answer answer) {
        byte[] clientData = clientData("webauthn.create", challenge, answer.origin());
        ByteArrayOutputStream authData = new ByteArrayOutputStream();
        authData.writeBytes(sha256(credential.rpId.getBytes(StandardCharsets.UTF_8)));
        authData.write(flags(answer) | 0x40);
        authData.writeBytes(ByteBuffer.allocate(4).putInt(credential.counter).array());
        authData.writeBytes(new byte[16]);
        authData.writeBytes(ByteBuffer.allocate(2).putShort((short) credential.id.length).array());
        authData.writeBytes(credential.id);
        authData.writeBytes(coseKey((ECPublicKey) credential.keys.getPublic()));
        Map<Object, Object> attestation = new LinkedHashMap<>();
        attestation.put("fmt", "none");
        attestation.put("attStmt", new LinkedHashMap<>());
        attestation.put("authData", authData.toByteArray());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", B64.encodeToString(clientData));
        response.put("attestationObject", B64.encodeToString(Cbor.encode(attestation)));
        response.put("transports", List.of("hybrid", "internal"));
        return credentialJson(credential, response);
    }

    /**
     * Answers request options ({@code options.publicKey}) with the most recent of its passkeys the options allow (any
     * of the relying party's when they allow every one).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> get(Map<String, Object> options, Answer answer) {
        List<Map<String, Object>> allowed = (List<Map<String, Object>>) options.get("allowCredentials");
        Credential chosen = null;
        for (Credential credential : credentials) {
            if (credential.rpId.equals(options.get("rpId")) && (allowed == null || allowed.isEmpty()
                || allowed.stream().anyMatch(a -> credential.id().equals(a.get("id"))))) {
                chosen = credential;
            }
        }
        if (chosen == null) {
            throw new AssertionError("No passkey the options allow");
        }
        return get(options, chosen, answer);
    }

    /** Answers request options with the given passkey, whatever the options allow. */
    public Map<String, Object> get(Map<String, Object> options, Credential credential, Answer answer) {
        byte[] clientData = clientData("webauthn.get", (String) options.get("challenge"), answer.origin());
        credential.counter = answer.counter() != null ? answer.counter() : credential.counter + 1;
        ByteArrayOutputStream authData = new ByteArrayOutputStream();
        authData.writeBytes(sha256(credential.rpId.getBytes(StandardCharsets.UTF_8)));
        authData.write(flags(answer));
        authData.writeBytes(ByteBuffer.allocate(4).putInt(credential.counter).array());
        byte[] data = authData.toByteArray();
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(credential.keys.getPrivate());
            signer.update(data);
            signer.update(sha256(clientData));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("clientDataJSON", B64.encodeToString(clientData));
            response.put("authenticatorData", B64.encodeToString(data));
            response.put("signature", B64.encodeToString(signer.sign()));
            response.put("userHandle", credential.userHandle);
            return credentialJson(credential, response);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public Credential last() {
        return credentials.getLast();
    }

    private static Map<String, Object> credentialJson(Credential credential, Map<String, Object> response) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", credential.id());
        json.put("rawId", credential.id());
        json.put("type", "public-key");
        json.put("response", response);
        json.put("authenticatorAttachment", "platform");
        json.put("clientExtensionResults", Map.of());
        return json;
    }

    /** UP, UV unless told otherwise, and backup eligible and done for a synced passkey. */
    private static int flags(Answer answer) {
        return 0x01 | (answer.userVerified() ? 0x04 : 0) | (answer.synced() ? 0x08 | 0x10 : 0);
    }

    private static byte[] clientData(String type, String challenge, String origin) {
        return ("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin
            + "\",\"crossOrigin\":false}").getBytes(StandardCharsets.UTF_8);
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The COSE EC2 key (kty 2, alg ES256, crv P-256, x, y). */
    private static byte[] coseKey(ECPublicKey key) {
        Map<Object, Object> cose = new LinkedHashMap<>();
        cose.put(1, 2);
        cose.put(3, -7);
        cose.put(-1, 1);
        cose.put(-2, coordinate(key.getW().getAffineX()));
        cose.put(-3, coordinate(key.getW().getAffineY()));
        return Cbor.encode(cose);
    }

    private static byte[] coordinate(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, fixed, 32 - length, length);
        return fixed;
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Just the CBOR the answers need: maps, text, byte strings and integers. */
    static final class Cbor {

        private Cbor() {}

        static byte[] encode(Object value) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            write(out, value);
            return out.toByteArray();
        }

        private static void write(ByteArrayOutputStream out, Object value) {
            switch (value) {
                case Integer n when n >= 0 -> head(out, 0, n);
                case Integer n -> head(out, 1, -1L - n);
                case byte[] bytes -> {
                    head(out, 2, bytes.length);
                    out.writeBytes(bytes);
                }
                case String text -> {
                    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                    head(out, 3, bytes.length);
                    out.writeBytes(bytes);
                }
                case Map<?, ?> map -> {
                    head(out, 5, map.size());
                    map.forEach((k, v) -> {
                        write(out, k);
                        write(out, v);
                    });
                }
                default -> throw new IllegalArgumentException("Not encoded: " + value);
            }
        }

        private static void head(ByteArrayOutputStream out, int major, long value) {
            int type = major << 5;
            if (value < 24) {
                out.write(type | (int) value);
            } else if (value < 0x100) {
                out.write(type | 24);
                out.write((int) value);
            } else if (value < 0x10000) {
                out.write(type | 25);
                out.writeBytes(ByteBuffer.allocate(2).putShort((short) value).array());
            } else {
                out.write(type | 26);
                out.writeBytes(ByteBuffer.allocate(4).putInt((int) value).array());
            }
        }
    }
}
