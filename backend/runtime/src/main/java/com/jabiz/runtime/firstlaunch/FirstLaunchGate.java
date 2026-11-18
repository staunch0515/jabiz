package com.jabiz.runtime.firstlaunch;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether the application serves (docs/design/23-first-launch.md section 3): closed from startup until the first
 * launch's content is in place, so nobody sees a half-made system. Open whenever there is no first launch to do.
 */
@Component
public class FirstLaunchGate {

    private final AtomicBoolean open = new AtomicBoolean(true);

    public boolean isOpen() {
        return open.get();
    }

    void close() {
        open.set(false);
    }

    void open() {
        open.set(true);
    }
}
