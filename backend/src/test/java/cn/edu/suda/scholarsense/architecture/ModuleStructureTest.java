package cn.edu.suda.scholarsense.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ModuleStructureTest {

    private static final Set<String> MODULES = Set.of(
            "identityaccess",
            "subjectregistry",
            "ingestionquality",
            "rulegovernance",
            "signalevaluation",
            "cluecare",
            "collaboration",
            "reporting",
            "auditoperations");

    private static final Set<String> LAYERS = Set.of("api", "domain", "application", "adapters");
    private static final Set<String> SHARED_KERNELS = Set.of(
            "id", "time", "error", "trace", "outbox", "observability");

    @Test
    void allBusinessModulesDeclareEveryBoundary() {
        Path root = mainPackageRoot();
        for (String module : MODULES) {
            for (String layer : LAYERS) {
                Path descriptor = root.resolve(module).resolve(layer).resolve("package-info.java");
                assertTrue(Files.isRegularFile(descriptor), () -> "Missing module boundary: " + descriptor);
            }
        }
    }

    @Test
    void sharedContainsOnlyApprovedTechnicalKernels() throws IOException {
        Path shared = mainPackageRoot().resolve("shared");
        assertTrue(Files.isDirectory(shared), "Missing shared technical kernel");
        Set<String> actual = new TreeSet<>();
        try (var children = Files.list(shared)) {
            children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .forEach(actual::add);
        }
        assertEquals(new TreeSet<>(SHARED_KERNELS), actual);
        for (String kernel : SHARED_KERNELS) {
            assertTrue(Files.isRegularFile(shared.resolve(kernel).resolve("package-info.java")),
                    () -> "Missing shared kernel descriptor: " + kernel);
        }
    }

    @Test
    void publicIntegrationProductionKernelContainsOnlyTransportNeutralValues() throws IOException {
        Path outbox = mainPackageRoot().resolve("shared/outbox");
        assertTrue(Files.isRegularFile(outbox.resolve("DeliveryRecordKey.java")));
        assertTrue(Files.isRegularFile(outbox.resolve("DeliveryStatus.java")));

        List<String> forbiddenProductionTypes = List.of(
                "QueuedDelivery",
                "CurrentDelivery",
                "ProviderLineage",
                "SourceTerminalFence",
                "LaneCutoverFence",
                "PublicIntegrationHttp",
                "PublicIntegrationJdbc");
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            List<String> paths = sources
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();
            for (String forbidden : forbiddenProductionTypes) {
                assertTrue(paths.stream().noneMatch(name -> name.contains(forbidden)),
                        () -> "test-scope PIC type leaked into production: " + forbidden);
            }
        }
    }

    @Test
    void publicIntegrationReferenceAdapterDoesNotImportBusinessInternals() throws IOException {
        Path fixtureRoot = Path.of(
                "src/test/java/cn/edu/suda/scholarsense/contractfixture/publicintegration");
        assertTrue(Files.isDirectory(fixtureRoot));
        try (var sources = Files.walk(fixtureRoot)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source);
                assertTrue(content.lines().noneMatch(line -> line.matches(
                                "import cn\\.edu\\.suda\\.scholarsense\\.(?:identityaccess|subjectregistry|ingestionquality|rulegovernance|signalevaluation|cluecare|collaboration|reporting|auditoperations)\\.(?:domain|application|adapters)\\..*")),
                        () -> "reference adapter imports a business internal package: " + source);
            }
        }
    }

    private Path mainPackageRoot() {
        return Path.of("src/main/java/cn/edu/suda/scholarsense");
    }
}
