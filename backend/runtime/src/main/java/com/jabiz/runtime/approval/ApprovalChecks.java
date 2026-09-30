package com.jabiz.runtime.approval;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup self-check of the approval subjects: each name declared once. The steps that ask for approval check the
 * subject they name ({@link RequireApproval}, {@link WithdrawApproval}); stored rules are checked against their
 * subject when they are proposed and published.
 */
@Component
public class ApprovalChecks implements PlatformCheck {

    public static final String CATEGORY = "APPROVAL";

    private final ApprovalSubjectRegistry subjects;

    public ApprovalChecks(ApprovalSubjectRegistry subjects) {
        this.subjects = subjects;
    }

    @Override
    public List<CheckProblem> check() {
        Set<String> seen = new HashSet<>();
        return subjects.all().stream().map(ApprovalSubject::name).filter(name -> !seen.add(name)).distinct()
            .map(name -> CheckProblem.error(CATEGORY, "ApprovalSubject " + name, "declared more than once"))
            .toList();
    }
}
