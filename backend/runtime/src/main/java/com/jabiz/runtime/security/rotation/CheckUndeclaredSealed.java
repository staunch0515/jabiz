package com.jabiz.runtime.security.rotation;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.MfaSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * The last step of {@code KEY_ROTATION_REKEY} (decision D78 item 8): values under a retired seal key in fields not
 * declared as sealed ({@link RetiredSealScan}). The re-keying does not reach them, so the retired key must stay. A run
 * that stored or took back nothing and finds such values fails, naming the fields (nothing of the run is lost); a run
 * with work done keeps it and lists them in its output. A run that finds nothing left anywhere says that the retired
 * keys can be removed.
 */
@Component
class CheckUndeclaredSealed implements StepHandler<NoMetadata, ProcessContext> {

    private static final Logger log = LoggerFactory.getLogger(CheckUndeclaredSealed.class);

    private final RetiredSealScan scan;
    private final MfaSecretCipher mfa;
    private final SecretSealer sealer;

    CheckUndeclaredSealed(RetiredSealScan scan, MfaSecretCipher mfa, SecretSealer sealer) {
        this.scan = scan;
        this.mfa = mfa;
        this.sealer = sealer;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        return scan.undeclared().doOnNext(findings -> {
            KeyRotationProcesses.RekeyOutput out = ctx.get(KeyRotationProcesses.OUTPUT,
                KeyRotationProcesses.RekeyOutput.class);
            List<?> found = ctx.get(KeyRotationProcesses.FOUND, List.class);
            List<String> undeclared = findings.stream().map(RetiredSealScan.Finding::toString).toList();
            boolean nothingDone = out.mfaSecrets() == 0 && out.sealedValues() == 0
                && out.secondFactorsTakenBack() == 0;
            if (!undeclared.isEmpty()) {
                log.error("Key rotation: values under a retired seal key remain in undeclared fields {}: declare them "
                    + "(SealedFields) before removing the retired key", undeclared);
                if (nothingDone) {
                    throw new BusinessRuleViolationException(new Violation(null,
                        PlatformErrorCodes.KEY_ROTATION_UNDECLARED_SEALED,
                        "values under a retired seal key remain in undeclared fields " + undeclared,
                        Map.of("fields", String.join(", ", undeclared))));
                }
            }
            boolean rotating = !mfa.previousKeyIds().isEmpty() || !sealer.previousKeyIds().isEmpty();
            boolean removable = rotating && found.isEmpty() && undeclared.isEmpty();
            if (removable) {
                log.info("Key rotation: nothing is left under the retired keys (MFA {}, seal {}): they can be removed",
                    mfa.previousKeyIds(), sealer.previousKeyIds());
            }
            ctx.put(KeyRotationProcesses.OUTPUT, new KeyRotationProcesses.RekeyOutput(out.mfaSecrets(),
                out.sealedValues(), out.unreadable(), out.more(), out.secondFactorsTakenBack(), undeclared,
                removable));
        }).then();
    }
}
