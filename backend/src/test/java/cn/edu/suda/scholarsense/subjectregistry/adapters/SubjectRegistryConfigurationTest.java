package cn.edu.suda.scholarsense.subjectregistry.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SubjectRegistryConfigurationTest {
    @Test
    void contributesOwnerMappingAndR7OnlyTechnicalJobShellEntries() {
        var capabilities = new SubjectRegistryConfiguration()
                .subjectRegistryShellCapabilities().capabilities();

        assertEquals(List.of(
                "data-quality.subject-mapping-exceptions",
                "subject-registry.recompute-jobs"),
                capabilities.stream().map(item -> item.routeName()).toList());
        assertEquals(Set.of("R6-DATA-OWNER"), capabilities.getFirst().authorizedRoleIds());
        assertEquals(
                Set.of("R7-PLATFORM-OPS"),
                capabilities.getLast().authorizedRoleIds());
    }
}
