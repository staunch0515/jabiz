package com.jabiz.runtime.approval;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup self-check of the approval subjects: each name declared once, each declared entity type known. The steps that ask for approval check the
 * subject they name ({@link RequireApproval}, {@link WithdrawApproval}); stored rules are checked against their
 * subject when they are proposed and published.
 */
@Component
public class ApprovalChecks implements PlatformCheck {

    public static final String CATEGORY = "APPROVAL";

    private final ApprovalSubjectRegistry subjects;
    private final EntityDefinitionRegistry entities;

    public ApprovalChecks(ApprovalSubjectRegistry subjects, EntityDefinitionRegistry entities) {
        this.subjects = subjects;
        this.entities = entities;
    }

    @Override
    public List<CheckProblem> check() {
        Set<String> seen = new HashSet<>();
        List<CheckProblem> problems = new ArrayList<>();
        subjects.all().stream().map(ApprovalSubject::name).filter(name -> !seen.add(name)).distinct()
            .map(name -> CheckProblem.error(CATEGORY, "ApprovalSubject " + name, "declared more than once"))
            .forEach(problems::add);
        subjects.all().stream().filter(s -> s.entity() != null && !entities.contains(s.entity()))
            .map(s -> CheckProblem.error(CATEGORY, "ApprovalSubject " + s.name(), "entity " + s.entity()
                + " is not declared"))
            .forEach(problems::add);
        return List.copyOf(problems);
    }
}
