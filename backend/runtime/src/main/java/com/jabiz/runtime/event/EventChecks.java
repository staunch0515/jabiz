package com.jabiz.runtime.event;

import com.jabiz.event.EventSubscription;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.job.JobChecks;
import com.jabiz.runtime.process.ProcessRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup check of event subscriptions (category {@code EVENT}): consumer names are unique (a consumption is
 * identified by consumer and event), and every consumer runs a registered process.
 */
@Component
public class EventChecks implements PlatformCheck {

    static final String CATEGORY = "EVENT";

    private final OutboxDeliverer deliverer;
    private final ProcessRegistry processes;

    public EventChecks(OutboxDeliverer deliverer, ProcessRegistry processes) {
        this.deliverer = deliverer;
        this.processes = processes;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> consumers = new HashSet<>();
        for (EventSubscription<?> subscription : deliverer.subscriptions()) {
            String where = "consumer " + subscription.consumer();
            if (!consumers.add(subscription.consumer())) {
                problems.add(CheckProblem.error(CATEGORY, where, "is declared more than once"));
            }
            problems.addAll(JobChecks.processProblems(processes, CATEGORY, where, subscription.process()));
        }
        return problems;
    }
}
