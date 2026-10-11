package com.jabiz.app.archfixture;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.security.ActingUser;

import java.util.Locale;

/**
 * Business code that would start processes itself, or pass for another actor, one way per class: the
 * counter-examples of {@code ArchitectureTest}'s rule against the process machinery (decision D41). Test sources only,
 * so the real check never sees them.
 */
public final class ProcessMachinery {

    private ProcessMachinery() {}

    /** Runs a process directly, outside the steps and entries the platform owns. */
    public static final class Executor {
        Object run(ProcessExecutor executor) {
            return executor;
        }
    }

    /** Claims to be a platform entry point. */
    public static final class EntryOption {
        Object claim() {
            return ExecutionOptions.NONE;
        }
    }

    /** Runs work as another user. */
    public static final class ActsAsSomeoneElse {
        Object act() {
            return ActingUser.userId(null);
        }
    }

    /** Makes itself the system. */
    public static final class PassesForTheSystem {
        Object forge() {
            return RequestContext.system(Locale.ENGLISH, "forged");
        }
    }

    /** Only reads who is acting: allowed. */
    public static final class ReadsTheActor {
        boolean bySystem(RequestContext request) {
            return RequestContext.SYSTEM_ACTOR.equals(request.actorId());
        }
    }
}
