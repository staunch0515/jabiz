package com.jabiz.runtime.firstlaunch;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code firstLaunch} in the actuator's health (docs/design/23-first-launch.md section 3): DOWN while the
 * {@link FirstLaunchGate} is closed, so the overall health, and a readiness group that includes it, show that the
 * application does not serve yet; the process itself stays alive.
 */
@Component("firstLaunch")
public class FirstLaunchHealth implements HealthIndicator {

    private final FirstLaunchGate gate;

    public FirstLaunchHealth(FirstLaunchGate gate) {
        this.gate = gate;
    }

    @Override
    public Health health() {
        return gate.isOpen() ? Health.up().build()
            : Health.down().withDetail("reason", "the first launch is putting the content in place").build();
    }
}
