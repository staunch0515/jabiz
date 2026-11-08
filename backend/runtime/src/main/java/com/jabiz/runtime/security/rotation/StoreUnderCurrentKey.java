package com.jabiz.runtime.security.rotation;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.BlockingStep;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.MfaSecretCipher;
import com.jabiz.security.SealedSecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The second step of {@code KEY_ROTATION_REKEY}: opens each value found with the retired key it is under and stores it
 * under the current key, as an update of its row (the platform commits them at the end of the process, audited with
 * the value masked). A value that does not open - another context than declared, altered - is left as it is and
 * counted: it would not open after the change either. Blocking: fresh random IVs.
 */
@Component
class StoreUnderCurrentKey implements BlockingStep<NoMetadata, ProcessContext> {

    private static final Logger log = LoggerFactory.getLogger(StoreUnderCurrentKey.class);

    private final MfaSecretCipher mfa;
    private final SecretSealer sealer;
    private final SecureRandom random = new SecureRandom();

    StoreUnderCurrentKey(MfaSecretCipher mfa, SecretSealer sealer) {
        this.mfa = mfa;
        this.sealer = sealer;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run(NoMetadata metadata, ProcessContext ctx) {
        List<RetiredValue> found = (List<RetiredValue>) ctx.get(KeyRotationProcesses.FOUND, List.class);
        int mfaSecrets = 0;
        int sealedValues = 0;
        int unreadable = 0;
        java.util.Set<?> takenBack = ctx.contains(TakeBackSecondFactors.TAKEN_BACK_IDS)
            ? ctx.get(TakeBackSecondFactors.TAKEN_BACK_IDS, java.util.Set.class) : java.util.Set.of();
        // One update per row with every value stored again: two sealed fields of one row are found as two values with
        // the same version, and a second update at that version would fail the whole run (ROADMAP 17zz4).
        Map<String, RowUpdate> updates = new java.util.LinkedHashMap<>();
        for (RetiredValue value : found) {
            if (value.target().kind() == RetiredValue.Kind.MFA && takenBack.contains(String.valueOf(value.row().id()))) {
                // Taken back in this run: no longer confirmed, a new one replaces it; written once.
                continue;
            }
            String stored = value.stored();
            String context = value.context();
            String renewed;
            try {
                renewed = value.target().kind() == RetiredValue.Kind.MFA ? reencrypt(stored, context)
                    : sealer.reseal(stored, context);
            } catch (MfaSecretCipher.UnreadableSecretException | SealedSecrets.UnreadableSecretException
                     | IllegalArgumentException e) {
                unreadable++;
                log.warn("Key rotation: {} does not open ({}): left under the retired key", value, e.getMessage());
                continue;
            }
            updates.computeIfAbsent(value.row().entityType() + "\n" + value.row().id(),
                    key -> new RowUpdate(value.row().entityType(), value.row().id(), value.row().version(),
                        new java.util.LinkedHashMap<>()))
                .values().put(value.target().field(), renewed);
            if (value.target().kind() == RetiredValue.Kind.MFA) {
                mfaSecrets++;
            } else {
                sealedValues++;
            }
        }
        updates.values().forEach(update -> ctx.changes().update(update.entityType(), update.id(), update.version(),
            Map.copyOf(update.values())));
        if (!found.isEmpty()) {
            log.info("Key rotation: {} TOTP secrets and {} sealed values stored under the current key, {} unreadable",
                mfaSecrets, sealedValues, unreadable);
        }
        int takenBackCount = takenBack.size();
        ctx.put(KeyRotationProcesses.OUTPUT, new KeyRotationProcesses.RekeyOutput(mfaSecrets, sealedValues,
            unreadable, ctx.contains(KeyRotationProcesses.MORE), takenBackCount,
            List.of(), false));
    }

    private String reencrypt(String stored, String userId) {
        byte[] secret = mfa.decrypt(stored, userId);
        try {
            byte[] iv = new byte[MfaSecretCipher.IV_BYTES];
            random.nextBytes(iv);
            return mfa.encrypt(secret, userId, iv);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /** The values of one row stored again, written as one update at the version they were read at. */
    private record RowUpdate(String entityType, Object id, long version, Map<String, Object> values) {}
}
