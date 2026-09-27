package com.jabiz.file;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * What may be uploaded under one name and who may do it (docs/design/14-files.md section 3, decision D18): the types
 * recognised by content, the size limit, how images are processed, and the permissions to upload and to read.
 * Declared as a bean; fields refer to it by name ({@link FileKind#of}).
 *
 * @param name             unique within the application, for example {@code commerce.image}
 * @param allowed          the accepted types, in declaration order
 * @param maxBytes         the largest accepted upload
 * @param image            how images are processed; null when the policy accepts no image type
 * @param uploadPermission permission to upload under this policy
 * @param readPermission   permission to read files of this policy (besides {@code file.read})
 */
public record FilePolicy(String name, Set<MediaTypes> allowed, long maxBytes, ImageOptions image,
    String uploadPermission, String readPermission) {

    public static final long KB = 1024;
    public static final long MB = 1024 * KB;

    /** Policy names: dot-separated lower-case segments. */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*");
    public static final int MAX_NAME_LENGTH = 100;

    public FilePolicy {
        if (name == null || name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("File policy name '" + name + "' must match " + NAME.pattern()
                + " and have at most " + MAX_NAME_LENGTH + " characters");
        }
        if (allowed == null || allowed.isEmpty()) {
            throw new IllegalArgumentException("File policy " + name + ": allow at least one type");
        }
        allowed = Collections.unmodifiableSet(new LinkedHashSet<>(allowed));
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("File policy " + name + ": maxBytes must be positive");
        }
        boolean images = allowed.stream().anyMatch(MediaTypes::isImage);
        if (image == null && images) {
            image = ImageOptions.DEFAULT;
        }
        if (image != null && !images) {
            throw new IllegalArgumentException("File policy " + name + ": image options need an image type");
        }
        requirePermission(name, "upload", uploadPermission);
        requirePermission(name, "read", readPermission);
    }

    private static void requirePermission(String policy, String what, String permission) {
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("File policy " + policy + " declares no " + what
                + " permission (default deny)");
        }
    }

    public static Builder define(String name) {
        return new Builder(name);
    }

    public boolean allows(MediaTypes type) {
        return allowed.contains(type);
    }

    /** Whether the policy accepts images, which are decoded and re-encoded. */
    public boolean acceptsImages() {
        return image != null;
    }

    /** The accepted content types, in declaration order. */
    public List<String> contentTypes() {
        return allowed.stream().map(MediaTypes::contentType).toList();
    }

    /**
     * How images are processed. Images are always decoded and re-encoded, which removes every metadata block (EXIF
     * with GPS, XMP, comments); there is no switch to keep them (decision D18).
     *
     * @param maxPixels largest width x height accepted, checked from the header before decoding (decompression bombs)
     * @param variants  widths of the variants to produce, ascending; a variant wider than the image is not produced
     */
    public record ImageOptions(long maxPixels, List<Integer> variants) {

        public static final long DEFAULT_MAX_PIXELS = 40_000_000L;
        public static final ImageOptions DEFAULT = new ImageOptions(DEFAULT_MAX_PIXELS, List.of());

        /** Widest variant; wider ones would only waste storage. */
        public static final int MAX_VARIANT_WIDTH = 8192;
        /** Most variants of one policy: their names are stored together in {@code sys_file.variants}. */
        public static final int MAX_VARIANTS = 12;

        public ImageOptions {
            if (maxPixels <= 0) {
                throw new IllegalArgumentException("maxPixels must be positive");
            }
            Objects.requireNonNull(variants, "variants must not be null");
            if (variants.size() > MAX_VARIANTS) {
                throw new IllegalArgumentException("At most " + MAX_VARIANTS + " variants");
            }
            TreeSet<Integer> sorted = new TreeSet<>();
            for (Integer width : variants) {
                if (width == null || width <= 0 || width > MAX_VARIANT_WIDTH) {
                    throw new IllegalArgumentException("Variant width " + width + " must be between 1 and "
                        + MAX_VARIANT_WIDTH);
                }
                if (!sorted.add(width)) {
                    throw new IllegalArgumentException("Variant width " + width + " is declared twice");
                }
            }
            variants = List.copyOf(sorted);
        }

        /** The name of the variant of the given width, as used in URLs and storage keys. */
        public static String variantName(int width) {
            return "w" + width;
        }
    }

    public static final class ImageBuilder {
        private long maxPixels = ImageOptions.DEFAULT_MAX_PIXELS;
        private final List<Integer> variants = new ArrayList<>();

        private ImageBuilder() {}

        public ImageBuilder maxPixels(long pixels) {
            this.maxPixels = pixels;
            return this;
        }

        public ImageBuilder variants(int... widths) {
            for (int width : widths) {
                variants.add(width);
            }
            return this;
        }
    }

    public static final class Builder {
        private final String name;
        private final Set<MediaTypes> allowed = new LinkedHashSet<>();
        private long maxBytes;
        private ImageOptions image;
        private String uploadPermission;
        private String readPermission;

        private Builder(String name) {
            this.name = name;
        }

        public Builder allow(MediaTypes... types) {
            for (MediaTypes type : types) {
                allowed.add(Objects.requireNonNull(type, "type must not be null"));
            }
            return this;
        }

        public Builder maxBytes(long bytes) {
            this.maxBytes = bytes;
            return this;
        }

        public Builder image(Consumer<ImageBuilder> configuration) {
            ImageBuilder builder = new ImageBuilder();
            configuration.accept(builder);
            this.image = new ImageOptions(builder.maxPixels, builder.variants);
            return this;
        }

        /** The permissions to upload and to read files of this policy. */
        public Builder permissions(String upload, String read) {
            this.uploadPermission = upload;
            this.readPermission = read;
            return this;
        }

        public FilePolicy build() {
            return new FilePolicy(name, allowed, maxBytes, image, uploadPermission, readPermission);
        }
    }
}
