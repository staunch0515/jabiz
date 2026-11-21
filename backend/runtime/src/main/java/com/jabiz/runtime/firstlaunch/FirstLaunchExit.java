package com.jabiz.runtime.firstlaunch;

/**
 * Ends the application when the first launch cannot go on (docs/design/23-first-launch.md section 4): a failed
 * content phase exits with a non-zero code, a dry run with 0. Tests replace it.
 */
@FunctionalInterface
public interface FirstLaunchExit {

    void exit(int code);
}
