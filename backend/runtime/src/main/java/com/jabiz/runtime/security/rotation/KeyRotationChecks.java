package com.jabiz.runtime.security.rotation;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.MfaSecretCipher;
import com.jabiz.security.SealedField;
import com.jabiz.security.SealedFields;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Startup check {@code KEYS} (decision D78): every declared sealed field exists, is a sensitive text field of an entity
 * that can be updated (not write-once) and has a dataset, and is declared once; a key in rotation (a retired JWT, MFA
 * or seal key configured) is a warning until it is removed. Retired integrity keys stay for good and are not reported.
 */
@Component
public class KeyRotationChecks implements PlatformCheck {

    static final String CATEGORY = "KEYS";

    private final ObjectProvider<SealedFields> declared;
    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final JwtService jwt;
    private final MfaSecretCipher mfa;
    private final SecretSealer sealer;

    public KeyRotationChecks(ObjectProvider<SealedFields> declared, EntityDefinitionRegistry entities,
        DatasetRegistry datasets, JwtService jwt, MfaSecretCipher mfa, SecretSealer sealer) {
        this.declared = declared;
        this.entities = entities;
        this.datasets = datasets;
        this.jwt = jwt;
        this.mfa = mfa;
        this.sealer = sealer;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        declared.orderedStream().flatMap(bean -> bean.fields().stream()).forEach(field -> {
            String location = field.entity() + "." + field.field();
            if (!seen.add(location)) {
                problems.add(CheckProblem.error(CATEGORY, location, "the sealed field is declared twice"));
                return;
            }
            sealedField(field, location).ifPresent(problems::add);
        });
        if (jwt.previousSecrets() > 0) {
            problems.add(rotating("jabiz.security.jwt.previous-secrets", jwt.previousSecrets() + " retired JWT "
                + "secret(s) still accepted: remove them once the tokens and mail links signed with them may expire"));
        }
        if (!mfa.previousKeyIds().isEmpty()) {
            problems.add(rotating("jabiz.security.mfa.previous-keys", "retired MFA key(s) " + mfa.previousKeyIds()
                + ": remove them once the job jabiz.key-rotation finds nothing left"));
        }
        if (!sealer.previousKeyIds().isEmpty()) {
            problems.add(rotating("jabiz.security.seal.previous-keys", "retired seal key(s) " + sealer.previousKeyIds()
                + ": remove them once the job jabiz.key-rotation finds nothing left"));
        }
        return problems;
    }

    private static CheckProblem rotating(String location, String message) {
        return CheckProblem.warning(CATEGORY, location, "key rotation in progress: " + message);
    }

    private Optional<CheckProblem> sealedField(SealedField field, String location) {
        Optional<EntityDefinition> entity = entities.find(field.entity());
        if (entity.isEmpty()) {
            return Optional.of(CheckProblem.error(CATEGORY, location, "no such entity"));
        }
        Optional<FieldDefinition> definition = entity.get().findField(field.field());
        if (definition.isEmpty()) {
            return Optional.of(CheckProblem.error(CATEGORY, location, "no such field"));
        }
        if (entity.get().temporal && entity.get().temporalSpec.writeOnce()) {
            return Optional.of(CheckProblem.error(CATEGORY, location, "a write-once entity cannot be stored again "
                + "under a new key (decision D29): keep sealed values in an entity that can be updated"));
        }
        if (datasets.findForEntity(field.entity()).isEmpty()) {
            return Optional.of(CheckProblem.error(CATEGORY, location, "the entity has no dataset to re-key through"));
        }
        if (!definition.get().sensitive()) {
            return Optional.of(CheckProblem.warning(CATEGORY, location, "a sealed field should be sensitive "
                + "(f.sensitive()): read APIs return it otherwise"));
        }
        return Optional.empty();
    }
}
