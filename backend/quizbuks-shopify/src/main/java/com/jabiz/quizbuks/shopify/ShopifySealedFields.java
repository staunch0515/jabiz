package com.jabiz.quizbuks.shopify;

import com.jabiz.security.SealedField;
import com.jabiz.security.SealedFields;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The values QuizBuks for Shopify seals (platform decision D78), so that a new {@code JABIZ_SEAL_KEY} can take over:
 * a discount code ({@link DiscountPool}) is sealed for its shop's address, which the row keeps as {@code shopDomain}.
 * A shop's access and refresh tokens ({@link ConnectionProcesses}) are sealed for the same address, which their row
 * keeps as {@code shopDomain} too (V81; empty on rows from before it, which the re-keying would count as unreadable
 * and leave under the retired key: test databases only), but are not declared yet: the platform's re-keying writes each declared field
 * of a row as a separate update of the same version, so two sealed fields on one row fail the whole batch with an
 * optimistic lock conflict (docs/quizbuks/plans/impl/F-F14-sealed-fields.md, 待决定). Declare them, as
 * {@code SealedField.of(QbsShops.TOKEN, "sealedAccessToken" / "sealedRefreshToken", "shopDomain")}, once it writes a
 * row's fields in one update.
 */
@Component
public class ShopifySealedFields implements SealedFields {

    @Override
    public List<SealedField> fields() {
        return List.of(SealedField.of(QbsStorefront.CODE, "sealedCode", "shopDomain"));
    }
}
