package cn.edu.suda.scholarsense.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final Set<String> SHARED_KERNELS = Set.of("id", "time", "error", "trace", "outbox");

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

    private Path mainPackageRoot() {
        return Path.of("src/main/java/cn/edu/suda/scholarsense");
    }
}
