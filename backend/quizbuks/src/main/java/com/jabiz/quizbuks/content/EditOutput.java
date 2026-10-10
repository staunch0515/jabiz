package com.jabiz.quizbuks.content;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The quiz after an edit: its revision (the number of changes of its content) and when it last changed.
 *
 * @param itemId  the question or material saved or deleted; null for the quiz itself
 * @param partIds the options or images of the saved item, or the reordered items, in their order
 */
public record EditOutput(UUID quizId, long revision, Instant editedAt, UUID itemId, List<UUID> partIds) {
    public EditOutput {
        partIds = partIds == null ? List.of() : List.copyOf(partIds);
    }
}
