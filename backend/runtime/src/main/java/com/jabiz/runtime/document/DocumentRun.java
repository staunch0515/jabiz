package com.jabiz.runtime.document;

import com.jabiz.document.DocumentContent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An issued document as {@code sys_document_run} keeps it (docs/design/22-documents.md section 4).
 *
 * @param layoutSource     the layout's canonical description when it was issued
 * @param templateVersions the version of every template read, by template id
 * @param permissions      the layout's and its templates' permissions when it was issued: reading it needs them all
 * @param scope            by dataset id, the issuer's values of the scopes that depend on the caller
 * @param params           the parameters it was issued with
 * @param asOf             the effective time asked for; null when none was
 * @param readAt           the effective time the templates were read at
 * @param knownAt          the recorded time they were read as of
 * @param content          what it shows; null when only the summary was read
 * @param pdf              the PDF as issued; null when only the summary was read
 */
public record DocumentRun(UUID runId, String layoutId, String layoutVersion, String layoutSource,
    Map<String, String> templateVersions, List<String> permissions, Map<String, Map<String, String>> scope,
    String subjectEntity, String subjectId, String documentNo, String title, String language, String pageSize,
    Map<String, Object> params, Instant asOf, Instant readAt, Instant knownAt, DocumentContent content,
    String contentHash, boolean recomputable, byte[] pdf, String pdfHash, int pdfSize, int pageCount, String issuedBy,
    Instant issuedTime, long processSeqId) {}
