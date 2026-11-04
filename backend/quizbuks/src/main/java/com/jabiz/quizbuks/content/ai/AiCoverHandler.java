package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The handler of {@value AiCoverJob#TYPE} (docs/quizbuks/plans/Q8-ai.md, Q8b; Q96 for illustrations, drawn through
 * {@link CoverSvg} with the title on a band): asks the {@link CoverDesigner} for designs, keeps the ones of the catalogue whose text the server can draw ({@link CoverDesigns#check}), draws them
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
        if (AiCoverJob.ILLUSTRATION.equals(input.styleOrDefault())) {
            return illustrate(input, ctx);
        }
        // Before anything is paid for (review M1): the requested language's script, and the title, which the cover
        // text usually is or shortens (or the sponsor's own text, Q96).
        String own = sponsorsText(input);
        if (!renderer.drawable(SAMPLES.getOrDefault(input.language(), SAMPLES.get("en")))
            || !renderer.drawable(own != null ? own : input.title())) {
            throw ExternalJobException.permanent(AiCodes.COVER_FONT, "The server has no font for the covers' text");
        }
        CoverDesigner.Designed designed = designer.design(input, ctx);
        List<CoverDesigns.Design> designs = own == null ? designed.designs() : designed.designs().stream()
            .map(d -> d == null ? null : new CoverDesigns.Design(d.palette(), d.layout(), d.motif(), own)).toList();
        CoverDesigns.Checked checked = CoverDesigns.check(designs, renderer::drawable);
        ExternalJobResult<AiCoverJob.Result> result = ExternalJobResult.of(new AiCoverJob.Result(
            designed.servedModel(), designed.fellBack(), checked.valid(), checked.invalid()));
        for (int i = 0; i < checked.valid().size(); i++) {
            result = result.file(AiCoverJob.fileKey(i + 1), "cover-" + (i + 1) + ".png",
                renderer.render(checked.valid().get(i)));
        }
        return result;
    }

    /**
     * An illustration (Q96): the SVG read through {@link CoverSvg} (not one of its kind, or out of its bounds, is not
     * the answer asked for: tried again), the title (the sponsor's text, else Claude's wording, else the quiz's title)
     * on a band where Claude left room.
     */
    private ExternalJobResult<AiCoverJob.Result> illustrate(AiCoverJob.Input input, ExternalJobContext ctx) {
        String own = sponsorsText(input);
        if (input.overlay() && (!renderer.drawable(SAMPLES.getOrDefault(input.language(), SAMPLES.get("en")))
            || !renderer.drawable(own != null ? own : input.title()))) {
            throw ExternalJobException.permanent(AiCodes.COVER_FONT, "The server has no font for the cover's text");
        }
        CoverDesigner.Illustrated illustrated = designer.illustrate(input, ctx);
        CoverSvg.Drawing drawing;
        try {
            drawing = CoverSvg.parse(illustrated.svg());
        } catch (CoverSvg.Invalid e) {
            throw ClaudeErrors.badResponse("is not an illustration the server draws: " + e.getMessage());
        }
        String text = null;
        if (input.overlay()) {
            text = own != null ? own : usable(illustrated.text());
            if (text == null) {
                text = usable(input.title());
            }
            if (text == null) {
                throw ClaudeErrors.badResponse("has no title the server can draw");
            }
        }
        String placement = CoverRenderer.PLACEMENTS.contains(illustrated.placement()) ? illustrated.placement()
            : "bottom";
        AiCoverJob.Overlay overlay = new AiCoverJob.Overlay(text, placement);
        return ExternalJobResult.of(new AiCoverJob.Result(illustrated.servedModel(), illustrated.fellBack(),
                List.of(), 0, AiCoverJob.ILLUSTRATION, overlay))
            .file(AiCoverJob.fileKey(1), "cover-1.png", renderer.renderIllustration(drawing, text, placement));
    }

    /** The sponsor's cover text, as the request checked it; null when Claude words the title. */
    private static String sponsorsText(AiCoverJob.Input input) {
        return input.titleText() == null || input.titleText().isBlank() ? null : input.titleText().strip();
    }

    /** A text as a cover shows it (trimmed, single spaces, 1–40 plain characters the fonts have); else null. */
    private String usable(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.strip().replaceAll("\\s+", " ");
        return CoverDesigns.text(cleaned) && renderer.drawable(cleaned) ? cleaned : null;
    }
}
