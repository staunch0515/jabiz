package com.jabiz.runtime.param;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup self-check of the controlled parameters (decision D40): every declared key is well formed. A key that has
 * no parameter yet is fine: it is created later, by a controlled change as well.
 */
@Component
public class ParamChecks implements PlatformCheck {

    public static final String CATEGORY = "PARAM";

    private final ControlledParamRegistry controlled;

    public ParamChecks(ControlledParamRegistry controlled) {
        this.controlled = controlled;
    }

    @Override
    public List<CheckProblem> check() {
        return controlled.declarations().stream()
            .flatMap(declaration -> declaration.problems().stream())
            .map(problem -> CheckProblem.error(CATEGORY, "ControlledParams", problem))
            .toList();
    }
}
