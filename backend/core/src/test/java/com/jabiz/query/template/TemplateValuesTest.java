package com.jabiz.query.template;

import com.jabiz.entity.SemanticKind;
import com.jabiz.query.custom.QueryParameter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.jabiz.query.template.TemplateFixtures.ENTITIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateValuesTest {

    private static final QueryParameter PRICE_IDS = QueryParameter.like("ids", "Price", "priceId", false, true)
        .withKind(new SemanticKind.SemanticIdentity("urn:test:price"));

    @Test
    void identifiersOfTemporalEntitiesAreUuids() {
        UUID id = UUID.randomUUID();

        assertThat(TemplateValues.bindingType(PRICE_IDS, ENTITIES)).isEqualTo(UUID.class);
        assertThat((UUID[]) TemplateValues.bindable(PRICE_IDS, List.of(id.toString()), ENTITIES)).containsExactly(id);
        QueryParameter ref = QueryParameter.of("r", new SemanticKind.Reference("Price"), false);
        assertThat(TemplateValues.bindable(ref, id.toString(), ENTITIES)).isEqualTo(id);
        assertThatThrownBy(() -> TemplateValues.bindable(PRICE_IDS, List.of("nope"), ENTITIES))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherIdentifiersAreText() {
        QueryParameter orders = QueryParameter.like("o", "Order", "orderId", false, true)
            .withKind(new SemanticKind.SemanticIdentity("urn:test:order"));

        assertThat(TemplateValues.bindingType(orders, ENTITIES)).isEqualTo(String.class);
        assertThat((String[]) TemplateValues.bindable(orders, List.of("A", 7), ENTITIES)).containsExactly("A", "7");
        QueryParameter unknownRef = QueryParameter.of("r", new SemanticKind.Reference("Ghost"), false);
        assertThat(TemplateValues.bindable(unknownRef, 5, ENTITIES)).isEqualTo("5");
    }

    @Test
    void valuesAreConvertedToTheirKind() {
        QueryParameter amounts = QueryParameter.listOf("a", new SemanticKind.Monetary("JPY", 0), false);
        QueryParameter code = QueryParameter.of("c", new SemanticKind.Code("urn:d", List.of("A")), false);

        assertThat((BigDecimal[]) TemplateValues.bindable(amounts, List.of("1", 2), ENTITIES))
            .containsExactly(new BigDecimal("1"), new BigDecimal("2"));
        assertThat((BigDecimal[]) TemplateValues.bindable(amounts, List.of(), ENTITIES)).isEmpty();
        assertThatThrownBy(() -> TemplateValues.bindable(code, "B", ENTITIES)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsAndSingleValuesAreNotInterchangeable() {
        QueryParameter single = QueryParameter.of("s", new SemanticKind.Text(null, false), false);
        QueryParameter list = QueryParameter.listOf("l", new SemanticKind.Text(null, false), false);

        assertThatThrownBy(() -> TemplateValues.bindable(single, List.of("a"), ENTITIES))
            .hasMessageContaining("not a list");
        assertThatThrownBy(() -> TemplateValues.bindable(list, "a", ENTITIES)).hasMessageContaining("a list is expected");
        assertThatThrownBy(() -> TemplateValues.bindable(list, java.util.Arrays.asList("a", null), ENTITIES))
            .hasMessageContaining("null");
    }
}
