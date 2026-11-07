package com.jabiz.app.it.security;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.security.rotation.KeyRotationChecks;
import com.jabiz.runtime.security.rotation.KeyRotationProcesses;
import com.jabiz.runtime.security.rotation.RetiredSealScan;
import com.jabiz.security.SealedSecrets;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Values under a retired seal key in a field nobody declared as sealed (decision D78 item 8; ROADMAP 17zzz): here the
 * warehouse name holds one, and no {@code SealedFields} bean names it. The scan finds it by how sealed values begin,
 * the startup check lists it (a warning at startup, an error in {@code platformCheck}), the gauge counts it, and the
 * re-keying fails rather than say the retired key can go; once the value is gone it says so.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.seal.key=bmV4dC1zZWFsLWtleS1vZi10aGUta2V5LXJvdGF0aW9uLXRlc3RzLTAwMDAy",
    "jabiz.security.seal.previous-keys=" + KeyRotationIT.OLD_SEAL})
class KeyRotationUndeclaredIT extends SecurityItSupport {

    private static final SealedSecrets OLD_SEALER =
        new SealedSecrets(Base64.getDecoder().decode(KeyRotationIT.OLD_SEAL));

    @Autowired
    ProcessExecutor processes;

    @Autowired
    RetiredSealScan scan;

    @Autowired
    KeyRotationChecks checks;

    @Autowired
    MeterRegistry meters;

    private KeyRotationProcesses.RekeyOutput rekey() {
        return processes.run(KeyRotationProcesses.REKEY, new KeyRotationProcesses.RekeyInput(null),
                ExecutionOptions.NONE)
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it-rekey")))
            .block().output();
    }

    private void commit(Map<String, Object> change) {
        post("/api/datasets/" + CommerceEntities.WAREHOUSE_DATASET + "/commit", bearer("*"),
            Map.of("changes", List.of(change))).expectStatus().isOk();
    }

    private double gauge() {
        return meters.get("jabiz.keys.undeclared-sealed").gauge().value();
    }

    @Test
    void aSealedValueInAnUndeclaredFieldKeepsTheRetiredKeyUntilItIsGone() {
        // Nothing under the retired key anywhere: the job says the key can be removed.
        assertThat(scan.undeclared().block()).isEmpty();
        KeyRotationProcesses.RekeyOutput clean = rekey();
        assertThat(clean.retiredKeysRemovable()).isTrue();
        assertThat(clean.undeclaredSealed()).isEmpty();

        // A process sealed a secret into the warehouse name, which nobody declared.
        String code = "U" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String sealed = OLD_SEALER.seal("a password".getBytes(StandardCharsets.UTF_8), code,
            new byte[SealedSecrets.IV_BYTES]);
        commit(Map.of("action", "INSERT", "attributes", Map.of("warehouseCode", code, "warehouseName", sealed,
            "active", true)));
        // Ordinary text is not taken for a sealed value.
        String plain = "U" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        commit(Map.of("action", "INSERT", "attributes", Map.of("warehouseCode", plain, "warehouseName",
            "s1: not sealed", "active", true)));

        List<RetiredSealScan.Finding> found = scan.undeclared().block();
        assertThat(found).containsExactly(new RetiredSealScan.Finding(CommerceEntities.WAREHOUSE, "warehouseName", 1));
        assertThat(gauge()).isEqualTo(1.0);

        // The startup check: a warning when the application starts, an error in platformCheck.
        assertThat(checks.check()).filteredOn(p -> p.location().equals(CommerceEntities.WAREHOUSE + ".warehouseName"))
            .singleElement().extracting(CheckProblem::severity).isEqualTo(CheckProblem.Severity.WARNING);
        assertThat(KeyRotationChecks.undeclared(found, true)).singleElement()
            .extracting(CheckProblem::severity).isEqualTo(CheckProblem.Severity.ERROR);

        // The job has nothing it can do, so it fails, naming the field, instead of saying the key can go.
        assertThatThrownBy(this::rekey).isInstanceOfSatisfying(BusinessRuleViolationException.class, e ->
            assertThat(e.violations()).extracting(Violation::ruleCode)
                .containsExactly(PlatformErrorCodes.KEY_ROTATION_UNDECLARED_SEALED));

        // Once the value is gone (declared and re-keyed, or replaced as here), the key can be removed.
        Map<String, Object> row = query("SELECT warehouse_id, version_no FROM warehouse_version"
            + " WHERE warehouse_code = ? ORDER BY version_no DESC LIMIT 1", code).getFirst();
        String id = String.valueOf(row.get("warehouse_id"));
        Object version = row.get("version_no");
        commit(Map.of("action", "UPDATE", "id", id, "version", version,
            "attributes", Map.of("warehouseName", "renamed")));
        KeyRotationProcesses.RekeyOutput after = rekey();
        assertThat(after.undeclaredSealed()).isEmpty();
        assertThat(after.retiredKeysRemovable()).isTrue();
        assertThat(gauge()).isZero();
    }
}
