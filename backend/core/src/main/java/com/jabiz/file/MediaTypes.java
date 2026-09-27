package com.jabiz.file;

import java.util.List;

/**
 * The file types the platform can recognise by content (docs/design/14-files.md section 3). Nothing else is ever
 * accepted: in particular no SVG, HTML, XML or script type, which could run scripts on the application's origin.
 */
public enum MediaTypes {

    JPEG("image/jpeg", "jpg", true, List.of(".jpg", ".jpeg")),
    PNG("image/png", "png", true, List.of(".png")),
    PDF("application/pdf", "pdf", false, List.of(".pdf")),
    MP3("audio/mpeg", "mp3", false, List.of(".mp3")),
    M4A("audio/mp4", "m4a", false, List.of(".m4a")),
    OGG("audio/ogg", "ogg", false, List.of(".ogg", ".oga", ".opus"));

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

    /** File name extensions an upload control may suggest (with the dot); advisory only, the content decides. */
    public List<String> extensions() {
        return extensions;
    }
}
