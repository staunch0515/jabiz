package com.jabiz.quizbuks.shopify;

import com.jabiz.security.SealedField;
import com.jabiz.security.SealedFields;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The values QuizBuks for Shopify seals (platform decision D78), so that a new {@code JABIZ_SEAL_KEY} can take over:
 * a discount code is sealed for its shop's address ({@link DiscountPool}), which the row keeps as {@code shopDomain}.
 */
@Component
public class ShopifySealedFields implements SealedFields {

    @Override
    public List<SealedField> fields() {
        return List.of(SealedField.of(QbsStorefront.CODE, "sealedCode", "shopDomain"));
    }
}
