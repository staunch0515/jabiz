package com.jabiz.app.it.security;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.context.RequestContext;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.security.rotation.KeyRotationProcesses;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.SealedField;
import com.jabiz.security.SealedFields;
import com.jabiz.security.SealedSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The re-keying writes no more values of an entity in one run than its dataset accepts in one commit (decision D78;
 * ROADMAP 17zz4). The warehouse dataset takes at most 100 changes a commit (the default), the job's batch is 500: with
 * 101 values under the retired key, a run stores 100 and says there are more, the next stores the last one. Before,
 * the run failed (BATCH_TOO_LARGE, "Batch size [101] exceeds dataset limit [100]") and the rotation never ended.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.seal.key=bmV4dC1zZWFsLWtleS1vZi10aGUta2V5LXJvdGF0aW9uLXRlc3RzLTAwMDAy",
    "jabiz.security.seal.previous-keys=" + KeyRotationIT.OLD_SEAL})
class KeyRotationBatchIT extends SecurityItSupport {

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final SealedSecrets OLD_SEALER =
        new SealedSecrets(Base64.getDecoder().decode(KeyRotationIT.OLD_SEAL));

    static final String SMALL = "ItSmallBatchCard";
    static final String PAIR = "ItSealedPair";

    @TestConfiguration
    static class Sealed extends com.jabiz.entity.BaseEntityDefinitions {
        @Bean
        SealedFields itSealedFields() {
            return () -> List.of(SealedField.of(CommerceEntities.WAREHOUSE, "warehouseName", "warehouseCode"),
                SealedField.of(PAIR, "sealedAccess", "pairId"), SealedField.of(PAIR, "sealedRefresh", "pairId"));
        }

        /** Two sealed values in one row of an ordinary table (test table it_sealed_pair). */
        @Bean
        com.jabiz.entity.EntityDefinition itSealedPair() {
            return com.jabiz.entity.EntityDefinition.define(PAIR, eb -> {
                eb.physicalTable("it_sealed_pair");
                eb.primaryKey("pairId");
                eb.field("pairId", f -> f.physicalColumn("pair_id").immutable(true).required(true).asText(36));
                eb.field("sealedAccess", f -> f.physicalColumn("sealed_access").required(true).asText(4000).sensitive());
                eb.field("sealedRefresh", f -> f.physicalColumn("sealed_refresh").required(true).asText(4000)
                    .sensitive());
                eb.field("rowVersion", rowVersion("f_version"));
            });
        }

        @Bean
        com.jabiz.dataset.DatasetDefinition itSealedPairs(
            @org.springframework.beans.factory.annotation.Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return com.jabiz.dataset.DatasetDefinition.define("urn:jabiz:dataset:default:" + PAIR, d -> d
                .targetEntityType(PAIR).asDefault().permissions("it.pair.read", "it.pair.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }

        /** An entity whose dataset takes 2 changes per commit (test table it_product_card). */
        @Bean
        com.jabiz.entity.EntityDefinition itSmallBatchCard() {
            return com.jabiz.entity.EntityDefinition.define(SMALL, eb -> {
                eb.physicalTable("it_product_card");
                eb.primaryKey("productId");
                eb.field("productId", f -> f.physicalColumn("product_id").immutable(true).required(true)
                    .asReference(CommerceEntities.PRODUCT));
                eb.field("cardSecret", f -> f.physicalColumn("title").required(true).asText(100).sensitive());
                eb.field("rowVersion", rowVersion("f_version"));
            });
        }

        @Bean
        com.jabiz.dataset.DatasetDefinition itSmallBatchCards(
            @org.springframework.beans.factory.annotation.Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return com.jabiz.dataset.DatasetDefinition.define("urn:jabiz:dataset:default:" + SMALL, d -> d
                .targetEntityType(SMALL).asDefault().permissions("it.card.read", "it.card.write")
                .policy(p -> p.maxWriteBatchSize(2)).storage(s -> s.connectionPoolRef(pool)));
        }
    }

    @Autowired
    com.jabiz.runtime.security.rotation.KeyRotationChecks checks;

    private static String sealed(String secret, String context) {
        byte[] iv = new byte[SealedSecrets.IV_BYTES];
        RANDOM.nextBytes(iv);
        return OLD_SEALER.seal(secret.getBytes(StandardCharsets.UTF_8), context, iv);
    }

    /**
     * Two declared sealed fields in one row are stored again in one update of the row (ROADMAP 17zz4). Before, each
     * field was its own update with the version both had read: the second failed (409, "expected version [1], stored
     * version [2]") and the whole run was rolled back, so nothing was ever stored again.
     */
    @Test
    void twoSealedValuesOfOneRowAreStoredAgainTogether() {
        String id = UUID.randomUUID().toString();
        // Test set-up only: the row as a process would have written it under the retired key.
        execute("INSERT INTO it_sealed_pair (pair_id, sealed_access, sealed_refresh) VALUES (?, ?, ?)", id,
            sealed("access token", id), sealed("refresh token", id));

        KeyRotationProcesses.RekeyOutput out = rekey();
        assertThat(out.sealedValues()).isGreaterThanOrEqualTo(2);
        Map<String, Object> row = query("SELECT sealed_access, sealed_refresh, f_version FROM it_sealed_pair"
            + " WHERE pair_id = ?", id).getFirst();
        assertThat(sealer.open(String.valueOf(row.get("sealed_access")), id)).isEqualTo("access token");
        assertThat(sealer.open(String.valueOf(row.get("sealed_refresh")), id)).isEqualTo("refresh token");
        assertThat(((Number) row.get("f_version")).longValue()).as("one update of the row").isEqualTo(2);
    }

    @Test
    void aSealedFieldWhoseDatasetTakesFewChangesPerCommitIsReported() {
        assertThat(checks.declarations(List.of(SealedField.of(SMALL, "cardSecret", "productId"))))
            .singleElement().satisfies(problem -> {
                assertThat(problem.severity()).isEqualTo(com.jabiz.runtime.check.CheckProblem.Severity.WARNING);
                assertThat(problem.message()).contains("takes 2 change(s) per commit");
            });
        // The demo's warehouses (100 per commit) are not.
        assertThat(checks.declarations(List.of(SealedField.of(CommerceEntities.WAREHOUSE, "warehouseName",
            "warehouseCode")))).noneMatch(problem -> problem.message().contains("per commit"));
    }

    @Autowired
    ProcessExecutor processes;

    @Autowired
    SecretSealer sealer;

    private KeyRotationProcesses.RekeyOutput rekey() {
        return processes.run(KeyRotationProcesses.REKEY, new KeyRotationProcesses.RekeyInput(null),
                ExecutionOptions.NONE)
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH, "it-rekey")))
            .block().output();
    }

    /** Warehouses whose names are sealed under the retired key, in commits the dataset accepts. */
    private List<String> warehouses(int count) {
        List<String> codes = new ArrayList<>();
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            codes.add(code);
            byte[] iv = new byte[SealedSecrets.IV_BYTES];
            RANDOM.nextBytes(iv);
            String name = OLD_SEALER.seal(("password " + i).getBytes(StandardCharsets.UTF_8), code, iv);
            changes.add(Map.of("action", "INSERT", "attributes", Map.of("warehouseCode", code, "warehouseName", name,
                "active", true)));
            if (changes.size() == 50 || i == count - 1) {
                post("/api/datasets/" + CommerceEntities.WAREHOUSE_DATASET + "/commit", bearer("*"),
                    Map.of("changes", List.copyOf(changes))).expectStatus().isOk();
                changes.clear();
            }
        }
        return codes;
    }

    private long underTheRetiredKey(List<String> codes) {
        String prefix = SealedSecrets.storedPrefix(OLD_SEALER.keyId());
        return codes.stream().filter(code -> String.valueOf(query("SELECT warehouse_name FROM warehouse_version"
            + " WHERE warehouse_code = ? ORDER BY version_no DESC LIMIT 1", code).getFirst().get("warehouse_name"))
            .startsWith(prefix)).count();
    }

    @Test
    void aRunStoresNoMoreValuesOfAnEntityThanItsDatasetTakesInOneCommit() {
        List<String> codes = warehouses(101);

        KeyRotationProcesses.RekeyOutput first = rekey();
        assertThat(first.sealedValues()).isEqualTo(100);
        assertThat(first.more()).isTrue();
        assertThat(first.retiredKeysRemovable()).isFalse();
        assertThat(underTheRetiredKey(codes)).isEqualTo(1);

        KeyRotationProcesses.RekeyOutput second = rekey();
        assertThat(second.sealedValues()).isEqualTo(1);
        assertThat(second.more()).isFalse();
        assertThat(underTheRetiredKey(codes)).isZero();
        assertThat(sealer.open(String.valueOf(query("SELECT warehouse_name FROM warehouse_version"
            + " WHERE warehouse_code = ? ORDER BY version_no DESC LIMIT 1", codes.getLast()).getFirst()
            .get("warehouse_name")), codes.getLast())).isEqualTo("password 100");

        assertThat(rekey().retiredKeysRemovable()).isTrue();
    }
}
