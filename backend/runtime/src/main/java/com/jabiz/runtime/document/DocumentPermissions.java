package com.jabiz.runtime.document;

/** Permission codes of business documents (docs/design/22-documents.md section 4). */
public final class DocumentPermissions {

    /** Run {@code DOCUMENT_ISSUE} and preview documents; both also need the layout's and its templates' permissions. */
    public static final String ISSUE = "document.issue";
    /** Read issued documents, print them again and verify them; each also needs the permissions it was issued with. */
    public static final String ARCHIVE_READ = "document.archive.read";
    /** Run {@code DOCUMENT_SEND} to the addresses a document's data names; also needs the document's permissions. */
    public static final String SEND = "document.send";
    /** Send documents to addresses their data does not name as well (to be granted sparingly). */
    public static final String SEND_ANY = "document.send.any";

    private DocumentPermissions() {}
}
