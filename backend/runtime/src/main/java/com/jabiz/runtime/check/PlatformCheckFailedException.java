package com.jabiz.runtime.check;

import java.util.List;
import java.util.stream.Collectors;

/** The application is inconsistent: thrown at startup with every error the platform checks found. */
public class PlatformCheckFailedException extends IllegalStateException {

    private final List<CheckProblem> problems;

    public PlatformCheckFailedException(List<CheckProblem> problems) {
        super("Platform check failed (" + problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + "):\n - "
            + problems.stream().map(CheckProblem::format).collect(Collectors.joining("\n - ")));
        this.problems = List.copyOf(problems);
    }

    public List<CheckProblem> problems() {
        return problems;
    }
}
