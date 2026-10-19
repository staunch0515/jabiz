package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;

/**
 * The handler of {@value AiCoverJob#TYPE} (docs/quizbuks/plans/Q8-ai.md, Q8b): asks the {@link CoverDesigner} for
 * designs, keeps the ones of the catalogue whose text the server can draw ({@link CoverDesigns#check}), draws them
 * ({@link CoverRenderer}) and returns the pictures as the job's files {@code cover-1..n}, which the platform stores as the
 * sponsor's uploads. None kept: the result has no designs, and the completion records that none met the rules.
 *
 * <p>Before anything is sent, a server whose fonts cannot draw the requested language's script (or the quiz's title)
 * fails with {@link AiCodes#COVER_FONT}, nothing charged: a server without Chinese and Japanese fonts would otherwise pay
 * for designs it cannot draw. It reads no database: {@code qb.ai.enabled} holds its jobs before they are claimed
 * ({@link AiCoverJob#type}).
 */
@Component
public class AiCoverHandler implements ExternalJobHandler<AiCoverJob.Input, AiCoverJob.Result> {

    /** A sample of each language's script the covers may be written in: the fonts must draw all of it. */
    static final java.util.Map<String, String> SAMPLES = java.util.Map.of(
        "en", "Quiz: The Rivers of Europe 2026",
        "ja", "日本の歴史クイズ：ひらがなとカタカナ",
        "zh", "中国历史知识测验：长江与黄河");

    private final CoverDesigner designer;
    private final CoverRenderer renderer;

    AiCoverHandler(CoverDesigner designer, CoverRenderer renderer) {
        this.designer = designer;
        this.renderer = renderer;
    }

    @Override
    public ExternalJobResult<AiCoverJob.Result> run(AiCoverJob.Input input, ExternalJobContext ctx) {
        // Before anything is paid for (review M1): the requested language's script, and the title, which the cover
        // text usually is or shortens.
        if (!renderer.drawable(SAMPLES.getOrDefault(input.language(), SAMPLES.get("en")))
            || !renderer.drawable(input.title())) {
            throw ExternalJobException.permanent(AiCodes.COVER_FONT, "The server has no font for the covers' text");
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
