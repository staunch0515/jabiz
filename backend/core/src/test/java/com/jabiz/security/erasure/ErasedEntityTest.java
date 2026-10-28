package com.jabiz.security.erasure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErasedEntityTest {

    @Test
    void declaresWhatBecomesOfTheRowsAndWhichFieldsArePersonal() {
        ErasedEntity profile = ErasedEntity.delete("Profile", "userId", "displayName", "about");
        assertThat(profile.action()).isEqualTo(ErasedEntity.RowAction.DELETE);
        assertThat(profile.fields()).containsExactly("displayName", "about");
        ErasedEntity payouts = ErasedEntity.clear("Payout", "userId", "accountName");
        assertThat(payouts.action()).isEqualTo(ErasedEntity.RowAction.CLEAR);
        ErasedEntity receipts = ErasedEntity.keep("Receipt", "userId", "buyerName").documents("receipt", "invoice");
        assertThat(receipts.documents()).containsExactly("receipt", "invoice");
        assertThat(receipts.fields()).containsExactly("buyerName");
        // A row deleted without personal fields of its own: the tombstone is enough, nothing to overwrite.
        assertThat(ErasedEntity.delete("Follow", "userId").fields()).isEmpty();
    }

    @Test
    void refusesDeclarationsThatCannotBeCarriedOut() {
        assertThatThrownBy(() -> ErasedEntity.clear("Payout", "userId"))
            .hasMessageContaining("CLEAR names no field");
        assertThatThrownBy(() -> ErasedEntity.keep("Ledger", "userId"))
            .hasMessageContaining("KEEP names no field");
        assertThatThrownBy(() -> ErasedEntity.delete("Profile", "userId", "about", "about"))
            .hasMessageContaining("named twice");
        // The user field finds the rows; the user's id stays (D62 item 4).
        assertThatThrownBy(() -> ErasedEntity.clear("Profile", "userId", "userId"))
            .hasMessageContaining("user field userId is kept");
        assertThatThrownBy(() -> ErasedEntity.delete("1bad", "userId")).hasMessageContaining("Invalid entity");
        assertThatThrownBy(() -> ErasedEntity.delete("Profile", "")).hasMessageContaining("Invalid user field");
        assertThatThrownBy(() -> ErasedEntity.keep("Receipt", "userId", "x").documents("bad layout"))
            .hasMessageContaining("Invalid document layout");
    }

    @Test
    void noticesAndLoadsAreNamedSafely() {
        assertThat(ErasureNotice.of("balance", Map.of("amount", 120)).params()).containsEntry("amount", 120);
        assertThatThrownBy(() -> ErasureNotice.of("bad code")).hasMessageContaining("Invalid notice code");
        assertThat(ErasureLoad.of("wallet", "urn:jabiz:dataset:default:Wallet", "userId").name()).isEqualTo("wallet");
        assertThatThrownBy(() -> ErasureLoad.of("1x", "d", "u")).hasMessageContaining("Invalid load name");
        assertThatThrownBy(() -> ErasureLoad.of("x", " ", "u")).hasMessageContaining("names no dataset");
        assertThatThrownBy(() -> ErasureLoad.of("x", "d", "")).hasMessageContaining("names no user field");
        ErasureSubject subject = new ErasureSubject("u1", Map.of("wallet", List.of(Map.of("balance", 5))));
        assertThat(subject.rows("wallet")).hasSize(1);
        assertThat(subject.rows("other")).isEmpty();
    }

    @Test
    void anEraserDeclaresNoNoticesByDefault() {
        PersonalDataEraser eraser = () -> List.of(ErasedEntity.delete("Profile", "userId", "about"));
        assertThat(eraser.loads()).isEmpty();
        assertThat(eraser.notices(new ErasureSubject("u1", Map.of()))).isEmpty();
    }
}
