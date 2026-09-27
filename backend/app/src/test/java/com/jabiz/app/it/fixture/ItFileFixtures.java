package com.jabiz.app.it.fixture;

import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.file.FileKind;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * File fixtures of the integration tests (docs/design/14-files.md): an ordinary (not temporal) entity with a file
 * field, so that both write paths check files, a policy for the audio types, and a tiny policy for the size limit.
 * The table is created by {@code db/testmigration/V1003__it_files.sql}.
 */
public final class ItFileFixtures extends BaseEntityDefinitions {

    public static final String ATTACHMENT_DATASET = "urn:jabiz:dataset:it:ItAttachment";
    public static final String AUDIO = "it.audio";
    public static final String TINY = "it.tiny";
    /** Bytes the tiny policy accepts. */
    public static final long TINY_MAX_BYTES = 64 * FilePolicy.KB;

    public static final EntityDefinition ATTACHMENT = EntityDefinition.define("ItAttachment", eb -> {
        eb.physicalTable("it_attachment");
        eb.primaryKey("attachmentId");
        eb.field("attachmentId", semanticIdentity("f_id", "urn:jabiz:entity:it:attachment"));
        eb.field("title", f -> f.physicalColumn("f_title").asText(100));
        eb.field("document", f -> f.physicalColumn("f_document").kind(FileKind.of(CommerceFiles.DOCUMENT)));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    private ItFileFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        EntityDefinition itAttachmentEntity() {
            return ATTACHMENT;
        }

        @Bean
        DatasetDefinition itAttachmentDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(ATTACHMENT_DATASET, d -> d
                .targetEntityType("ItAttachment")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        FilePolicy itAudioPolicy() {
            return FilePolicy.define(AUDIO).allow(MediaTypes.MP3, MediaTypes.M4A, MediaTypes.OGG)
                .maxBytes(FilePolicy.MB).permissions("it.audio.upload", "it.audio.read").build();
        }

        @Bean
        FilePolicy itTinyPolicy() {
            return FilePolicy.define(TINY).allow(MediaTypes.PDF).maxBytes(TINY_MAX_BYTES)
                .permissions("it.tiny.upload", "it.tiny.read").build();
        }
    }
}
