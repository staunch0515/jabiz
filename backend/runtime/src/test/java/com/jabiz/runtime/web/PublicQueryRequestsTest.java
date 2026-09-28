package com.jabiz.runtime.web;

import com.jabiz.entity.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The query-string forms of the public template API (docs/design/15-public-access.md section 5). */
class PublicQueryRequestsTest {

    @Test
    void filtersAreFieldOpValue() {
        assertThat(PublicQueryController.filter("sku:eq:A-1"))
            .isEqualTo(new ListRequests.Filter("sku", "eq", "A-1", null, null, null));
        // The value may itself contain colons.
        assertThat(PublicQueryController.filter("time:gte:2026-01-01T00:00:00Z").value())
            .isEqualTo("2026-01-01T00:00:00Z");
        assertThat(PublicQueryController.filter("sku:in:A,B").values()).isEqualTo(List.of("A", "B"));
        ListRequests.Filter between = PublicQueryController.filter("price:between:10,20");
        assertThat(between.from()).isEqualTo("10");
        assertThat(between.to()).isEqualTo("20");
        assertThat(PublicQueryController.filter("note:isnull").op()).isEqualTo("isnull");
    }

    @Test
    void malformedFiltersAreRejected() {
        for (String bad : List.of("sku", ":eq:x", "sku::x", "sku:eq", "sku:in:", "price:between:10",
            "price:between:1,2,3")) {
            assertThatThrownBy(() -> PublicQueryController.filter(bad)).as(bad)
                .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void sortsAreFieldAndDirection() {
        assertThat(PublicQueryController.sort("sku")).isEqualTo(new ListRequests.Sort("sku", true));
        assertThat(PublicQueryController.sort("sku:asc")).isEqualTo(new ListRequests.Sort("sku", true));
        assertThat(PublicQueryController.sort("sku:desc")).isEqualTo(new ListRequests.Sort("sku", false));
        for (String bad : List.of("sku:up", ":asc", "sku:asc:x")) {
            assertThatThrownBy(() -> PublicQueryController.sort(bad)).as(bad)
                .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void ifNoneMatchComparesWeakly() {
        String tag = "\"abc\"";
        assertThat(PublicQueryController.matches(List.of("\"abc\""), tag)).isTrue();
        assertThat(PublicQueryController.matches(List.of("W/\"abc\""), tag)).isTrue();
        assertThat(PublicQueryController.matches(List.of("\"x\"", " \"abc\" "), tag)).isTrue();
        assertThat(PublicQueryController.matches(List.of("*"), tag)).isTrue();
        assertThat(PublicQueryController.matches(List.of("\"abcd\""), tag)).isFalse();
        assertThat(PublicQueryController.matches(List.of(), tag)).isFalse();
    }
}
