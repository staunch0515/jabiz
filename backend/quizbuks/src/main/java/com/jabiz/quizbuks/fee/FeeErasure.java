package com.jabiz.quizbuks.fee;

import com.jabiz.quizbuks.operator.OperatorTransfers;
import com.jabiz.security.erasure.ErasedEntity;
import com.jabiz.security.erasure.PersonalDataEraser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * F-M15 a0 (docs/quizbuks/plans/F-M15.md §4.2): the money's records that keep free text someone wrote. The records stay
 * (they are kept for years, as the ledger is); the purge overwrites only the text (a column that may be empty becomes
 * empty, a required one {@code ***}). Tables of ids, amounts and times only are not declared. A top-up's texts and a
 * credit note's are declared with the Sponsor's other records ({@code SponsorErasure}: one declaration per entity and
 * user field).
 */
@Configuration
public class FeeErasure {

    @Bean
    PersonalDataEraser qbFeePersonalData() {
        return () -> List.of(
            // Why finance charged a fee by hand, voided an invoice or asked for either (it may name a person).
            ErasedEntity.keep(QbFees.CHARGE, "sponsorId", "reason"),
            ErasedEntity.keep(QbFees.INVOICE, "sponsorId", "voidReason"),
            ErasedEntity.keep(QbFees.REQUEST, "sponsorId", "reason"),
            // The lead's note on a transfer to the operator's direct Sponsor: erased with either of them.
            ErasedEntity.keep(OperatorTransfers.TRANSFER_ENTITY_NAME, "requestedBy", "note"),
            ErasedEntity.keep(OperatorTransfers.TRANSFER_ENTITY_NAME, "sponsorId", "note"));
    }
}
