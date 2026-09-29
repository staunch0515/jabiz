package com.jabiz.runtime.numbering;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup self-check of the number sequences: each name declared once. The formats are checked where they are
 * declared, and the steps that draw numbers check the sequence they name ({@link AssignNumber}).
 */
@Component
public class NumberingChecks implements PlatformCheck {

    public static final String CATEGORY = "NUMBERING";

    private final NumberSequenceRegistry sequences;

    public NumberingChecks(NumberSequenceRegistry sequences) {
        this.sequences = sequences;
    }

    @Override
    public List<CheckProblem> check() {
        return sequences.duplicates().stream()
            .map(name -> CheckProblem.error(CATEGORY, "NumberSequence " + name, "declared more than once"))
            .toList();
    }
}
