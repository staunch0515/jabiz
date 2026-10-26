package com.jabiz.quizbuks.reward;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.security.secret.SecretSealer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Seals the codes a sponsor loads into a publication's pool ({@code QB_DIGITAL_CODES_LOAD}, Q33), each under the
 * sponsor's id (what {@code QB_DIGITAL_PRIZE_OPEN} opens them with). Blocking: sealing draws random bytes.
 */
@Component
public class SealDigitalCodes implements BlockingStep<NoMetadata, ProcessContext> {

    private final SecretSealer sealer;

    public SealDigitalCodes(SecretSealer sealer) {
        this.sealer = sealer;
    }

    @Override
    public void run(NoMetadata metadata, ProcessContext ctx) {
        if (ctx.hasViolations() || !ctx.contains(DigitalCodeProcesses.LINES)) {
            return;
        }
        List<String> lines = ctx.get(DigitalCodeProcesses.LINES, List.class);
        String context = String.valueOf((Object) ctx.request().actorId());
        List<String> sealed = new ArrayList<>(lines.size());
        for (String line : lines) {
            sealed.add(sealer.seal(line, context));
        }
        ctx.put(DigitalCodeProcesses.SEALED, List.copyOf(sealed));
    }
}
