package com.jabiz.file;

import java.util.List;

/**
 * The file types the platform can recognise by content (docs/design/14-files.md section 3). Nothing else is ever
 * accepted: in particular no SVG, HTML or script type, which could run scripts on the application's origin. The
 * import types ({@link #isImportOnly()}: text, XLSX, XML) are accepted only by policies that accept nothing else, are
 * stored as uploaded and are never served inline or publicly (decision D26).
 */
public enum MediaTypes {

    JPEG("image/jpeg", "jpg", true, List.of(".jpg", ".jpeg")),
    PNG("image/png", "png", true, List.of(".png")),
    PDF("application/pdf", "pdf", false, List.of(".pdf")),
    MP3("audio/mpeg", "mp3", false, List.of(".mp3")),
    M4A("audio/mp4", "m4a", false, List.of(".m4a")),
    OGG("audio/ogg", "ogg", false, List.of(".ogg", ".oga", ".opus")),
    /** Plain text: CSV, fixed-width and other line formats alike; the import definition chooses the parser. */
    TEXT("text/plain", "txt", false, List.of(".csv", ".txt", ".tsv", ".bai", ".bai2", ".ofx", ".qif")),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx", false, List.of(".xlsx")),
    XML("application/xml", "xml", false, List.of(".xml"));

    private final String contentType;
    private final String extension;
    private final boolean image;
    private final List<String> extensions;

    MediaTypes(String contentType, String extension, boolean image, List<String> extensions) {
        this.contentType = contentType;
        this.extension = extension;
        this.image = image;
        this.extensions = extensions;
    }

    /** The media type served with the file. */
    public String contentType() {
        return contentType;
    }

    /** The usual file name extension, without the dot. */
    public String extension() {
        return extension;
    }

    /** Whether the platform decodes and re-encodes files of this type. */
    public boolean isImage() {
        return image;
    }

    /** Whether the type is only for imports: stored as uploaded, never served inline or publicly (decision D26). */
    public boolean isImportOnly() {
        return this == TEXT || this == XLSX || this == XML;
    }

    /** File name extensions an upload control may suggest (with the dot); advisory only, the content decides. */
    public List<String> extensions() {
        return extensions;
    }
}
