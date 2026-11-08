package com.jabiz.app.commerce;

import com.jabiz.security.GuardParam;
import com.jabiz.security.SignInLoad;
import com.jabiz.security.SignUpAttempt;
import com.jabiz.security.SignUpDecision;
import com.jabiz.security.SignUpGuard;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A switch that closes the customer portal to new customers (docs/design/10-security.md section 15.17; decision D79):
 * while the business parameter {@value #CLOSED} is {@code true}, no account gets the portal's role, on any path. Unset
 * (the default) or {@code false}, the portal is open. The parameter is read as in effect at the attempt's time, so a
 * closing planned for a date takes effect then by itself.
 *
 * <p>Decision D80 (section 15.18): the portal may also be opened for one product's launch. While the parameter
 * {@value #LAUNCH_PRODUCT} names a product's SKU, registrations are taken only as long as that product exists and is
 * active: the guard reads the product's row (a global load, the SKU from the parameter), not the user's.
 */
@Component
class PortalSignUpSwitch implements SignUpGuard {

    /** The parameter (bool): whether the portal takes no new customers. */
    static final String CLOSED = "commerce.portal.sign-up-closed";

    /** The reason the caller reads ({@code SIGN_UP_REFUSED.reason.commerce.sign-up-closed}, messages_*.properties). */
    static final String REASON = "commerce.sign-up-closed";

    /** The parameter (text, a SKU): the product whose launch the portal's registration is open for (D80). */
    static final String LAUNCH_PRODUCT = "commerce.portal.launch-product";

    /** The load of that product's row. */
    static final String LAUNCH = "launchProduct";

    /** The reason while the launch product is missing or inactive. */
    static final String LAUNCH_OVER = "commerce.launch-over";

    @Override
    public List<GuardParam> params() {
        // May be unset: an application that never sets them keeps the portal open.
        return List.of(GuardParam.optional(CLOSED), GuardParam.optional(LAUNCH_PRODUCT));
    }

    @Override
    public List<SignInLoad> loads() {
        // Not the user's rows: the product the parameter names, by its SKU (unique, so indexed). Unset, nothing is read.
        return List.of(SignInLoad.global(LAUNCH, CommerceEntities.PRODUCT_DATASET).whereParam("sku", LAUNCH_PRODUCT));
    }

    @Override
    public SignUpDecision check(SignUpAttempt attempt) {
        if (!PortalSignInGuard.PORTAL.equals(attempt.entry())) {
            return SignUpDecision.ALLOW;
        }
        if (attempt.params().find(CLOSED, Boolean.class).orElse(false)) {
            return SignUpDecision.refuse(REASON);
        }
        if (attempt.params().has(LAUNCH_PRODUCT)
            && attempt.rows(LAUNCH).stream().noneMatch(product -> Boolean.TRUE.equals(product.get("active")))) {
            return SignUpDecision.refuse(LAUNCH_OVER);
        }
        return SignUpDecision.ALLOW;
    }
}
