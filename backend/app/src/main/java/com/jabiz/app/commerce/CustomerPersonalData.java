package com.jabiz.app.commerce;

import com.jabiz.security.erasure.ErasedEntity;
import com.jabiz.security.erasure.ErasureLoad;
import com.jabiz.security.erasure.ErasureNotice;
import com.jabiz.security.erasure.ErasureSubject;
import com.jabiz.security.erasure.PersonalDataEraser;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * What the demo erases of a portal customer whose account is deleted (docs/design/10-security.md section 17; decision
 * D62 item 7). The customer stays - its orders refer to it and are business records - but its name is overwritten in
 * every version; the platform does the rest (the account, its sign-ins, mail and audit).
 */
@Component
class CustomerPersonalData implements PersonalDataEraser {

    static final String CUSTOMERS = "customers";

    @Override
    public List<ErasedEntity> entities() {
        return List.of(ErasedEntity.keep(CommerceEntities.CUSTOMER, "userId", "customerName"));
    }

    @Override
    public List<ErasureLoad> loads() {
        return List.of(ErasureLoad.of(CUSTOMERS, CommerceEntities.CUSTOMER_DATASET, "userId"));
    }

    /** A customer is told that its orders stay, without its name. */
    @Override
    public List<ErasureNotice> notices(ErasureSubject subject) {
        return subject.rows(CUSTOMERS).isEmpty() ? List.of() : List.of(ErasureNotice.of("commerce.orders-kept"));
    }
}
