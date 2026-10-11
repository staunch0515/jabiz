package com.jabiz.quizbuks.publishing;

import java.util.UUID;

/**
 * A publication after a process moved it (or found nothing to do).
 *
 * @param approval          what the approval found ({@code PENDING}, {@code APPROVED}, {@code NOT_REQUIRED}) when
 *                          the process asked; null otherwise
 * @param approvalRequestId the request awaiting review or approved, if any
 */
public record PublicationOutput(UUID publicationId, String status, long revision, String approval,
    String approvalRequestId) {}
