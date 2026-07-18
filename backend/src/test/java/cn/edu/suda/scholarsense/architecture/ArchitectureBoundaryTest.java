package cn.edu.suda.scholarsense.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class ArchitectureBoundaryTest {

    @Test
    void productionSourcesAndCompiledClassesRespectBoundaries() throws Exception {
        GuardRun run = runGuard(Path.of("src/main/java"), Path.of("target/classes"));
        assertEquals(0, run.exitCode(), run.output());
        assertTrue(run.output().contains("architecture-guard: PASS"));
    }

    @Test
    void deliberatelyInvalidFixturesExitNonZeroWithStableReason() throws Exception {
        assertRejected("boundary-inversion", "DOMAIN_TECHNOLOGY_DEPENDENCY");
        assertRejected("cross-module-internal", "CROSS_MODULE_INTERNAL_ACCESS");
        assertRejected("module-cycle", "MODULE_CYCLE");
        assertRejected("shared-pollution", "SHARED_BUSINESS_POLLUTION");
        assertRejected("unregistered-top-level", "TOP_LEVEL_PACKAGE_NOT_APPROVED");
        assertRejected("api-domain-bypass", "LAYER_DIRECTION_REVERSED");
        assertRejected("domain-javax-sql", "DOMAIN_TECHNOLOGY_DEPENDENCY");
        assertRejected("domain-hibernate", "DOMAIN_TECHNOLOGY_DEPENDENCY");
        assertRejected("domain-classic-http", "DOMAIN_TECHNOLOGY_DEPENDENCY");
        assertRejected("shared-qualified-business", "SHARED_DEPENDS_ON_BUSINESS_MODULE");
        assertRejected("comment-package-bypass", "TOP_LEVEL_PACKAGE_NOT_APPROVED");
        assertRejected("root-package-bypass", "ROOT_PACKAGE_TYPE_NOT_APPROVED");
    }

    @Test
    void compiledClassesRecheckLayerCrossModuleCycleAndSharedBoundaries() throws Exception {
        Path source = Path.of("src/test/resources/architecture/fixtures/compiled-boundaries/src/main/java");
        Path classes = Files.createTempDirectory("compiled-boundaries-");
        try {
            String[] arguments;
            try (var walk = Files.walk(source)) {
                var sources = walk.filter(path -> path.toString().endsWith(".java"))
                        .sorted(Comparator.comparing(Path::toString))
                        .map(Path::toString)
                        .toList();
                arguments = new String[4 + sources.size()];
                arguments[0] = "--release";
                arguments[1] = "25";
                arguments[2] = "-d";
                arguments[3] = classes.toString();
                for (int index = 0; index < sources.size(); index++) {
                    arguments[4 + index] = sources.get(index);
                }
            }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments));

            GuardRun run = runGuard(Path.of("src/main/java"), classes);
            assertNotEquals(0, run.exitCode(), run.output());
            for (String reason : new String[] {
                    "COMPILED_LAYER_DIRECTION_REVERSED",
                    "COMPILED_CROSS_MODULE_INTERNAL_ACCESS",
                    "COMPILED_MODULE_CYCLE",
                    "COMPILED_SHARED_DEPENDS_ON_BUSINESS_MODULE",
                    "COMPILED_ROOT_ENTRY_DEPENDENCY_NOT_APPROVED"
            }) {
                assertTrue(run.output().contains(reason), () -> "missing " + reason + ":\n" + run.output());
            }
        } finally {
            try (var walk = Files.walk(classes)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void assertRejected(String fixture, String reason) throws Exception {
        Path source = Path.of("src/test/resources/architecture/fixtures")
                .resolve(fixture)
                .resolve("src/main/java");
        GuardRun run = runGuard(source, null);
        assertNotEquals(0, run.exitCode(), () -> fixture + " unexpectedly passed");
        assertTrue(run.output().contains(reason), () -> fixture + " did not report " + reason + ":\n" + run.output());
    }

    private GuardRun runGuard(Path sourceRoot, Path classesRoot) throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                ArchitectureGuardMain.class.getName(),
                sourceRoot.toString());
        if (classesRoot != null) {
            builder.command().add(classesRoot.toString());
        }
        Process process = builder.redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GuardRun(process.waitFor(), output);
    }

    private record GuardRun(int exitCode, String output) {}
}
