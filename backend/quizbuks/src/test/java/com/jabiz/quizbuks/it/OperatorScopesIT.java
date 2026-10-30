package com.jabiz.quizbuks.it;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetScope;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q59c-2 (platform decision D64): every dataset an operator's session reads, through the dataset API or a template its
 * permissions run, is limited to the operator's sponsors, or limited to the caller, or listed here with the reason it
 * needs no limit. A new dataset or template within the operators' reach fails here until it says which.
 */
class OperatorScopesIT extends QbItSupport {

    /** Datasets an operator reads whole, and why that is right. */
    static final Set<String> WHOLE = Set.of(
        // Reference data and settings: the countries, the parameters in effect (the reward defaults), when the
        // play statistics were last counted.
        "urn:jabiz:dataset:default:QbCountry", "urn:jabiz:dataset:now:SysParam",
        "urn:jabiz:dataset:default:QbStatsRun");

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    SqlTemplateRegistry templates;

    private static boolean limited(DatasetDefinition dataset) {
        return dataset.scope().entries().stream().anyMatch(entry -> entry instanceof DatasetScope.FromContext
            || entry instanceof DatasetScope.WithinEntryScope within && QbOperators.SCOPE.equals(within.scope())
            || entry instanceof DatasetScope.WithinEntryScopeTag || entry instanceof DatasetScope.Fixed);
    }

    @Test
    void everyDatasetWithinTheOperatorsReachIsLimited() {
        Set<String> permissions = Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions());
        Set<String> problems = new TreeSet<>();
        for (DatasetDefinition dataset : datasets.all()) {
            if (permissions.contains(dataset.permissions().read()) && !limited(dataset)
                && !WHOLE.contains(dataset.resourceId())) {
                problems.add("dataset " + dataset.resourceId());
            }
        }
        for (AdvancedQueryDefinition template : templates.all()) {
            // A template needs every permission it names; the operators run those whose permissions they all hold.
            // Public templates read public datasets only, the same for everybody.
            if (template.publicAccess() || !permissions.containsAll(template.permissions())
                || templates.datasetsOf(template).values().stream()
                    .anyMatch(dataset -> MEMBERSHIP.equals(dataset.resourceId()))) {
                // The operators' own templates read the caller's membership and limit their rows by it.
                continue;
            }
            List<String> open = new ArrayList<>();
            for (DatasetDefinition dataset : templates.datasetsOf(template).values()) {
                if (!limited(dataset) && !WHOLE.contains(dataset.resourceId())
                    && !TEMPLATE_JOINS.contains(dataset.resourceId())) {
                    open.add(dataset.resourceId());
                }
            }
            if (!open.isEmpty()) {
                problems.add("template " + template.queryId() + " reads " + open);
            }
        }
        assertThat(problems).isEmpty();
    }

    /**
     * Datasets the operators' templates join to rows already limited (a sponsor's name, its ban, a user's address):
     * whole, since the limited rows choose which of theirs show. Not readable through the dataset API with the
     * operators' permissions.
     */
    static final Set<String> TEMPLATE_JOINS = Set.of("urn:jabiz:dataset:default:QbProfile",
        "urn:jabiz:dataset:platform:SecUser", "urn:jabiz:dataset:default:QbUserBan",
        "urn:jabiz:dataset:default:QbSponsorOperator", "urn:jabiz:dataset:default:QbOperator");

    /** The operators' own templates read the caller's membership (limited to the caller) and filter by it. */
    static final String MEMBERSHIP = QbOperators.OWN_MEMBERSHIP_DATASET;
}
