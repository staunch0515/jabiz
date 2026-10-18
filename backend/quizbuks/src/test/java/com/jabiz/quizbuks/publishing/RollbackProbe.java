package com.jabiz.quizbuks.publishing;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;

/**
 * Test only: a process that moves a publication as an administrator's end does, announces the move for the sponsor's
 * mail as every such process does, and then fails, so that its transaction is rolled back (Q7a review: a rolled-back
 * move announces nothing).
 */
public final class RollbackProbe {

    public static final String NAME = "QB_TEST_END_THEN_FAIL";

    public static ProcessDefinition<PublicationAdmin.AdminInput, PublicationOutput, ProcessContext> define() {
        return ProcessDefinition.define(NAME, 1, PublicationAdmin.AdminInput.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Test only: ends a publication, announces it, then fails.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(PublicationEditing.OUTPUT, PublicationOutput.class))
                .steps(PublicationEditing::lockStatesAndLoadAny)
                .compute("End", (metadata, ctx) -> PublicationAdmin.end(ctx,
                    ctx.get(PublicationEditing.INPUT, PublicationAdmin.AdminInput.class)))
                .steps(PublicationNotices::announce)
                .compute("Fail", (metadata, ctx) -> {
                    throw new IllegalStateException("Rolled back on purpose");
                }));
    }

    private RollbackProbe() {}
}
