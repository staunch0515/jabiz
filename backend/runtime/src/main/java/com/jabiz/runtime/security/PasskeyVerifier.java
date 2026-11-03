package com.jabiz.runtime.security;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.MaliciousCounterValueException;
import com.webauthn4j.verifier.exception.VerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Checks what a browser answers in a passkey ceremony (decision D71; docs/design/10-security.md section 15.16), and
 * writes the options it asks the browser with. Pure computation (webauthn4j): the origin is one of the configured
 * ones, the relying party's id hash, the challenge, the ceremony's type, the user present and verified, an algorithm
 * of {@link #ALGORITHMS}; the attestation is not checked (any format, no trust anchors). An assertion is checked against
 * the stored credential, with its signature counter (a counter that does not grow, while not both are 0, is refused).
 * The answers are WebAuthn's JSON ({@code PublicKeyCredential.toJSON()}): Base64url without padding.
 */
public class PasskeyVerifier {

    private static final Logger log = LoggerFactory.getLogger(PasskeyVerifier.class);

    /** ES256, EdDSA and RS256, in the order of preference the options give. */
    static final List<COSEAlgorithmIdentifier> ALGORITHMS = List.of(COSEAlgorithmIdentifier.ES256,
        COSEAlgorithmIdentifier.EdDSA, COSEAlgorithmIdentifier.RS256);

    /** Longest answer looked at: real ones are a few kilobytes. */
    static final int MAX_RESPONSE_LENGTH = 16 * 1024;

    /** A response that is no valid answer to the challenge. */
    public static final class InvalidPasskeyException extends RuntimeException {
        InvalidPasskeyException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * A checked registration.
     *
     * @param credentialData the attested credential data (AAGUID, id, COSE key), Base64url: what assertions are
     *                       checked against
     */
    public record Registered(String credentialId, String credentialData, long signCount, boolean backedUp,
        String transports, String aaguid) {
        @Override
        public String toString() {
            return "Registered[credentialId=" + credentialId + ", signCount=" + signCount + "]";
        }
    }

    /** A checked assertion: the counter it carried and whether the passkey is synced now. */
    public record Asserted(long signCount, boolean backedUp) {}

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder D64 = Base64.getUrlDecoder();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PasskeyProperties properties;
    private final WebAuthnManager manager;
    private final AttestedCredentialDataConverter credentials;
    private final Set<Origin> origins;

    public PasskeyVerifier(PasskeyProperties properties) {
        this.properties = properties;
        ObjectConverter converter = new ObjectConverter();
        this.manager = WebAuthnManager.createNonStrictWebAuthnManager(converter);
        this.credentials = new AttestedCredentialDataConverter(converter);
        this.origins = properties.origins().stream().map(Origin::new).collect(Collectors.toUnmodifiableSet());
    }

    /** Whether passkeys can be used at all: the relying party and its origins are configured and right. */
    public boolean usable() {
        return properties.configured() && properties.problems().isEmpty();
    }

    // ---- options -----------------------------------------------------------------------------------------------

    /**
     * The options of {@code navigator.credentials.create} ({@code PublicKeyCredentialCreationOptionsJSON}): a
     * discoverable passkey, the user verified, no attestation, none of {@code existing} again.
     */
    public Map<String, Object> creationOptions(String challenge, UUID userId, String userName, String displayName,
        List<StoredCredential> existing) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", challenge);
        options.put("rp", Map.of("id", properties.rpId(), "name", properties.rpName()));
        options.put("user", Map.of("id", userHandle(userId), "name", userName,
            "displayName", displayName == null || displayName.isBlank() ? userName : displayName));
        options.put("pubKeyCredParams", ALGORITHMS.stream()
            .map(alg -> Map.of("type", "public-key", "alg", alg.getValue())).toList());
        options.put("timeout", properties.challengeTtl().toMillis());
        options.put("attestation", "none");
        options.put("authenticatorSelection", Map.of("residentKey", "required", "requireResidentKey", true,
            "userVerification", "required"));
        options.put("excludeCredentials", descriptors(existing));
        return options;
    }

    /**
     * The options of {@code navigator.credentials.get} ({@code PublicKeyCredentialRequestOptionsJSON}): the user
     * verified; {@code allowed} empty for a discoverable sign-in.
     */
    public Map<String, Object> requestOptions(String challenge, List<StoredCredential> allowed) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", challenge);
        options.put("rpId", properties.rpId());
        options.put("timeout", properties.challengeTtl().toMillis());
        options.put("userVerification", "required");
        options.put("allowCredentials", descriptors(allowed));
        return options;
    }

    /** A stored passkey as options name it. */
    public record StoredCredential(String credentialId, String transports) {}

    private static List<Map<String, Object>> descriptors(List<StoredCredential> credentials) {
        List<Map<String, Object>> descriptors = new ArrayList<>();
        for (StoredCredential credential : credentials) {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("type", "public-key");
            descriptor.put("id", credential.credentialId());
            if (credential.transports() != null && !credential.transports().isBlank()) {
                descriptor.put("transports", List.of(credential.transports().split(",")));
            }
            descriptors.add(descriptor);
        }
        return descriptors;
    }

    /** The user handle of a user: the 16 bytes of the id, Base64url. */
    public static String userHandle(UUID userId) {
        ByteBuffer bytes = ByteBuffer.allocate(16);
        bytes.putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits());
        return B64.encodeToString(bytes.array());
    }

    // ---- reading answers ----------------------------------------------------------------------------------------

    /** What an answer says before it is checked: the credential's id and the challenge it answers. */
    public record Claimed(String credentialId, String challenge) {}

    /**
     * Reads the credential's id and the challenge from an answer, before anything is checked (the challenge finds
     * what the answer is for, the id the stored passkey).
     *
     * @throws InvalidPasskeyException when it is no answer
     */
    public static Claimed claimed(String response) {
        JsonNode root = tree(response);
        String id = text(root, "id");
        JsonNode inner = root.get("response");
        String clientData = inner == null ? null : text(inner, "clientDataJSON");
        if (id == null || clientData == null || !"public-key".equals(text(root, "type"))) {
            throw new InvalidPasskeyException("Not a public key credential");
        }
        try {
            JsonNode collected = JSON.readTree(new String(D64.decode(clientData), StandardCharsets.UTF_8));
            String challenge = text(collected, "challenge");
            if (challenge == null) {
                throw new InvalidPasskeyException("No challenge");
            }
            return new Claimed(canonical(id), challenge);
        } catch (IllegalArgumentException | JacksonException e) {
            throw new InvalidPasskeyException("Unreadable client data");
        }
    }

    // ---- checking answers ---------------------------------------------------------------------------------------

    /**
     * Checks a registration answering {@code challenge}.
     *
     * @throws InvalidPasskeyException when it does not hold
     */
    public Registered register(String response, String challenge) {
        requireSize(response);
        try {
            RegistrationData data = manager.parseRegistrationResponseJSON(response);
            List<PublicKeyCredentialParameters> parameters = ALGORITHMS.stream()
                .map(alg -> new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, alg)).toList();
            manager.verify(data, new RegistrationParameters(server(challenge), parameters, true, true));
            AuthenticatorData<?> authenticator = data.getAttestationObject().getAuthenticatorData();
            AttestedCredentialData credential = authenticator.getAttestedCredentialData();
            Set<AuthenticatorTransport> transports = data.getTransports() == null ? Set.of() : data.getTransports();
            String transportList = transports.stream().map(AuthenticatorTransport::getValue).sorted()
                .collect(Collectors.joining(","));
            return new Registered(B64.encodeToString(credential.getCredentialId()),
                B64.encodeToString(credentials.convert(credential)), authenticator.getSignCount(),
                authenticator.isFlagBS(), transportList.isEmpty() ? null : transportList,
                credential.getAaguid() == null ? null : credential.getAaguid().toString());
        } catch (VerificationException | DataConversionException | JacksonException | IllegalArgumentException
                 | NullPointerException e) {
            throw new InvalidPasskeyException("Registration refused: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Checks an assertion answering {@code challenge} with the stored credential.
     *
     * @param userId the user the passkey belongs to: a user handle in the answer must be theirs
     * @throws InvalidPasskeyException when it does not hold
     */
    public Asserted authenticate(String response, String challenge, String credentialData, long storedCount,
        UUID userId) {
        requireSize(response);
        try {
            AuthenticationData data = manager.parseAuthenticationResponseJSON(response);
            byte[] handle = data.getUserHandle();
            if (handle != null && handle.length > 0 && !userHandle(userId).equals(B64.encodeToString(handle))) {
                throw new InvalidPasskeyException("The passkey's user handle is another user's");
            }
            AttestedCredentialData credential = credentials.convert(D64.decode(credentialData));
            CredentialRecordImpl record = new CredentialRecordImpl(new NoneAttestationStatement(), null, null, null,
                storedCount, credential, null, null, null, null);
            manager.verify(data, new AuthenticationParameters(server(challenge), record,
                List.of(credential.getCredentialId()), true, true));
            AuthenticatorData<?> authenticator = data.getAuthenticatorData();
            return new Asserted(authenticator.getSignCount(), authenticator.isFlagBS());
        } catch (MaliciousCounterValueException e) {
            // A counter that went back: the passkey may have been copied (decision D71 item 7).
            log.warn("Passkey of user {} refused: its signature counter did not grow", userId);
            throw new InvalidPasskeyException("Signature counter did not grow");
        } catch (VerificationException | DataConversionException | JacksonException | IllegalArgumentException
                 | NullPointerException e) {
            throw new InvalidPasskeyException("Assertion refused: " + e.getClass().getSimpleName());
        }
    }

    private ServerProperty server(String challenge) {
        return ServerProperty.builder().origins(origins).rpId(properties.rpId())
            .challenge(new DefaultChallenge(D64.decode(challenge))).build();
    }

    private static void requireSize(String response) {
        if (response == null || response.length() > MAX_RESPONSE_LENGTH) {
            throw new InvalidPasskeyException("Missing or oversized response");
        }
    }

    private static JsonNode tree(String response) {
        requireSize(response);
        try {
            JsonNode root = JSON.readTree(response);
            if (root == null || !root.isObject()) {
                throw new InvalidPasskeyException("Not a JSON object");
            }
            return root;
        } catch (JacksonException e) {
            throw new InvalidPasskeyException("Not JSON");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    /** An id as stored: Base64url without padding, whatever padding the client wrote. */
    static String canonical(String base64url) {
        try {
            return B64.encodeToString(D64.decode(base64url));
        } catch (IllegalArgumentException e) {
            throw new InvalidPasskeyException("The credential's id is not Base64url");
        }
    }

    /** Names of the transports a stored list holds. */
    static Set<String> transports(String stored) {
        return stored == null || stored.isBlank() ? Set.of() : new LinkedHashSet<>(List.of(stored.split(",")));
    }
}
