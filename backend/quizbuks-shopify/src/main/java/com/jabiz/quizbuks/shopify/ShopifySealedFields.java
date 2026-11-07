package com.jabiz.quizbuks.shopify;

import com.jabiz.security.SealedField;
import com.jabiz.security.SealedFields;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The values QuizBuks for Shopify seals (platform decision D78), so that a new {@code JABIZ_SEAL_KEY} can take over:
 * a discount code ({@link DiscountPool}) is sealed for its shop's address, which the row keeps as {@code shopDomain};
 * a shop's access and refresh tokens ({@link ConnectionProcesses}) are sealed for the same address, which their row
 * keeps as {@code shopDomain} too (V82; empty on rows from before it, which the re-keying counts as unreadable and
 * leaves under the retired key: test databases only). Both tokens are on one row; the platform writes a row's sealed
 * fields in one update (17zz4).
 */
@Component
public class ShopifySealedFields implements SealedFields {

    @Override
    public List<SealedField> fields() {
        return List.of(
            SealedField.of(QbsStorefront.CODE, "sealedCode", "shopDomain"),
            SealedField.of(QbsShops.TOKEN, "sealedAccessToken", "shopDomain"),
            SealedField.of(QbsShops.TOKEN, "sealedRefreshToken", "shopDomain"));
    }
}
