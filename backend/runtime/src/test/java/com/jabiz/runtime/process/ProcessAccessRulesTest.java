package com.jabiz.runtime.process;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessAccess;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may start a process, as the executor decides it for every root and sub-process (decision D41). The startup
 * check keeps a process that is not internal() from calling an internal() one; the executor refuses it all the same.
 */
class ProcessAccessRulesTest {

    record In(String text) {}

    private static final RequestContext SYSTEM = RequestContext.system(Locale.ENGLISH, "t");
    private static final RequestContext USER = new RequestContext("user-1", null, Locale.ENGLISH, "t", Set.of(),
        Set.of("*"));

    private static ProcessDefinition<In, In, ProcessContext> process(ProcessAccess access) {
        return ProcessDefinition.single("P_" + access, 1, In.class, In.class, (in, ctx) -> in)
            .withPermissions("p.run").withAccess(access);
    }

    @Test
    void rootsStartAsTheirDeclarationAllows() {
        ExecutionOptions none = ExecutionOptions.NONE;
        assertThatCode(() -> ProcessExecutor.requireStartable(process(ProcessAccess.OPEN), USER, none))
            .doesNotThrowAnyException();
        assertThatCode(() -> ProcessExecutor.requireStartable(process(ProcessAccess.INTERNAL), SYSTEM, none))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> ProcessExecutor.requireStartable(process(ProcessAccess.INTERNAL), USER, none))
            .isInstanceOf(ProcessInternalException.class);
        ProcessDefinition<In, In, ProcessContext> entry = process(ProcessAccess.ENTRY_ONLY);
        assertThatThrownBy(() -> ProcessExecutor.requireStartable(entry, SYSTEM, none))
            .isInstanceOf(ProcessInternalException.class);
        assertThatCode(() -> ProcessExecutor.requireStartable(entry, USER, none.atEntryOf(entry)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> ProcessExecutor.requireStartable(process(ProcessAccess.SUB_PROCESS_ONLY), SYSTEM,
            none)).isInstanceOf(ProcessInternalException.class);
        // An app proxy's process: from the proxy's entry only (decision D60).
        ProcessDefinition<In, In, ProcessContext> proxied = process(ProcessAccess.APP_PROXY);
        assertThatThrownBy(() -> ProcessExecutor.requireStartable(proxied, USER, none))
            .isInstanceOf(ProcessInternalException.class).hasMessageContaining("app proxy");
        assertThatThrownBy(() -> ProcessExecutor.requireStartable(proxied, SYSTEM, none))
            .isInstanceOf(ProcessInternalException.class);
        assertThatCode(() -> ProcessExecutor.requireStartable(proxied, USER, none.atEntryOf(proxied)))
            .doesNotThrowAnyException();
    }

    @Test
    void anInternalSubProcessRunsOnlyUnderARootTheSystemRuns() {
        assertThatThrownBy(() -> ProcessExecutor.requireCallable(process(ProcessAccess.INTERNAL), USER))
            .isInstanceOf(ProcessInternalException.class);
        assertThatCode(() -> ProcessExecutor.requireCallable(process(ProcessAccess.INTERNAL), SYSTEM))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> ProcessExecutor.requireCallable(process(ProcessAccess.ENTRY_ONLY), SYSTEM))
            .isInstanceOf(ProcessInternalException.class);
        assertThatThrownBy(() -> ProcessExecutor.requireCallable(process(ProcessAccess.APP_PROXY), SYSTEM))
            .isInstanceOf(ProcessInternalException.class);
        assertThatCode(() -> ProcessExecutor.requireCallable(process(ProcessAccess.SUB_PROCESS_ONLY), USER))
            .doesNotThrowAnyException();
        assertThatCode(() -> ProcessExecutor.requireCallable(process(ProcessAccess.OPEN), USER))
            .doesNotThrowAnyException();
    }
}
