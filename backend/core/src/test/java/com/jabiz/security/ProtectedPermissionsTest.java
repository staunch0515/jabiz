package com.jabiz.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectedPermissionsTest {

    @Test
    void theDefaultCoversWhatChangesRightsDecidesTheFourEyesOrWritesTheBooks() {
        ProtectedPermissions protectedSet = ProtectedPermissions.of(List.of());
        assertThat(protectedSet.permissions()).containsExactlyInAnyOrderElementsOf(ProtectedPermissions.DEFAULT);
        assertThat(protectedSet.isProtected("security.user-role.write")).isTrue();
        assertThat(protectedSet.isProtected("ledger.read")).isFalse();
        assertThat(protectedSet.isProtected(null)).isFalse();
        assertThat(ProtectedPermissions.of(null).permissions()).isEqualTo(protectedSet.permissions());
    }

    @Test
    void aConfiguredListReplacesTheDefaultButAlwaysKeepsTheWildcard() {
        ProtectedPermissions configured = ProtectedPermissions.of(List.of(" fin.pay ", "", "approval.decide"));
        assertThat(configured.permissions()).containsExactly("*", "approval.decide", "fin.pay");
        assertThat(configured.isProtected("ledger.post")).isFalse();
    }

    @Test
    void gainedIsWhatIsProtectedAndNotHeldYet() {
        ProtectedPermissions set = ProtectedPermissions.of(List.of());
        assertThat(set.among(List.of("ledger.read", "ledger.post", "*"))).containsExactly("*", "ledger.post");
        assertThat(set.gained(List.of("ledger.post", "ledger.read"), List.of())).containsExactly("ledger.post");
        assertThat(set.gained(List.of("ledger.post"), List.of("ledger.post"))).isEmpty();
        assertThat(set.gained(List.of("ledger.post", "*"), List.of("*"))).isEmpty();
        assertThat(set.gained(List.of("*"), Set.of("ledger.post"))).containsExactly("*");
    }

    @Test
    void codesAreChecked() {
        assertThat(ProtectedPermissions.problems(List.of("ok.code", "*", "security.*", "bad code", " "))).hasSize(2);
        assertThat(ProtectedPermissions.problems(null)).isEmpty();
    }
}
