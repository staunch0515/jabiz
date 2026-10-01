package com.jabiz.runtime.document;

/** Permission codes of business documents (docs/design/22-documents.md section 4). */
public final class DocumentPermissions {

    /** Run {@code DOCUMENT_ISSUE} and preview documents; both also need the layout's and its templates' permissions. */
    public static final String ISSUE = "document.issue";
    /** Read issued documents, print them again and verify them; each also needs the permissions it was issued with. */
    public static final String ARCHIVE_READ = "document.archive.read";

    private DocumentPermissions() {}
}
