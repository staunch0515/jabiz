package com.jabiz.runtime.erasure;

import com.jabiz.runtime.EntityInstance;
import com.jabiz.security.erasure.ErasedEntity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code ACCOUNT_ERASE} writes of the rows the erasers found (decision D62 item 8): an entity may be declared
 * under several user fields, and a row found by more than one is written once. The rows themselves are
 * {@code ErasureIT}'s.
 */
class AccountErasureTest {

    private static EntityInstance row(String type, String id) {
        return new EntityInstance(id, type, 3, null, Map.of());
    }

    @Test
    void writesARowFoundByTwoDeclarationsOnce() {
        EntityInstance both = row("Invitation", "i-1");
        EntityInstance mine = row("Invitation", "i-2");
        List<AccountErasure.RowWrite> writes = AccountErasure.writes(List.of(
            Map.entry(ErasedEntity.delete("Invitation", "publisherId", "email"), both),
            Map.entry(ErasedEntity.clear("Invitation", "userId", "note"), both),
            Map.entry(ErasedEntity.clear("Invitation", "userId", "note"), mine)));
        // Deleted when one declaration deletes it; the other's row cleared.
        assertThat(writes).extracting(w -> w.row().id(), AccountErasure.RowWrite::delete)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("i-1", true),
                org.assertj.core.groups.Tuple.tuple("i-2", false));
        Map<String, Object> cleared = new HashMap<>();
        cleared.put("note", null);
        assertThat(writes.get(1).cleared()).isEqualTo(cleared);
    }

    @Test
    void clearsTheFieldsOfEveryDeclarationThatFoundTheRow() {
        EntityInstance staff = row("Staff", "s-1");
        List<AccountErasure.RowWrite> writes = AccountErasure.writes(List.of(
            Map.entry(ErasedEntity.clear("Staff", "sponsorId", "nickname"), staff),
            Map.entry(ErasedEntity.clear("Staff", "userId", "note", "nickname"), staff),
            Map.entry(ErasedEntity.clear("Other", "userId", "note"), row("Other", "s-1"))));
        assertThat(writes).hasSize(2);
        assertThat(writes.getFirst().delete()).isFalse();
        assertThat(writes.getFirst().cleared()).containsOnlyKeys("nickname", "note");
        assertThat(writes.getFirst().row().version()).isEqualTo(3);
        // The same key of another entity is another row.
        assertThat(writes.get(1).row().entityType()).isEqualTo("Other");
    }
}
