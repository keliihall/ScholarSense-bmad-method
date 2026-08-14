package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FrozenRuleDependencyRegistryLoaderTest {
    @Test
    void loadsOnlyTheDigestLockedFiveRuleElevenDependencyRegistry() {
        var registry = FrozenRuleDependencyRegistryLoader.load(
                Path.of("..", "contracts"), new ObjectMapper());

        assertEquals(5, registry.rules().size());
        assertEquals(11, registry.rules().stream()
                .flatMap(rule -> rule.members().stream())
                .map(member -> member.dependencyId())
                .distinct().count());
        assertEquals(
                "sha256:cd1915107c0c2657a430c2d5ba0abd420d9bd8d06f6cdcaed2a570c71ce6655a",
                registry.registryDigest());
    }
}
