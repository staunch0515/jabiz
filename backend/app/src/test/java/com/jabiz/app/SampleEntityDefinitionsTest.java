package com.jabiz.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SampleEntityDefinitionsTest {

    @Test
    void sampleEntityDefinitionsAreValid() {
        assertThat(WaybillEntityDefinitions.WAYBILL.initialStates).containsExactly("CREATED");
        assertThat(CustomsDeclarationEntityDefinitions.CUSTOMS_DECLARATION.references).hasSize(1);
        assertThat(PriceEntityDefinitions.PRICE.temporal).isTrue();
        assertThat(PriceEntityDefinitions.PRICE.versionField).isEqualTo("versionNo");
    }
}
