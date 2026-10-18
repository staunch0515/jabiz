package com.jabiz.app.archfixture;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.runtime.file.FileKeys;
import com.jabiz.runtime.file.FileStore;
import com.jabiz.runtime.file.FileUploadService;

import java.util.UUID;

/**
 * Handlers that go around their job's files (decision D50), one way per class: the counter-examples of
 * {@code ArchitectureTest}'s file rule. Test sources only, so the real check never sees them.
 */
public final class FileAccess {

    private FileAccess() {}

    public record In(String fileId) {}

    public record Out(int size) {}

    /** Reads any file from the platform's storage, not only those its job was given. */
    public static final class HandlerReadingTheStore implements ExternalJobHandler<In, Out> {
        private FileStore store;

        @Override
        public ExternalJobResult<Out> run(In input, ExternalJobContext ctx) {
            store.size(FileKeys.key(UUID.fromString(input.fileId()), FileKeys.ORIGINAL));
            return ExternalJobResult.of(new Out(0));
        }
    }

    /** Stores a file itself instead of returning it with its result. */
    public static final class HandlerStoringFiles implements ExternalJobHandler<In, Out> {
        private FileUploadService uploads;

        @Override
        public ExternalJobResult<Out> run(In input, ExternalJobContext ctx) {
            uploads.storeGenerated("any", "x", 1, () -> new java.io.ByteArrayInputStream(new byte[] {1}), ctx.jobId());
            return ExternalJobResult.of(new Out(1));
        }
    }

    /** Reads the files its job was given, as it should. */
    public static final class HandlerReadingItsFiles implements ExternalJobHandler<In, Out> {
        @Override
        public ExternalJobResult<Out> run(In input, ExternalJobContext ctx) {
            return ExternalJobResult.of(new Out(ctx.file(UUID.fromString(input.fileId())).bytes().length));
        }
    }
}
