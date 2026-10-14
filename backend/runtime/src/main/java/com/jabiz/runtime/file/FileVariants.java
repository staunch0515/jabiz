package com.jabiz.runtime.file;

import com.jabiz.file.FilePolicy;
import com.jabiz.runtime.EntityInstance;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Which stored object answers a request for a variant (docs/design/14-files.md section 5), shared by the
 * authenticated and the public file endpoints. A variant {@code w<N>} is the image at most {@code N} pixels wide.
 * Images are never enlarged, so no object is stored for a width the image does not exceed (section 3): for such a
 * width of the file's policy the image at that width is the (re-encoded) original, and that is what is served.
 * Callers can therefore ask for any width of the policy without knowing the image's size. Who may read is decided
 * before this, exactly as for the original.
 */
final class FileVariants {

    private FileVariants() {}

    /**
     * The variant name of the stored object for {@code requested}: the original for {@link FileKeys#ORIGINAL}, the
     * variant itself when it was produced, the original for a width of the file's policy that is not narrower than
     * the image; empty (404) for anything else, including a width the policy does not declare and a variant added
     * to the policy after an image wider than it was uploaded (variants are made only on upload).
     */
    static Optional<String> stored(EntityInstance file, String requested, FilePolicyRegistry policies) {
        if (FileKeys.ORIGINAL.equals(requested) || produced(file).contains(requested)) {
            return Optional.of(requested);
        }
        Object width = file.get(FileEntities.WIDTH);
        if (width == null) {
            return Optional.empty();
        }
        int imageWidth = ((BigDecimal) width).intValueExact();
        return policies.find(file.get(FileEntities.POLICY))
            .filter(FilePolicy::acceptsImages)
            .flatMap(policy -> policy.image().variants().stream()
                .filter(declared -> FilePolicy.ImageOptions.variantName(declared).equals(requested))
                .filter(declared -> declared >= imageWidth)
                .findFirst())
            .map(declared -> FileKeys.ORIGINAL);
    }

    /** The variants produced on upload, narrowest first. */
    static List<String> produced(EntityInstance file) {
        String variants = file.get(FileEntities.VARIANTS);
        return variants == null || variants.isBlank() ? List.of() : Arrays.asList(variants.split(","));
    }
}
