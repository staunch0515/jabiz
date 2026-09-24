package com.jabiz.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceIdTest {

    @Test
    void parsesAllSegments() {
        ResourceId id = ResourceId.parse("urn:jabiz:entity:WaybillTracking:WB-1001");

        assertThat(id.namespace()).isEqualTo("jabiz");
        assertThat(id.kind()).isEqualTo("entity");
        assertThat(id.type()).isEqualTo("WaybillTracking");
        assertThat(id.id()).isEqualTo("WB-1001");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "urn:jabiz:entity:WaybillTracking:WB-1001",
        "urn:jabiz:process:SPONSOR_SIGN_IN:1",
        "urn:my-ns:kind-2:a.b_c-d:x.y_z-9"
    })
    void roundTripsThroughToString(String urn) {
        assertThat(ResourceId.parse(urn).toString()).isEqualTo(urn);
        assertThat(ResourceId.parse(urn)).isEqualTo(ResourceId.parse(urn));
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> ResourceId.parse(null))
            .isInstanceOf(InvalidResourceIdException.class)
            .hasMessageContaining("must not be null");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "urn:jabiz:entity:Waybill",
        "urn:jabiz:entity:Waybill:1:extra",
        "uri:jabiz:entity:Waybill:1",
        "URN:jabiz:entity:Waybill:1"
    })
    void rejectsMalformedStructure(String urn) {
        assertThatThrownBy(() -> ResourceId.parse(urn))
            .isInstanceOf(InvalidResourceIdException.class)
            .hasMessageContaining("Malformed resource identifier");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "urn:Jabiz:entity:Waybill:1",      // namespace must be lower case
        "urn:jabiz:Entity:Waybill:1",      // kind must be lower case
        "urn:ja_biz:entity:Waybill:1",     // underscore not allowed in namespace
        "urn:jabiz:entity:Way bill:1",     // space in type
        "urn:jabiz:entity:Waybill:1/2",    // slash in id
        "urn::entity:Waybill:1",           // empty namespace
        "urn:jabiz:entity:Waybill:"        // empty id
    })
    void rejectsIllegalSegments(String urn) {
        assertThatThrownBy(() -> ResourceId.parse(urn))
            .isInstanceOf(InvalidResourceIdException.class)
            .hasMessageContaining("Invalid resource identifier segment");
    }

    @Test
    void constructorValidatesToo() {
        assertThatThrownBy(() -> new ResourceId("jabiz", "entity", null, "1"))
            .isInstanceOf(InvalidResourceIdException.class)
            .hasMessageContaining("'type'");
        assertThat(new ResourceId("jabiz", "entity", "Todo", "42").toString())
            .isEqualTo("urn:jabiz:entity:Todo:42");
    }
}
