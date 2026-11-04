package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;

import java.util.List;

/**
 * Who designs candidate covers (docs/quizbuks/plans/Q8-ai.md, Q8b): the provider behind the job
 * {@value AiCoverJob#TYPE}, as {@link QuestionWriter} is for questions. It returns designs of the catalogue
 * ({@link CoverDesigns}) or, since Q96, an illustration as SVG; the server draws them. Changing the provider (or, A-4 option B, using an image model) changes
 * its implementation and the external service.
 */
public interface CoverDesigner {

    /**
     * The designs of one request, during one attempt of the job, as the provider gave them (unchecked). Records the
     * provider's usage on {@code ctx} as soon as it is known, or throws the attempt's failure.
     */
    Designed design(AiCoverJob.Input input, ExternalJobContext ctx);

    /**
     * An illustration of one request (Q96), during one attempt of the job, as the provider gave it (unchecked: the
     * handler reads the SVG through {@link CoverSvg}). Records the usage on {@code ctx} as {@link #design} does.
     */
    Illustrated illustrate(AiCoverJob.Input input, ExternalJobContext ctx);

    /** An illustration: its SVG, the provider's wording of the title and where it fits, and which model gave it. */
    record Illustrated(String servedModel, boolean fellBack, String svg, String text, String placement) {}

    /** The designs and which model gave them. */
    record Designed(String servedModel, boolean fellBack, List<CoverDesigns.Design> designs) {
        public Designed {
            designs = designs == null ? List.of() : java.util.Collections.unmodifiableList(
                new java.util.ArrayList<>(designs));
        }
    }
}
