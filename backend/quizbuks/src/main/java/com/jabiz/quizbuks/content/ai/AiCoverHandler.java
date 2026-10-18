package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The handler of {@value AiCoverJob#TYPE} (docs/quizbuks/plans/Q8-ai.md, Q8b): asks the {@link CoverDesigner} for
 * designs, keeps the ones of the catalogue whose text the server can draw ({@link CoverDesigns#check}), draws them
 * ({@link CoverRenderer}) and returns the pictures as the job's files {@code cover-1..n}, which the platform stores as the
 * sponsor's uploads. None kept: the result has no designs, and the completion records that none met the rules.
 *
 * <p>Before anything is sent: halted servers fail at once ({@link AiCodes#HALTED}, as {@link AiQuestionsHandler}), and
 * a quiz title the server's fonts cannot draw fails with {@link AiCodes#COVER_FONT} (the covers would show the title,
 * nothing charged). It reads no database.
 */
@Component
public class AiCoverHandler implements ExternalJobHandler<AiCoverJob.Input, AiCoverJob.Result> {

    private final CoverDesigner designer;
    private final CoverRenderer renderer;
    private final boolean halted;

    AiCoverHandler(CoverDesigner designer, CoverRenderer renderer,
        @Value("${quizbuks.ai.halted:false}") boolean halted) {
        this.designer = designer;
        this.renderer = renderer;
        this.halted = halted;
    }

    @Override
    public ExternalJobResult<AiCoverJob.Result> run(AiCoverJob.Input input, ExternalJobContext ctx) {
        if (halted) {
            throw ExternalJobException.permanent(AiCodes.HALTED, "AI generation is halted on this server");
        }
        if (!renderer.drawable(input.title())) {
            throw ExternalJobException.permanent(AiCodes.COVER_FONT, "The server has no font for the quiz's title");
        }
        CoverDesigner.Designed designed = designer.design(input, ctx);
        CoverDesigns.Checked checked = CoverDesigns.check(designed.designs(), renderer::drawable);
        ExternalJobResult<AiCoverJob.Result> result = ExternalJobResult.of(new AiCoverJob.Result(
            designed.servedModel(), designed.fellBack(), checked.valid(), checked.invalid()));
        for (int i = 0; i < checked.valid().size(); i++) {
            result = result.file(AiCoverJob.fileKey(i + 1), "cover-" + (i + 1) + ".png",
                renderer.render(checked.valid().get(i)));
        }
        return result;
    }
}
