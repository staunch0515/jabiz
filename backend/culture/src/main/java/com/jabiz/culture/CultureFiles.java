package com.jabiz.culture;

import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.culture.CultureEntities.AUDIO_POLICY;
import static com.jabiz.culture.CultureEntities.CONSENT_DOC_POLICY;
import static com.jabiz.culture.CultureEntities.IMAGE_POLICY;
import static com.jabiz.culture.CultureEntities.PDF_POLICY;

/**
 * File policies (docs/culture/00-design.md section 3.11). Images are re-encoded without metadata such as GPS by the
 * platform (docs/design/14-files.md); videos are not uploaded at all, only their provider and id are stored.
 */
@Configuration
public class CultureFiles {

    @Bean
    FilePolicy cultureImagePolicy() {
        return FilePolicy.define(IMAGE_POLICY)
            .allow(MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(15 * FilePolicy.MB)
            .image(i -> i.maxPixels(40_000_000).variants(320, 640, 1280, 1920))
            .permissions(Culture.MEDIA_UPLOAD, Culture.MEDIA_READ)
            .build();
    }

    @Bean
    FilePolicy cultureAudioPolicy() {
        return FilePolicy.define(AUDIO_POLICY)
            .allow(MediaTypes.MP3, MediaTypes.M4A, MediaTypes.OGG)
            .maxBytes(30 * FilePolicy.MB)
            .permissions(Culture.MEDIA_UPLOAD, Culture.MEDIA_READ)
            .build();
    }

    @Bean
    FilePolicy culturePdfPolicy() {
        return FilePolicy.define(PDF_POLICY)
            .allow(MediaTypes.PDF)
            .maxBytes(30 * FilePolicy.MB)
            .permissions(Culture.RESOURCE_WRITE, Culture.MEDIA_READ)
            .build();
    }

    /** Signed consent forms: readable only with the consent permission, and referenced by no public view. */
    @Bean
    FilePolicy cultureConsentDocPolicy() {
        return FilePolicy.define(CONSENT_DOC_POLICY)
            .allow(MediaTypes.PDF, MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(15 * FilePolicy.MB)
            .image(i -> i.maxPixels(40_000_000))
            .permissions(Culture.CONSENT_WRITE, Culture.CONSENT_READ)
            .build();
    }
}
