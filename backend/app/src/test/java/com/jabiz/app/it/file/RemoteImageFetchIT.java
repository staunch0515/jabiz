package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.ProductImageLink;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.net.PublicAddresses;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.file.RemoteFetchNetwork;
import com.jabiz.runtime.file.RemoteImageFetcher;
import com.jabiz.runtime.process.steps.FetchRemoteImage;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.HttpsStubServer;
import com.jabiz.runtime.test.HttpsStubServer.Answer;
import com.jabiz.security.MaskedLink;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.net.ssl.SSLException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Images downloaded from links (docs/design/14-files.md section 12, decision D75), against HTTPS stubs on this machine.
 * The test configuration replaces the network: names under {@code fetch.test} resolve to the stub on
 * {@code 127.0.0.1} (or as a test scripts them), only that address of the loopback range counts as public, and the
 * stub's certificate is trusted. A second stub on {@code 127.0.0.2} stands for a private service: it must never see a
 * connection.
 */
class RemoteImageFetchIT extends FileItSupport {

    static final InetAddress STUB_ADDRESS = address("127.0.0.1");
    static final InetAddress PRIVATE_ADDRESS = address("127.0.0.2");
    static final HttpsStubServer STUB = HttpsStubServer.start(STUB_ADDRESS);
    static final HttpsStubServer PRIVATE = HttpsStubServer.start(PRIVATE_ADDRESS);

    static final String POLICY = "it.fetch";
    static final int MAX_BYTES = 64 * 1024;

    /** Answers of the test resolver, by host, one per resolution; then {@code fetch.test} names give the stub. */
    static final Map<String, Deque<List<InetAddress>>> DNS = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> RESOLVED = new ConcurrentHashMap<>();

    record Seen(String name, Map<String, String> tags) {}

    static final List<Seen> SEEN = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void fetching(DynamicPropertyRegistry registry) {
        registry.add("jabiz.files.fetch.allowed-ports", () -> "443," + STUB.port() + "," + PRIVATE.port());
        registry.add("jabiz.files.fetch.connect-timeout", () -> "2s");
        registry.add("jabiz.files.fetch.read-timeout", () -> "1s");
        registry.add("jabiz.files.fetch.deadline", () -> "3s");
        registry.add("jabiz.process.rate-limits.FILE_FETCH.per-user", () -> "1000");
        registry.add("jabiz.process.rate-limits.FILE_FETCH.per-host", () -> "3");
        registry.add("jabiz.process.rate-limits.FILE_FETCH.per-instance", () -> "1000");
    }

    @AfterAll
    static void stopStubs() {
        STUB.close();
        PRIVATE.close();
    }

    @TestConfiguration
    static class Network {

        /** The network of this test: a scripted resolver, the stub's address counted public, its certificate trusted. */
        @Bean
        @Primary
        RemoteFetchNetwork testNetwork() throws SSLException {
            return new RemoteFetchNetwork(RemoteImageFetchIT::resolve,
                address -> address.equals(STUB_ADDRESS) || PublicAddresses.isPublic(address),
                SslContextBuilder.forClient().trustManager(HttpsStubServer.certificate()).build());
        }

        @Bean
        FilePolicy itFetchPolicy() {
            return FilePolicy.define(POLICY)
                .allow(MediaTypes.JPEG, MediaTypes.PNG, MediaTypes.PDF)
                .maxBytes(MAX_BYTES)
                .image(i -> i.maxPixels(4_000_000).variants(16))
                .permissions("it.fetch.upload", "it.fetch.read")
                .build();
        }

        record FetchInput(@MaskedLink String imageLink) {}

        @Bean
        ProcessDefinition<FetchInput, FileProcesses.FileInfo, ProcessContext> itFetchProcess() {
            return ProcessDefinition.define("IT_FETCH", 1, FetchInput.class, FileProcesses.FileInfo.class,
                ProcessContext.class, pb -> pb
                    .permissions("it.fetch.run")
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("link", input.imageLink());
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get("file", FileProcesses.FileInfo.class))
                    .step("Fetch", FetchRemoteImage.<ProcessContext>of(POLICY, ctx -> ctx.get("link", String.class))
                        .into("file")));
        }

        @Bean
        ObservationHandler<Observation.Context> fetchObservations() {
            return new ObservationHandler<>() {
                @Override
                public void onStop(Observation.Context context) {
                    Map<String, String> tags = new TreeMap<>();
                    for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                        tags.put(kv.getKey(), kv.getValue());
                    }
                    SEEN.add(new Seen(context.getName(), tags));
                }

                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }
            };
        }
    }

    static List<InetAddress> resolve(String host) throws UnknownHostException {
        RESOLVED.computeIfAbsent(host, h -> new AtomicInteger()).incrementAndGet();
        Deque<List<InetAddress>> scripted = DNS.get(host);
        if (scripted != null && !scripted.isEmpty()) {
            return scripted.poll();
        }
        if (host.endsWith(".fetch.test") || host.equals("other.test")) {
            return List.of(STUB_ADDRESS);
        }
        throw new UnknownHostException(host);
    }

    // ---- the pipeline ---------------------------------------------------------------------------------------------

    @Test
    void anImageIsDownloadedOnceAndStoredAsTheRequestersUploadWithoutItsMetadata() {
        String path = "/" + unique() + "/photo.jpg";
        STUB.script(path, Answer.of(200, FileSamples.jpegWithExif(40, 20, 6)).header("Content-Type", "image/jpeg")
            .header("Set-Cookie", "tracker=1"));
        String host = host();
        String link = "https://" + host + ":" + STUB.port() + path + "?sig=LINK-SECRET-42";

        Map<String, Object> file = output(fetch(link, requester()));
        String fileId = (String) file.get("fileId");
        assertThat(file).containsEntry("policy", POLICY).containsEntry("contentType", "image/jpeg")
            .containsEntry("width", 20).containsEntry("height", 40);

        // Uploaded by the requester; the stored original was re-encoded without the EXIF block (GPS included).
        assertThat(query("SELECT uploaded_by, policy, original_name FROM sys_file WHERE file_id = CAST(? AS uuid)",
            fileId)).singleElement().satisfies(row -> assertThat(row).containsEntry("uploaded_by", "it-fetcher")
                .containsEntry("policy", POLICY).containsEntry("original_name", "image"));
        byte[] original = get("/api/files/" + fileId + "/content", bearer("it-reader", "it.fetch.read"))
            .expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(FileSamples.contains(original, FileSamples.SECRET_MARKER.getBytes(StandardCharsets.US_ASCII)))
            .isFalse();
        assertThat(FileSamples.contains(original, "Exif".getBytes(StandardCharsets.US_ASCII))).isFalse();

        // One request, with fixed headers only: nothing of the requester, no cookie, no credentials, no referrer.
        HttpsStubServer.Received request = STUB.received(path).getFirst();
        assertThat(STUB.received(path)).hasSize(1);
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.target()).isEqualTo(path + "?sig=LINK-SECRET-42");
        assertThat(request.local()).isEqualTo(STUB_ADDRESS);
        assertThat(request.headers().keySet()).containsExactlyInAnyOrder("host", "user-agent", "accept",
            "connection");
        assertThat(request.header("connection")).isEqualTo("close");
        assertThat(request.header("host")).isEqualTo(host + ":" + STUB.port());
        assertThat(request.header("user-agent")).isEqualTo(RemoteImageFetcher.USER_AGENT);
        assertThat(RESOLVED.get(host)).hasValue(1);

        // The link is recorded without its query, by the business process and by FILE_FETCH; never in full.
        List<Map<String, Object>> records = query("SELECT process_name, parent_seq_id, actor_id,"
            + " input_summary::text AS s FROM op_process WHERE input_summary::text LIKE ?", "%" + path + "%");
        assertThat(records).extracting(row -> row.get("process_name")).containsExactlyInAnyOrder("IT_FETCH",
            "FILE_FETCH");
        assertThat(records).allSatisfy(row -> {
            assertThat(String.valueOf(row.get("s"))).doesNotContain("LINK-SECRET").contains(path + "?…");
            assertThat(row.get("actor_id")).isEqualTo("it-fetcher");
        });
        assertThat(records).filteredOn(row -> "FILE_FETCH".equals(row.get("process_name")))
            .singleElement().satisfies(row -> assertThat(row.get("parent_seq_id")).isNotNull());
        assertThat(uploadLeftovers()).isEmpty();
    }

    @Test
    void aPngKeepsItsTypeAndLosesItsText() {
        String path = "/" + unique() + ".png";
        STUB.script(path, Answer.of(200, FileSamples.pngWithText(30, 10)).chunked());
        Map<String, Object> file = output(fetch(link(path), requester()));
        assertThat(file).containsEntry("contentType", "image/png").containsEntry("variants", List.of("w16"));
        byte[] original = get("/api/files/" + file.get("fileId") + "/content", bearer("it-reader", "it.fetch.read"))
            .expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(FileSamples.contains(original, FileSamples.SECRET_MARKER.getBytes(StandardCharsets.US_ASCII)))
            .isFalse();
    }

    @Test
    void whatIsNotAnAcceptedImageIsRefusedWhateverTheServerCallsIt() {
        Map<String, byte[]> bodies = Map.of(
            "html", FileSamples.html(), "svg", FileSamples.svg(), "pdf", FileSamples.pdf(),
            "text", "just text".getBytes(StandardCharsets.UTF_8));
        bodies.forEach((name, body) -> {
            String path = "/" + unique() + "-" + name;
            STUB.script(path, Answer.of(200, body).header("Content-Type", "image/png"));
            assertThat(refusal(fetch(link(path), requester()), 422)).as(name).isEqualTo("FILE_FETCH_NOT_IMAGE");
        });
        // An image that cannot be decoded (or would be too large decoded) is the upload's FILE_INVALID.
        String bomb = "/" + unique() + "-bomb.png";
        STUB.script(bomb, Answer.of(200, FileSamples.pngClaiming(50_000, 50_000)));
        assertThat(refusal(fetch(link(bomb), requester()), 400)).isEqualTo("FILE_INVALID");
        assertThat(uploadLeftovers()).isEmpty();
    }

    @Test
    void tooLargeImagesAreAbandonedWhateverTheirLengthSays() {
        byte[] large = new byte[MAX_BYTES * 3];
        System.arraycopy(FileSamples.jpeg(8, 8), 0, large, 0, 100);
        // An honest length is refused before the body is read.
        String declared = "/" + unique() + "-declared";
        STUB.script(declared, Answer.of(200, large));
        assertThat(refusal(fetch(link(declared), requester()), 422)).isEqualTo("FILE_FETCH_TOO_LARGE");
        // Without a length, chunked or until the connection closes, the count stops it.
        String chunked = "/" + unique() + "-chunked";
        STUB.script(chunked, Answer.of(200, large).chunked());
        assertThat(refusal(fetch(link(chunked), requester()), 422)).isEqualTo("FILE_FETCH_TOO_LARGE");
        String unbounded = "/" + unique() + "-unbounded";
        STUB.script(unbounded, Answer.of(200, large).length(-1));
        assertThat(refusal(fetch(link(unbounded), requester()), 422)).isEqualTo("FILE_FETCH_TOO_LARGE");
        // A length smaller than the body: only that many bytes are read, which are not an image.
        String lying = "/" + unique() + "-lying";
        STUB.script(lying, Answer.of(200, new byte[MAX_BYTES * 3]).length(10));
        assertThat(refusal(fetch(link(lying), requester()), 422)).isEqualTo("FILE_FETCH_NOT_IMAGE");
        assertThat(uploadLeftovers()).isEmpty();
    }

    // ---- links and addresses --------------------------------------------------------------------------------------

    @Test
    void onlyHttpsLinksToAllowedPortsAreFollowed() {
        int before = STUB.connections();
        assertThat(refusal(fetch("http://" + host() + ":" + STUB.port() + "/a.png", requester()), 422))
            .isEqualTo("FILE_FETCH_NOT_HTTPS");
        assertThat(refusal(fetch("ftp://" + host() + "/a.png", requester()), 422)).isEqualTo("FILE_FETCH_NOT_HTTPS");
        for (String link : List.of("https://" + host() + ":8443/a.png", "https://user:pw@" + host() + "/a.png",
            "https://127.1/a.png", "https://2130706433/a.png", "https://0x7f000001/a.png", "https://localhost/a.png",
            "https://" + host() + "\\@evil.example/a.png", "not a link")) {
            assertThat(refusal(fetch(link, requester()), 422)).as(link).isEqualTo("FILE_FETCH_LINK_INVALID");
        }
        assertThat(STUB.connections()).isEqualTo(before);
    }

    @Test
    void hostsOfPrivateReservedOrMetadataAddressesAreRefusedBeforeAnyConnection() {
        int before = STUB.connections() + PRIVATE.connections();
        for (String target : List.of("10.0.0.1", "192.168.1.10", "172.16.5.4", "100.64.0.1", "169.254.169.254",
            "127.0.0.2", "0.0.0.0", "fd00:ec2::254", "::1", "fe80::1", "2001:db8::1", "64:ff9b::a9fe:a9fe")) {
            String host = host();
            DNS.put(host, new ArrayDeque<>(List.<List<InetAddress>>of(List.of(address(target)))));
            assertThat(refusal(fetch("https://" + host + ":" + PRIVATE.port() + "/a.png", requester()), 422))
                .as(target).isEqualTo("FILE_FETCH_ADDRESS_NOT_PUBLIC");
        }
        // One private address among public ones is enough to refuse: another resolver might pick it.
        String mixed = host();
        DNS.put(mixed, new ArrayDeque<>(List.<List<InetAddress>>of(List.of(STUB_ADDRESS, address("10.1.2.3")))));
        assertThat(refusal(fetch("https://" + mixed + ":" + STUB.port() + "/a.png", requester()), 422))
            .isEqualTo("FILE_FETCH_ADDRESS_NOT_PUBLIC");
        // Addresses written into the link are judged the same way, in every notation the link check lets through.
        for (String link : List.of("https://169.254.169.254/latest/meta-data/", "https://[fd00:ec2::254]/",
            "https://[::1]/", "https://[::ffff:127.0.0.2]:" + PRIVATE.port() + "/", "https://127.0.0.2:"
                + PRIVATE.port() + "/", "https://[::ffff:a9fe:a9fe]/", "https://10.0.0.1/")) {
            assertThat(refusal(fetch(link, requester()), 422)).as(link).isEqualTo("FILE_FETCH_ADDRESS_NOT_PUBLIC");
        }
        assertThat(STUB.connections() + PRIVATE.connections()).isEqualTo(before);
    }

    @Test
    void everyRedirectIsCheckedAgainAndAtMostThreeAreFollowed() {
        // Three redirects, relative and absolute, are followed; cookies the server sets are not sent back.
        String dir = "/" + unique() + "/";
        STUB.script(dir + "r0", Answer.redirect(302, "r1").header("Set-Cookie", "session=abc"));
        STUB.script(dir + "r1", Answer.redirect(301, dir + "r2?x=1"));
        STUB.script(dir + "r2", Answer.redirect(307, "https://" + host() + ":" + STUB.port() + dir + "r3"));
        STUB.script(dir + "r3", Answer.of(200, FileSamples.jpeg(4, 4)));
        assertThat(output(fetch(link(dir + "r0"), requester()))).containsKey("fileId");
        assertThat(STUB.received().stream().filter(r -> r.path().startsWith(dir)).toList()).hasSize(4)
            .allSatisfy(r -> assertThat(r.header("cookie")).isNull());
        assertThat(STUB.received(dir + "r2").getFirst().target()).isEqualTo(dir + "r2?x=1");

        // A fourth is not.
        String chain = "/" + unique() + "-c";
        for (int i = 0; i < 4; i++) {
            STUB.script(chain + i, Answer.redirect(302, chain + (i + 1)));
        }
        STUB.script(chain + 4, Answer.of(200, FileSamples.jpeg(4, 4)));
        assertThat(refusal(fetch(link(chain + 0), requester()), 422)).isEqualTo("FILE_FETCH_TOO_MANY_REDIRECTS");
        assertThat(STUB.received(chain + 4)).isEmpty();

        // To plain http, to a private host, to a private address, to another port: refused, nothing connected.
        int privateBefore = PRIVATE.connections();
        String toHttp = "/" + unique();
        STUB.script(toHttp, Answer.redirect(302, "http://" + host() + ":" + STUB.port() + "/x.png"));
        assertThat(refusal(fetch(link(toHttp), requester()), 422)).isEqualTo("FILE_FETCH_NOT_HTTPS");
        String toPrivateHost = "/" + unique();
        String privateHost = host();
        DNS.put(privateHost, new ArrayDeque<>(List.<List<InetAddress>>of(List.of(PRIVATE_ADDRESS))));
        STUB.script(toPrivateHost, Answer.redirect(302, "https://" + privateHost + ":" + PRIVATE.port() + "/x.png"));
        assertThat(refusal(fetch(link(toPrivateHost), requester()), 422)).isEqualTo("FILE_FETCH_ADDRESS_NOT_PUBLIC");
        String toMetadata = "/" + unique();
        STUB.script(toMetadata, Answer.redirect(303, "https://169.254.169.254/latest/meta-data/iam"));
        assertThat(refusal(fetch(link(toMetadata), requester()), 422)).isEqualTo("FILE_FETCH_ADDRESS_NOT_PUBLIC");
        String toLoopback = "/" + unique();
        STUB.script(toLoopback, Answer.redirect(308, "https://127.0.0.2:" + PRIVATE.port() + "/x.png"));
        assertThat(refusal(fetch(link(toLoopback), requester()), 422)).isEqualTo("FILE_FETCH_ADDRESS_NOT_PUBLIC");
        String toPort = "/" + unique();
        STUB.script(toPort, Answer.redirect(302, "https://" + host() + ":22/x.png"));
        assertThat(refusal(fetch(link(toPort), requester()), 422)).isEqualTo("FILE_FETCH_LINK_INVALID");
        String nowhere = "/" + unique();
        STUB.script(nowhere, Answer.of(302, new byte[0]));
        assertThat(refusal(fetch(link(nowhere), requester()), 422)).isEqualTo("FILE_FETCH_FAILED");
        assertThat(PRIVATE.connections()).isEqualTo(privateBefore);
    }

    @Test
    void aNameIsResolvedOnceAndTheCheckedAddressIsTheOneConnectedTo() {
        // DNS rebinding: the name gives a public address when checked and a private one when asked again. The
        // connection goes to the checked address; a redirect to the same name asks again and is refused.
        String host = host();
        DNS.put(host, new ArrayDeque<>(List.of(List.of(STUB_ADDRESS), List.of(STUB_ADDRESS),
            List.of(PRIVATE_ADDRESS))));
        String path = "/" + unique();
        STUB.script(path, Answer.of(200, FileSamples.jpeg(4, 4)));
        int privateBefore = PRIVATE.connections();
        assertThat(output(fetch("https://" + host + ":" + STUB.port() + path, requester()))).containsKey("fileId");
        assertThat(STUB.received(path)).singleElement()
            .satisfies(request -> assertThat(request.local()).isEqualTo(STUB_ADDRESS));
        assertThat(RESOLVED.get(host)).hasValue(1);

        String again = "/" + unique();
        STUB.script(again, Answer.redirect(302, "/" + unique() + ".png"));
        assertThat(refusal(fetch("https://" + host + ":" + STUB.port() + again, requester()), 422))
            .isEqualTo("FILE_FETCH_ADDRESS_NOT_PUBLIC");
        assertThat(RESOLVED.get(host)).hasValue(3);
        assertThat(PRIVATE.connections()).isEqualTo(privateBefore);
    }

    @Test
    void certificatesAreVerifiedForTheLinksHost() {
        // other.test resolves to the stub, whose certificate does not name it.
        String path = "/" + unique();
        STUB.script(path, Answer.of(200, FileSamples.jpeg(4, 4)));
        assertThat(problem(fetch("https://other.test:" + STUB.port() + path, requester()), 422))
            .containsEntry("ruleCode", "FILE_FETCH_FAILED").extractingByKey("message").asString()
            .contains("no secure connection");
        assertThat(STUB.received(path)).isEmpty();
    }

    @Test
    void serversThatFailOrDoNotAnswerInTimeAreReportedSo() {
        String missing = "/" + unique();
        STUB.script(missing, Answer.of(404, "no".getBytes(StandardCharsets.UTF_8)));
        assertThat(problem(fetch(link(missing), requester()), 422)).containsEntry("ruleCode", "FILE_FETCH_FAILED")
            .extractingByKey("message").asString().contains("answered with an error");
        assertThat(problem(fetch("https://no-such-host.example:" + STUB.port() + "/a.png", requester()), 422))
            .containsEntry("ruleCode", "FILE_FETCH_FAILED").extractingByKey("message").asString()
            .contains("host was not found");
        String empty = "/" + unique();
        STUB.script(empty, Answer.of(200, new byte[0]));
        assertThat(problem(fetch(link(empty), requester()), 422)).extractingByKey("message").asString()
            .contains("sent nothing");

        // No head within the read timeout (1 s here).
        String slow = "/" + unique();
        STUB.script(slow, Answer.of(200, FileSamples.jpeg(4, 4)).after(Duration.ofMillis(1800)));
        assertThat(refusal(fetch(link(slow), requester()), 422)).isEqualTo("FILE_FETCH_TIMEOUT");
        // Bytes that keep coming, each within the read timeout, past the deadline (3 s here).
        String dripping = "/" + unique();
        STUB.script(dripping, Answer.of(200, new byte[40]).dripping(Duration.ofMillis(200)));
        assertThat(refusal(fetch(link(dripping), requester()), 422)).isEqualTo("FILE_FETCH_TIMEOUT");
        assertThat(uploadLeftovers()).isEmpty();
    }

    // ---- who may, how often, what is recorded ---------------------------------------------------------------------

    @Test
    void theRequesterNeedsThePolicysUploadPermissionAndFileFetchNeverRunsOnItsOwn() {
        String path = "/" + unique();
        STUB.script(path, Answer.of(200, FileSamples.jpeg(4, 4)));
        assertThat(fetch(link(path), bearer("it-runner", "it.fetch.run")).expectStatus().isForbidden()
            .expectBody(MAP).returnResult().getResponseBody().toString()).doesNotContain("FILE_FETCH_");
        assertThat(STUB.received(path)).isEmpty();
        assertThat(client.post().uri("/api/processes/FILE_FETCH/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-admin", "*")).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("policy", POLICY, "imageLink", link(path))).exchange()
            .expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody().toString())
            .contains("PROCESS_INTERNAL");
        assertThat(STUB.received(path)).isEmpty();
    }

    @Test
    void downloadsFromOneHostAreRateLimited() {
        String host = host();
        for (int i = 0; i < 3; i++) {
            String path = "/" + unique();
            STUB.script(path, Answer.of(200, FileSamples.jpeg(4, 4)));
            output(fetch("https://" + host + ":" + STUB.port() + path, requester()));
        }
        String path = "/" + unique();
        STUB.script(path, Answer.of(200, FileSamples.jpeg(4, 4)));
        fetch("https://" + host + ":" + STUB.port() + path, requester()).expectStatus().isEqualTo(429)
            .expectHeader().exists(HttpHeaders.RETRY_AFTER);
        assertThat(STUB.received(path)).isEmpty();
        // Another host is not held back.
        output(fetch("https://" + host() + ":" + STUB.port() + path, requester()));
    }

    @Test
    void theObservationCarriesTheOutcomeOnly() {
        String path = "/" + unique();
        STUB.script(path, Answer.of(200, FileSamples.jpeg(4, 4)));
        output(fetch(link(path), requester()));
        String host = host();
        DNS.put(host, new ArrayDeque<>(List.<List<InetAddress>>of(List.of(address("10.9.9.9")))));
        fetch("https://" + host + "/a.png", requester()).expectStatus().isEqualTo(422);
        List<Seen> fetches = SEEN.stream().filter(seen -> seen.name().equals("jabiz.file.fetch")).toList();
        assertThat(fetches).isNotEmpty().allSatisfy(seen -> assertThat(seen.tags().keySet())
            .containsExactly("outcome"));
        assertThat(fetches).extracting(seen -> seen.tags().get("outcome"))
            .contains("fetched", "FILE_FETCH_ADDRESS_NOT_PUBLIC");
        assertThat(fetches.toString()).doesNotContain(host).doesNotContain("fetch.test");
    }

    @Test
    void theSampleSetsAProductsPhotoFromALink() {
        String sku = "F" + unique().substring(0, 8).toUpperCase(Locale.ROOT);
        String admin = bearer("it-admin", "*");
        post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", admin, Map.of("changes",
            List.of(Map.of("action", "INSERT", "attributes", Map.of("sku", sku, "productName", "Product " + sku,
                "unitPrice", 100, "active", true))))).expectStatus().isOk();
        @SuppressWarnings("unchecked")
        Map<String, Object> product = ((List<Map<String, Object>>) post("/api/datasets/"
            + CommerceEntities.PRODUCT_DATASET + "/query", admin, Map.of("filter", Map.of("field", "sku", "op", "EQ",
            "value", sku))).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("items"))
            .getFirst();
        String path = "/" + unique() + ".jpg";
        STUB.script(path, Answer.of(200, FileSamples.jpegWithExif(64, 48, 1)));
        String editor = bearer("it-editor", "commerce.product.write", "commerce.media.upload");
        Map<String, Object> output = output(client.post().uri("/api/processes/"
                + ProductImageLink.PRODUCT_IMAGE_FROM_LINK + "/latest").header(HttpHeaders.AUTHORIZATION, editor)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("productId", product.get("id"),
                "imageLink", link(path) + "?token=SAMPLE-SECRET")).exchange());
        assertThat(query("SELECT uploaded_by, policy FROM sys_file WHERE file_id = CAST(? AS uuid)",
            output.get("imageFileId"))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("uploaded_by", "it-editor").containsEntry("policy", "commerce.image"));
        assertThat(query("SELECT input_summary::text AS s FROM op_process WHERE process_name = ?",
            ProductImageLink.PRODUCT_IMAGE_FROM_LINK)).singleElement()
            .satisfies(row -> assertThat(String.valueOf(row.get("s"))).doesNotContain("SAMPLE-SECRET"));
        // Without the photo upload permission, the product's editor cannot.
        assertThat(client.post().uri("/api/processes/" + ProductImageLink.PRODUCT_IMAGE_FROM_LINK + "/latest")
            .header(HttpHeaders.AUTHORIZATION, bearer("it-editor", "commerce.product.write"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("productId", product.get("id"),
                "imageLink", link(path))).exchange().expectStatus().isForbidden());
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private String requester() {
        return bearer("it-fetcher", "it.fetch.run", "it.fetch.upload");
    }

    private WebTestClient.ResponseSpec fetch(String link, String token) {
        return client.post().uri("/api/processes/IT_FETCH/latest").header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("imageLink", link)).exchange();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(WebTestClient.ResponseSpec response) {
        return (Map<String, Object>) response.expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("output");
    }

    /** The one violation's code and parameters. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> problem(WebTestClient.ResponseSpec response, int status) {
        Map<String, Object> body = response.expectStatus().isEqualTo(status).expectBody(MAP).returnResult()
            .getResponseBody();
        List<Map<String, Object>> violations = (List<Map<String, Object>>) body.get("violations");
        assertThat(violations).as(String.valueOf(body)).hasSize(1);
        assertThat(body.toString()).doesNotContain("fetch.test/").doesNotContain("SECRET");
        return violations.getFirst();
    }

    private static String refusal(WebTestClient.ResponseSpec response, int status) {
        return (String) problem(response, status).get("ruleCode");
    }

    private static String link(String path) {
        return "https://" + host() + ":" + STUB.port() + path;
    }

    /** A host of its own: the per-host limit counts each separately. */
    private static String host() {
        return "h" + unique().substring(0, 12) + ".fetch.test";
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(literal, e);
        }
    }

}
