package com.jabiz.app.commerce;

import com.jabiz.security.EntryScopeResolver;
import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInLoad;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The warehouse entry's scope (decision D64; docs/design/10-security.md section 13.4): a session works on the
 * warehouse its user works in ({@code WarehouseStaff}), worked out at sign-in and every refresh. A user of no warehouse
 * does not get in. The stock levels' dataset limits such a session to that warehouse; STOCK_RECEIVE checks the
 * warehouse it receives into.
 */
@Component
class WarehouseScope implements EntryScopeResolver {

    /** The sign-in entry of the warehouse staff (application configuration {@code jabiz.security.entries.warehouse}). */
    static final String ENTRY = "warehouse";
    static final String STAFF = "warehouseStaff";

    @Override
    public String entry() {
        return ENTRY;
    }

    @Override
    public String scope() {
        return CommerceEntities.WAREHOUSE_SCOPE;
    }

    @Override
    public List<SignInLoad> loads() {
        return List.of(SignInLoad.of(STAFF, CommerceEntities.WAREHOUSE_STAFF_DATASET, "userId"));
    }

    @Override
    public String resolve(SignInAttempt attempt) {
        List<Map<String, Object>> rows = attempt.rows(STAFF);
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst().get("warehouseId"));
    }
}
