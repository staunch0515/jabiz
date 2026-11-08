package com.jabiz.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards' loads: the user's rows as before, and decision D80's conditions on parameters and global loads. */
class SignInLoadTest {

    @Test
    void aLoadOfTheUsersRowsIsAsBefore() {
        SignInLoad load = SignInLoad.of("customers", "urn:jabiz:dataset:default:Customer", "userId");
        assertThat(load.global()).isFalse();
        assertThat(load.conditions()).isEmpty();
        assertThat(load.paramKeys()).isEmpty();
        assertThat(load).isEqualTo(new SignInLoad("customers", "urn:jabiz:dataset:default:Customer", "userId"));
        assertThatThrownBy(() -> SignInLoad.of("customers", "d", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignInLoad.of("customers", "d", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignInLoad.of("1bad", "d", "userId")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignInLoad.of("ok", " ", "userId")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conditionsTakeTheirValuesFromParameters() {
        SignInLoad node = SignInLoad.global("node", "urn:jabiz:dataset:default:Node")
            .whereParam("mapId", "app.gate", value -> ((String) value).split("/")[0])
            .whereParam("nodeId", "app.gate", value -> ((String) value).split("/")[1]);
        assertThat(node.global()).isTrue();
        assertThat(node.conditions()).extracting(SignInLoad.Condition::field).containsExactly("mapId", "nodeId");
        assertThat(node.paramKeys()).containsExactly("app.gate");
        assertThat(node.conditions().get(0).valueOf("m1/n7")).isEqualTo("m1");
        assertThat(node.conditions().get(1).valueOf("m1/n7")).isEqualTo("n7");
        // No value, no comparison: the platform reads nothing then.
        assertThat(node.conditions().get(0).valueOf(null)).isNull();

        SignInLoad mine = SignInLoad.of("lit", "urn:jabiz:dataset:default:Lit", "userId").whereParam("nodeId", "app.node");
        assertThat(mine.global()).isFalse();
        assertThat(mine.conditions().getFirst().valueOf("n7")).isEqualTo("n7");
        // Adding a condition leaves the load it was added to as it was.
        SignInLoad plain = SignInLoad.of("lit", "urn:jabiz:dataset:default:Lit", "userId");
        plain.whereParam("nodeId", "app.node");
        assertThat(plain.conditions()).isEmpty();
        assertThatThrownBy(() -> mine.conditions().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plain.whereParam(" ", "app.node")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plain.whereParam("nodeId", null)).isInstanceOf(NullPointerException.class);
        assertThat(new SignInLoad("x", "d", null, null).conditions()).isEqualTo(List.of());
    }
}
