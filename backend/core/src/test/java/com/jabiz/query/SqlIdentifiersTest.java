package com.jabiz.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlIdentifiersTest {

    @ParameterizedTest
    @ValueSource(strings = {"t_item", "_x", "F_WB$SN", "public.t_item"})
    void acceptsPlainAndQualifiedIdentifiers(String identifier) {
        assertThat(SqlIdentifiers.require(identifier)).isEqualTo(identifier);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsBlank(String identifier) {
        assertThatThrownBy(() -> SqlIdentifiers.require(identifier))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be blank");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1abc", "a b", "a;drop", "\"quoted\"", "a.", ".a", "a..b", "a-b", "x.y.z w"})
    void rejectsAnythingElse(String identifier) {
        assertThatThrownBy(() -> SqlIdentifiers.require(identifier))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Illegal SQL identifier");
    }
}
