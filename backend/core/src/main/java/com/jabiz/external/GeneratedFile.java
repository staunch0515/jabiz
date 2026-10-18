package com.jabiz.external;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A file an external job's handler produced (decision D50 item 3), returned with its result
 * ({@link ExternalJobResult#file}). The platform does not take the handler's word for what it is: it recognises the
 * type from the content, refuses what the job type's output policy does not allow, re-encodes images (which drops all
 * their metadata) and records the file as an upload of the job's requester. The completion process gets the file's id
 * under {@link #key}.
 *
 * @param key     {@value #KEY_PATTERN}, unique within the result: how the completion process finds the file
 * @param name    the file's name for downloads (cleaned like an uploaded one); the key when null
 * @param content the bytes, at least one. Not copied (a file may be tens of megabytes, and two copies of every file
 *                would double what an attempt holds in memory): the array passes to the result, and the handler
 *                must not change it afterwards; {@link #content()} returns the same array, for the same reason
 */
public record GeneratedFile(String key, String name, byte[] content) {

    static final String KEY_PATTERN = "[a-zA-Z][A-Za-z0-9._-]{0,63}";
    public static final Pattern KEY = Pattern.compile(KEY_PATTERN);

    public GeneratedFile {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("A generated file's key must match " + KEY_PATTERN);
        }
        Objects.requireNonNull(content, "content must not be null");
        if (content.length == 0) {
            throw new IllegalArgumentException("Generated file " + key + " is empty");
        }
        name = name == null || name.isBlank() ? key : name;
    }

    /** The number of bytes, without copying them. */
    public int size() {
        return content.length;
    }

    /** The bytes as a stream, without copying them: the platform stores large files without a second copy. */
    public InputStream open() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GeneratedFile that && key.equals(that.key) && name.equals(that.name)
            && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, name, Arrays.hashCode(content));
    }

    /** The key and size only: the name and the content may be personal. */
    @Override
    public String toString() {
        return "GeneratedFile[key=" + key + ", size=" + content.length + "]";
    }
}
