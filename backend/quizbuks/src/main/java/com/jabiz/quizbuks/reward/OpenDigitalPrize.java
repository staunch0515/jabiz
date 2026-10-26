package com.jabiz.quizbuks.reward;

import com.jabiz.entity.Violation;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.SealedSecrets;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Opens a digital prize's sealed content for its winner ({@code QB_DIGITAL_PRIZE_OPEN}, Q33): sealed under the
 * sponsor's id when loaded or sent. One sealed under another key (the sealing key was changed) cannot be opened:
 * the sponsor sends it again. Opening is cheap, but the sealer's work is kept off the request threads.
 */
@Component
public class OpenDigitalPrize implements BlockingStep<NoMetadata, ProcessContext> {

    private final SecretSealer sealer;

    public OpenDigitalPrize(SecretSealer sealer) {
        this.sealer = sealer;
    }

    @Override
    public void run(NoMetadata metadata, ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        EntityInstance prize = ctx.get(DigitalPrizeProcesses.PRIZE, EntityInstance.class);
        String sealed = prize.get("sealedContent");
        if (sealed == null) {
            return;
        }
        try {
            ctx.put(DigitalPrizeProcesses.CONTENT, sealer.open(sealed, String.valueOf((Object) prize.get("publisherId"))));
        } catch (SealedSecrets.UnreadableSecretException e) {
            ctx.reject(new Violation("digitalPrizeId", RewardCodes.DIGITAL_UNREADABLE, "The prize's content cannot "
                + "be opened: ask the sponsor to send it again", Map.of()));
        }
    }
}
