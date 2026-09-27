package com.jabiz.file;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePolicyTest {

    private static FilePolicy.Builder valid() {
        return FilePolicy.define("commerce.image").allow(MediaTypes.JPEG, MediaTypes.PNG).maxBytes(5 * FilePolicy.MB)
            .permissions("commerce.media.upload", "commerce.media.read");
    }

    @Test
    void buildsWithImageDefaultsAndSortedVariants() {
        FilePolicy policy = valid().image(i -> i.maxPixels(1_000_000).variants(640, 320)).build();
        assertThat(policy.name()).isEqualTo("commerce.image");
        assertThat(policy.contentTypes()).containsExactly("image/jpeg", "image/png");
        assertThat(policy.allows(MediaTypes.PNG)).isTrue();
        assertThat(policy.allows(MediaTypes.PDF)).isFalse();
        assertThat(policy.acceptsImages()).isTrue();
        assertThat(policy.image().variants()).containsExactly(320, 640);
        assertThat(policy.image().maxPixels()).isEqualTo(1_000_000);

        FilePolicy defaults = valid().build();
        assertThat(defaults.image()).isEqualTo(FilePolicy.ImageOptions.DEFAULT);
        assertThat(FilePolicy.ImageOptions.variantName(320)).isEqualTo("w320");
    }

    @Test
    void documentsHaveNoImageOptions() {
        FilePolicy pdf = FilePolicy.define("commerce.document").allow(MediaTypes.PDF).maxBytes(FilePolicy.MB)
            .permissions("u", "r").build();
        assertThat(pdf.acceptsImages()).isFalse();
        assertThat(pdf.image()).isNull();
        assertThatThrownBy(() -> FilePolicy.define("commerce.document").allow(MediaTypes.PDF).maxBytes(1)
            .image(i -> i.variants(100)).permissions("u", "r").build())
            .hasMessageContaining("image options need an image type");
    }

    @Test
    void rejectsInvalidDeclarations() {
        assertThatThrownBy(() -> FilePolicy.define("Bad Name").allow(MediaTypes.PDF).maxBytes(1)
            .permissions("u", "r").build()).hasMessageContaining("must match");
        assertThatThrownBy(() -> FilePolicy.define("x".repeat(101)).allow(MediaTypes.PDF).maxBytes(1)
            .permissions("u", "r").build()).hasMessageContaining("at most");
        assertThatThrownBy(() -> FilePolicy.define("a").maxBytes(1).permissions("u", "r").build())
            .hasMessageContaining("at least one type");
        assertThatThrownBy(() -> FilePolicy.define("a").allow(MediaTypes.PDF).permissions("u", "r").build())
            .hasMessageContaining("maxBytes");
        assertThatThrownBy(() -> FilePolicy.define("a").allow(MediaTypes.PDF).maxBytes(1).build())
            .hasMessageContaining("no upload permission");
        assertThatThrownBy(() -> FilePolicy.define("a").allow(MediaTypes.PDF).maxBytes(1).permissions("u", " ")
            .build()).hasMessageContaining("no read permission");
        assertThatThrownBy(() -> valid().image(i -> i.variants(0)).build()).hasMessageContaining("between");
        assertThatThrownBy(() -> valid().image(i -> i.variants(9000)).build()).hasMessageContaining("between");
        assertThatThrownBy(() -> valid().image(i -> i.variants(100, 100)).build()).hasMessageContaining("twice");
        assertThatThrownBy(() -> valid().image(i -> i.maxPixels(0)).build()).hasMessageContaining("maxPixels");
        assertThatThrownBy(() -> new FilePolicy("a", Set.of(), 1, null, "u", "r"))
            .hasMessageContaining("at least one type");
        assertThatThrownBy(() -> new FilePolicy.ImageOptions(1, null)).isInstanceOf(NullPointerException.class);
        assertThat(new FilePolicy.ImageOptions(1, List.of()).variants()).isEmpty();
    }
}
