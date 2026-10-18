package com.jabiz.external;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a job type declares of its input size and files, and how results carry files (decision D50). */
class ExternalJobFilesTest {

    record In(String id) {}

    record Out(String text) {}

    static final class Handler implements ExternalJobHandler<In, Out> {
        @Override
        public ExternalJobResult<Out> run(In input, ExternalJobContext ctx) {
            return ExternalJobResult.of(new Out(input.id()));
        }
    }

    private static ExternalJobType<In, Out> type(java.util.function.Consumer<ExternalJobType.Builder<In, Out>> more) {
        return ExternalJobType.define("it.files", In.class, Out.class, t -> {
            t.handler(Handler.class).onCompleted("A").onFailed("B");
            more.accept(t);
        });
    }

    @Test
    void theInputLimitIsDeclaredPerTypeWithinBounds() {
        assertThat(type(t -> { }).maxInputBytes()).isEqualTo(64 * 1024);
        ExternalJobType<In, Out> large = type(t -> t.maxInputSize(256 * 1024));
        assertThat(large.maxInputBytes()).isEqualTo(256 * 1024);
        assertThat(large.problems()).isEmpty();
        assertThat(large.toString()).contains("maxInputBytes=262144");
        assertThat(type(t -> t.maxInputSize(ExternalJobType.MAX_INPUT_BYTES)).problems()).isEmpty();
        assertThat(type(t -> t.maxInputSize(ExternalJobType.MAX_INPUT_BYTES + 1)).problems())
            .containsExactly("maxInputSize: must be between 1024 and 1048576 bytes");
        assertThat(type(t -> t.maxInputSize(1023)).problems())
            .containsExactly("maxInputSize: must be between 1024 and 1048576 bytes");
    }

    @Test
    void filesAreDeclaredWithTheirPoliciesAndLimits() {
        ExternalJobType<In, Out> type = type(t -> t.inputFiles(3, 10 * 1024 * 1024, "a.pdf", "a.image")
            .outputFiles("a.image", 2, 5 * 1024 * 1024));
        assertThat(type.inputFiles()).isEqualTo(new ExternalJobType.InputFiles(Set.of("a.pdf", "a.image"), 3,
            10 * 1024 * 1024));
        assertThat(type.inputFiles().policies()).containsExactly("a.pdf", "a.image");
        assertThat(type.outputFiles()).isEqualTo(new ExternalJobType.OutputFiles("a.image", 2, 5 * 1024 * 1024));
        assertThat(type.problems()).isEmpty();
        assertThat(type(t -> { }).inputFiles()).isNull();
        assertThat(type(t -> { }).outputFiles()).isNull();
    }

    @Test
    void unsoundFileDeclarationsAreReportedAtOnce() {
        ExternalJobType<In, Out> type = type(t -> t.inputFiles(0, ExternalJobType.MAX_INPUT_FILE_BYTES + 1)
            .outputFiles(" ", 21, 0));
        assertThat(type.problems()).containsExactlyInAnyOrder(
            "inputFiles: name the file policies the files may be of",
            "inputFiles: the most files must be between 1 and 20",
            "inputFiles: the largest file must be between 1 and 104857600 bytes",
            "outputFiles: name the file policy the files are stored under",
            "outputFiles: the most files must be between 1 and 20",
            "outputFiles: the largest file must be between 1 and 52428800 bytes");
        assertThat(type(t -> t.inputFiles(1, 1, " ")).problems()).containsExactly("inputFiles: a policy name is blank");
        assertThat(type(t -> t.outputFiles("p", 1, ExternalJobType.MAX_OUTPUT_FILE_BYTES + 1)).problems())
            .containsExactly("outputFiles: the largest file must be between 1 and 52428800 bytes");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void theDeclarationOfBeforeKeepsItsConstructor() {
        ExternalJobType old = new ExternalJobType("it.old", In.class, Out.class, Handler.class, Set.of(),
            Duration.ofSeconds(30), 3, Duration.ofSeconds(1), Duration.ofSeconds(2), 1, "A", "B", 5, false);
        assertThat(old.maxInputBytes()).isEqualTo(ExternalJobType.DEFAULT_MAX_INPUT_BYTES);
        assertThat(old.inputFiles()).isNull();
        assertThat(old.outputFiles()).isNull();
        assertThat(old.problems()).isEmpty();
    }

    @Test
    void resultsCarryFilesByKey() {
        byte[] content = {1, 2, 3};
        ExternalJobResult<Out> result = ExternalJobResult.of(new Out("x")).usage("tokens", 2)
            .file("cover", "Cover.png", content).file("thumb", null, new byte[] {4});
        content[0] = 9;
        assertThat(result.files()).extracting(GeneratedFile::key).containsExactly("cover", "thumb");
        assertThat(result.files().getFirst().content()).containsExactly(1, 2, 3);
        assertThat(result.files().getFirst().name()).isEqualTo("Cover.png");
        assertThat(result.files().get(1).name()).isEqualTo("thumb");
        assertThat(result.files().getFirst().size()).isEqualTo(3);
        assertThat(result.usage()).containsEntry("tokens", 2L);
        assertThat(result.toString()).contains("cover", "size=3").doesNotContain("Cover.png");
        assertThat(result.usage("more", 1).files()).hasSize(2);
        assertThat(new ExternalJobResult<>(new Out("x"), Map.of()).files()).isEmpty();
        assertThatThrownBy(() -> result.file("cover", null, new byte[] {1}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cover");
        assertThatThrownBy(() -> result.file("../etc", null, new byte[] {1}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.file("empty", null, new byte[0]))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new GeneratedFile("a", "n", new byte[] {1})).isEqualTo(new GeneratedFile("a", "n", new byte[] {1}))
            .hasSameHashCodeAs(new GeneratedFile("a", "n", new byte[] {1}))
            .isNotEqualTo(new GeneratedFile("a", "n", new byte[] {2}));
    }

    @Test
    void completionsHandOverTheFilesByKey() {
        UUID id = UUID.randomUUID();
        ExternalJobCompletion completion = new ExternalJobCompletion("j1", "t", "u1", "ref", new Out("x"), null, 1,
            Instant.EPOCH, Map.of("cover", id));
        assertThat(completion.file("cover")).isEqualTo(id);
        assertThat(completion.toString()).contains("cover").doesNotContain(id.toString());
        assertThatThrownBy(() -> completion.file("other")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ExternalJobCompletion("j1", "t", "u1", null, new Out("x"), null, 1, Instant.EPOCH).files())
            .isEmpty();
    }

    @Test
    void aHandlerReadsOnlyTheFilesOfItsJob() {
        UUID given = UUID.randomUUID();
        ExternalJobFile file = new ExternalJobFile() {
            @Override
            public UUID fileId() {
                return given;
            }

            @Override
            public String contentType() {
                return "application/pdf";
            }

            @Override
            public long sizeBytes() {
                return 2;
            }

            @Override
            public InputStream open() {
                return new ByteArrayInputStream(new byte[] {7, 8});
            }
        };
        ExternalJobContext ctx = new ExternalJobContext() {
            @Override
            public String jobId() {
                return "j1";
            }

            @Override
            public int attempt() {
                return 1;
            }

            @Override
            public String idempotencyKey() {
                return "k";
            }

            @Override
            public Instant deadline() {
                return Instant.EPOCH;
            }

            @Override
            public ExternalHttp http(String service) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String secret(String service, String name) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void recordUsage(String metric, long quantity) {
            }

            @Override
            public List<ExternalJobFile> files() {
                return List.of(file);
            }
        };
        assertThat(ctx.file(given).bytes()).containsExactly(7, 8);
        assertThatThrownBy(() -> ctx.file(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("was not given");
    }
}
