package com.jabiz.runtime.approval;

import com.jabiz.approval.ApprovalSubject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** The declared {@link ApprovalSubject} beans; duplicate names are reported by {@link ApprovalChecks}. */
@Component
public class ApprovalSubjectRegistry {

    private final ObjectProvider<ApprovalSubject> subjects;

    public ApprovalSubjectRegistry(ObjectProvider<ApprovalSubject> subjects) {
        this.subjects = subjects;
    }

    public List<ApprovalSubject> all() {
        return subjects.orderedStream().toList();
    }

    public Optional<ApprovalSubject> find(String name) {
        return subjects.orderedStream().filter(subject -> subject.name().equals(name)).findFirst();
    }
}
