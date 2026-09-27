package com.jabiz.runtime.file;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.file.FileKind;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.StorageEngineBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileChecksTest {

    @TempDir
    Path temp;

    private static final EntityDefinition SERVED = EntityDefinition.define("Served", eb -> {
        eb.physicalTable("served");
        eb.primaryKey("id");
        eb.field("id", f -> f.physicalColumn("id").required(true).asSemanticIdentity("urn:served"));
        eb.field("scan", f -> f.physicalColumn("scan").kind(FileKind.of("known")));
        eb.field("photo", f -> f.physicalColumn("photo").kind(FileKind.of("unknown")));
    });

    private static final EntityDefinition UNSERVED = EntityDefinition.define("Unserved", eb -> {
        eb.physicalTable("unserved");
        eb.primaryKey("id");
        eb.field("id", f -> f.physicalColumn("id").required(true).asSemanticIdentity("urn:unserved"));
        eb.field("scan", f -> f.physicalColumn("scan").kind(FileKind.of("known")));
    });

    private static FilePolicy policy(String name, long maxBytes) {
        return FilePolicy.define(name).allow(MediaTypes.PDF).maxBytes(maxBytes).permissions("u", "r").build();
    }

    private static List<String> problems(String root, Object... policies) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("served", SERVED);
        factory.registerSingleton("unserved", UNSERVED);
        factory.registerSingleton("dataset", DatasetDefinition.define("urn:ds:Served", d -> d
            .targetEntityType("Served").asDefault().permissions("r", "w")
            .storage(s -> s.connectionPoolRef("default"))));
        StorageEngine engine = (StorageEngine) Proxy.newProxyInstance(StorageEngine.class.getClassLoader(),
            new Class<?>[] {StorageEngine.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException();
            });
        factory.registerSingleton("engine", new StorageEngineBinding("default", engine));
        for (int i = 0; i < policies.length; i++) {
            factory.registerSingleton("policy" + i, policies[i]);
        }
        EntityDefinitionRegistry entities = new EntityDefinitionRegistry(factory.getBeanProvider(EntityDefinition.class));
        DatasetRegistry datasets = new DatasetRegistry(factory.getBeanProvider(DatasetDefinition.class), entities,
            new StorageAdapterRegistry(factory.getBeanProvider(StorageEngineBinding.class)), new MockEnvironment());
        FileProperties properties = new FileProperties(DataSize.ofMegabytes(1), null, null, null, null, null, null,
            new FileProperties.Local(root));
        return new FileChecks(new FilePolicyRegistry(factory.getBeanProvider(FilePolicy.class)), entities, datasets,
            properties).check().stream().map(CheckProblem::format).toList();
    }

    @Test
    void reportsEveryProblemAtOnce() throws Exception {
        Path notADirectory = Files.writeString(temp.resolve("file"), "x");
        List<String> problems = problems(notADirectory.toString(), policy("known", 1024),
            policy("known", 2048), policy("huge", 10L * 1024 * 1024));
        assertThat(problems).hasSize(5).allSatisfy(p -> assertThat(p).startsWith("FILE | "));
        assertThat(String.join("\n", problems))
            .contains("FilePolicy known | declared more than once")
            .contains("FilePolicy huge | maxBytes 10485760 exceeds")
            .contains("Served.photo | file policy unknown is not declared")
            .contains("Unserved.scan | no dataset serves Unserved")
            .contains("jabiz.files.local.root | storage directory");
    }

    @Test
    void needsAStorageDirectoryOnlyWithPolicies() {
        assertThat(problems(null, policy("known", 1024), policy("unknown", 1024)))
            .anySatisfy(p -> assertThat(p).contains("no storage directory is configured"));
        assertThat(problems(temp.resolve("new").toString(), policy("known", 1024), policy("unknown", 1024)))
            .containsExactly("FILE | Unserved.scan | no dataset serves Unserved, so references to files cannot be "
                + "found");
        assertThat(temp.resolve("new")).isDirectory();
        assertThat(problems(null)).noneMatch(p -> p.contains("storage directory"));
    }

    @Test
    void completesTheExportOfFileFields() {
        FilePolicy photos = FilePolicy.define("photos").allow(MediaTypes.JPEG).maxBytes(5)
            .image(i -> i.variants(320)).permissions("u", "r").build();
        Map<String, Object> field = new java.util.LinkedHashMap<>();
        FilePolicyExport.describe(field, photos);
        assertThat(field).containsEntry("accept", List.of("image/jpeg", ".jpg", ".jpeg"))
            .containsEntry("maxBytes", 5L).containsEntry("image", true).containsEntry("variants", List.of("w320"));
    }
}
