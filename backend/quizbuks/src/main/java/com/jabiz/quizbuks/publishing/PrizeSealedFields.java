package com.jabiz.quizbuks.publishing;

import com.jabiz.quizbuks.reward.QbDigitalPrizes;
import com.jabiz.quizbuks.reward.QbRewards;
import com.jabiz.security.SealedField;
import com.jabiz.security.SealedFields;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The digital prizes' sealed fields (platform decision D78): so that a new {@code JABIZ_SEAL_KEY} can take over, the
 * platform stores each again under the current key, for the context the process that sealed it used:
 * <ul>
 *   <li>a shared prize's code or link, on the publication and its card: sealed by {@code QB_PUBLICATION_SAVE} for the
 *       caller, who is always the publisher (the sponsor's own dataset; a digital product, which has a fulfiller,
 *       cannot be shared, Q89b-2);</li>
 *   <li>a winner's prize and a pool's code: sealed for their handler ({@link QbRewards#handlerOf}: the provider of a
 *       digital product, else the publisher), as {@code OpenDigitalPrize} opens them and {@code SealDigitalCodes}
 *       sealed them for the caller the pool's load let in.</li>
 * </ul>
 */
@Configuration
public class PrizeSealedFields {

    @Bean
    SealedFields qbPrizeSealedFields() {
        return () -> List.of(
            SealedField.of(QbPublications.PUBLICATION, "sealedDigitalContent", "publisherId"),
            SealedField.of(QbPublications.CARD, "sealedDigitalContent", "publisherId"),
            SealedField.of(QbDigitalPrizes.DIGITAL_PRIZE, "sealedContent", QbRewards::handlerOf),
            SealedField.of(QbDigitalPrizes.DIGITAL_CODE, "sealedContent", QbRewards::handlerOf));
    }
}
