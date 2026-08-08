package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Contract harness only; it deliberately does not claim a production downstream consumer. */
class DeferredSubjectConsumerConformanceTest {

    @Test
    void plannedSignalAndClueConsumersProveAppendSupersedeAndDedupWithoutRuntimeClaim()
            throws Exception {
        JsonNode registry = new ObjectMapper().readTree(Files.readString(Path.of(
                "../contracts/events/subject-registry/consumer-registry-1.0.0.json")));
        List<JsonNode> planned = new ArrayList<>();
        registry.get("consumers").forEach(consumer -> {
            if ("planned".equals(consumer.get("lifecycle").asText())) planned.add(consumer);
        });
        assertEquals(Set.of("signal-evaluation", "clue-care"), planned.stream()
                .map(item -> item.get("ownerModule").asText()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(planned.stream().allMatch(
                item -> "none".equals(item.get("runtimeEvidenceClaim").asText())));

        AppendOnlyConformanceLedger signal = new AppendOnlyConformanceLedger("evaluation-v1");
        AppendOnlyConformanceLedger clue = new AppendOnlyConformanceLedger("clue-v1");
        for (AppendOnlyConformanceLedger ledger : List.of(signal, clue)) {
            assertTrue(ledger.apply("event-v2", List.of("subject-a", "subject-b")));
            assertTrue(!ledger.apply("event-v2", List.of("subject-a", "subject-b")));
            assertEquals("evaluation-v1".equals(ledger.originalId()) ? "evaluation-v1" : "clue-v1",
                    ledger.facts().getFirst().id());
            assertEquals(3, ledger.facts().size());
            assertTrue(ledger.facts().subList(1, 3).stream()
                    .allMatch(fact -> fact.supersedesId().equals(ledger.originalId())));
        }
    }

    private static final class AppendOnlyConformanceLedger {
        private final String originalId;
        private final Set<String> events = new HashSet<>();
        private final List<Fact> facts = new ArrayList<>();

        private AppendOnlyConformanceLedger(String originalId) {
            this.originalId = originalId;
            facts.add(new Fact(originalId, null));
        }

        private boolean apply(String eventId, List<String> targets) {
            if (!events.add(eventId)) return false;
            for (String target : targets) {
                facts.add(new Fact(eventId + ":" + target, originalId));
            }
            return true;
        }

        private String originalId() { return originalId; }
        private List<Fact> facts() { return List.copyOf(facts); }
    }

    private record Fact(String id, String supersedesId) {}
}
