package com.jabiz.audit;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuditDiffTest {

    private static final EntityDefinition VENDOR = EntityDefinition.define("Vendor", eb -> {
        eb.physicalTable("vendor");
        eb.primaryKey("vendorId");
        eb.field("vendorId", f -> f.physicalColumn("vendor_id").asText(20));
        eb.field("name", f -> f.physicalColumn("name").asText(100));
        eb.field("bankAccount", f -> f.physicalColumn("bank_account").asText(40).sensitive());
        eb.field("creditLimit", f -> f.physicalColumn("credit_limit").asMonetary("USD", 2));
        eb.field("since", f -> f.physicalColumn("since").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("labels", f -> f.physicalColumn("labels").asText(400));
        eb.field("contractName", f -> f.physicalColumn("contract_name").asText(255).auditMasked());
    });

    private static Map<String, Object> state(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void anUpdateKeepsOnlyWhatChangedWithSecretsMasked() {
        Map<String, AuditDiff.Change> changes = AuditDiff.of(VENDOR,
            state("vendorId", "V200", "name", "Acme", "bankAccount", "111", "creditLimit", new BigDecimal("100.00")),
            state("vendorId", "V200", "name", "Acme Inc.", "bankAccount", "222", "creditLimit", new BigDecimal("100.00"),
                "unknown", "x"));
        assertThat(changes).containsOnlyKeys("bankAccount", "name");
        assertThat(changes.get("name")).isEqualTo(new AuditDiff.Change("Acme", "Acme Inc."));
        assertThat(changes.get("bankAccount")).isEqualTo(new AuditDiff.Change(AuditDiff.MASK, AuditDiff.MASK));
        assertThat(changes.keySet()).containsExactly("bankAccount", "name");
    }

    @Test
    void auditMaskedValuesAreReadableButKeptOutOfTheTrail() {
        assertThat(VENDOR.fields.get("contractName").auditMasked()).isTrue();
        assertThat(VENDOR.fields.get("contractName").sensitive()).isFalse();
        assertThat(AuditDiff.of(VENDOR, state("contractName", "山田_contract.pdf"),
            state("contractName", "Tanaka_contract.pdf")))
            .containsExactly(Map.entry("contractName", new AuditDiff.Change(AuditDiff.MASK, AuditDiff.MASK)));
        assertThat(AuditDiff.of(VENDOR, state("contractName", "same.pdf"), state("contractName", "same.pdf")))
            .isEmpty();
    }

    @Test
    void insertsHaveNoBeforeAndDeletesNoAfter() {
        Map<String, AuditDiff.Change> inserted = AuditDiff.of(VENDOR, null,
            state("vendorId", "V1", "bankAccount", "9", "since", Instant.parse("2026-01-02T03:04:05Z"), "name", null));
        assertThat(inserted).containsOnlyKeys("bankAccount", "since", "vendorId");
        assertThat(inserted.get("bankAccount")).isEqualTo(new AuditDiff.Change(null, AuditDiff.MASK));
        assertThat(inserted.get("since").after()).isEqualTo("2026-01-02T03:04:05Z");
        Map<String, AuditDiff.Change> deleted = AuditDiff.of(VENDOR, state("vendorId", "V1", "bankAccount", "9"), null);
        assertThat(deleted.get("vendorId")).isEqualTo(new AuditDiff.Change("V1", null));
        assertThat(deleted.get("bankAccount")).isEqualTo(new AuditDiff.Change(AuditDiff.MASK, null));
    }

    @Test
    void valuesAreCanonical() {
        assertThat(AuditDiff.canonical(new BigDecimal("1.50"))).isEqualTo("1.50");
        assertThat(AuditDiff.canonical(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(AuditDiff.canonical(1.5d)).isEqualTo("1.5");
        assertThat(AuditDiff.canonical(2.5f)).isEqualTo("2.5");
        assertThat(AuditDiff.canonical(OffsetDateTime.of(2026, 1, 1, 9, 0, 0, 0, ZoneOffset.ofHours(9))))
            .isEqualTo("2026-01-01T00:00:00Z");
        assertThat(AuditDiff.canonical(3)).isEqualTo(3L);
        assertThat(AuditDiff.canonical((short) 4)).isEqualTo(4L);
        assertThat(AuditDiff.canonical(5L)).isEqualTo(5L);
        assertThat(AuditDiff.canonical(true)).isEqualTo(true);
        assertThat(AuditDiff.canonical(null)).isNull();
        Object map = AuditDiff.canonical(Map.of("b", 1, "a", new BigDecimal("2.0")));
        assertThat(map).isEqualTo(Map.of("a", "2.0", "b", 1L));
        assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) map).keySet())).containsExactly("a", "b");
        assertThat(AuditDiff.canonical(List.of(1, "x"))).isEqualTo(List.of(1L, "x"));
        assertThat(AuditDiff.canonical(new String[] {"a"})).isEqualTo(List.of("a"));
        assertThat(AuditDiff.canonical(Set.of(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))))
            .isEqualTo(List.of("00000000-0000-0000-0000-000000000001"));
        // Differently scaled amounts are different stored values, so they are a change.
        assertThat(AuditDiff.of(VENDOR, state("creditLimit", new BigDecimal("1.5")),
            state("creditLimit", new BigDecimal("1.50")))).containsOnlyKeys("creditLimit");
        assertThat(AuditDiff.of(VENDOR, state("labels", Map.of("en", "a")), state("labels", Map.of("en", "a"))))
            .isEmpty();
    }
}
