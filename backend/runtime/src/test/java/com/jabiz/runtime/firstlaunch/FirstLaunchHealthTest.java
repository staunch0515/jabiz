package com.jabiz.runtime.firstlaunch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

class FirstLaunchHealthTest {

    @Test
    void downWhileTheGateIsClosed() {
        FirstLaunchGate gate = new FirstLaunchGate();
        FirstLaunchHealth health = new FirstLaunchHealth(gate);

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        gate.close();
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        gate.open();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }
}
