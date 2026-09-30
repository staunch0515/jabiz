package com.jabiz.runtime.retention;

import com.jabiz.retention.RetentionPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Month;
import java.util.List;
import java.util.Optional;

/**
 * The retention policies of the application (beans) and the end of its fiscal year
 * ({@code jabiz.fiscal-year-end}, the month number; default 12), docs/design/21-audit-retention.md section 3.
 */
@Component
public class RetentionPolicies {

    private final List<RetentionPolicy> policies;
    private final Month fiscalYearEnd;

    public RetentionPolicies(ObjectProvider<RetentionPolicy> policies,
        @Value("${jabiz.fiscal-year-end:12}") int fiscalYearEnd) {
        this.policies = policies.orderedStream().toList();
        this.fiscalYearEnd = Month.of(fiscalYearEnd);
    }

    public List<RetentionPolicy> all() {
        return policies;
    }

    /** The policy of the entity; the first when several are declared (the startup check reports that). */
    public Optional<RetentionPolicy> of(String entity) {
        return policies.stream().filter(policy -> policy.entity().equals(entity)).findFirst();
    }

    public Month fiscalYearEnd() {
        return fiscalYearEnd;
    }
}
