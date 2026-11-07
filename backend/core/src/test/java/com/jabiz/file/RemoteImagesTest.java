package com.jabiz.file;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteImagesTest {

    @Test
    void imagesCompareByContentAndRefusalsCarryTheirCode() {
        RemoteImages.Image a = new RemoteImages.Image(new byte[] {1, 2}, MediaTypes.PNG);
        assertThat(a).isEqualTo(new RemoteImages.Image(new byte[] {1, 2}, MediaTypes.PNG))
            .hasSameHashCodeAs(new RemoteImages.Image(new byte[] {1, 2}, MediaTypes.PNG))
            .isNotEqualTo(new RemoteImages.Image(new byte[] {1, 2}, MediaTypes.JPEG));
        assertThat(a.toString()).isEqualTo("Image[PNG, 2 bytes]");
        RemoteImages.Refused refused = new RemoteImages.Refused("FILE_FETCH_TIMEOUT", "late", null);
        assertThat(refused.code()).isEqualTo("FILE_FETCH_TIMEOUT");
        assertThat(refused.params()).isEmpty();
        assertThat(new RemoteImages.Refused("X", "m", Map.of("a", 1)).params()).containsEntry("a", 1);
    }
}
