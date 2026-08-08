package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.domain.HistoricalWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeIdentity;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeJob;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MappingRecomputePlannerTest {

    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");
    private static final String SUBJECT = "019fcfea-6200-7000-8000-000000000001";
    private static final UUID LINEAGE = uuid("019fcfea-6200-7000-8000-000000000010");
    private static final UUID REQUEST = uuid("019fcfea-6200-7000-8000-000000000011");
    private static final String SOURCE = "SRC-P0-CARD-001";

    @Test
    void queuesOnlyActionableApprovedWindowsAndReturnsTheOriginalJobForReplay() {
        MemoryStore store = new MemoryStore(List.of(
                window("current", NOW.plusSeconds(60), "wm-current", "personal-baseline-28d"),
                window("expired", NOW, "wm-expired", "personal-baseline-28d"),
                window("missing-boundary", null, "wm-legacy", "personal-baseline-28d"),
                window("missing-scenario", NOW.plusSeconds(60), "wm-no-scenario", null)));
        MappingRecomputePlanner planner = new MappingRecomputePlanner(store, store, store, () ->
                uuid("019fcfea-6200-7000-8000-000000000020"));

        MappingRecomputePlan first = planner.plan(
                REQUEST, LINEAGE, SOURCE, Set.of(SUBJECT), NOW,
                "00112233445566778899aabbccddeeff");
        MappingRecomputePlan replay = planner.plan(
                REQUEST, LINEAGE, SOURCE, Set.of(SUBJECT), NOW,
                "00112233445566778899aabbccddeeff");

        assertEquals(1, first.jobs().size());
        assertEquals(first.jobs().getFirst().jobId(), replay.jobs().getFirst().jobId());
        assertEquals(3, first.historyOnlyWindowCount());
        assertEquals(1, store.jobs.size());
        assertEquals(SOURCE, first.jobs().getFirst().ownerSourceId());
        assertEquals(new PlanRecord(REQUEST, SOURCE, 1, 3), store.plan);
    }

    @Test
    void newWatermarkCreatesASuccessorIdentityRatherThanOverwritingTheOriginal() {
        MemoryStore store = new MemoryStore(List.of(window(
                "current", NOW.plusSeconds(60), "wm-current", "personal-baseline-28d")));
        List<UUID> ids = new ArrayList<>(List.of(
                uuid("019fcfea-6200-7000-8000-000000000020"),
                uuid("019fcfea-6200-7000-8000-000000000021")));
        MappingRecomputePlanner planner = new MappingRecomputePlanner(
                store, store, store, () -> ids.removeFirst());
        planner.plan(REQUEST, LINEAGE, SOURCE, Set.of(SUBJECT), NOW,
                "00112233445566778899aabbccddeeff");
        store.windows = List.of(window(
                "current", NOW.plusSeconds(60), "wm-new", "personal-baseline-28d"));

        MappingRecomputePlan successor = planner.plan(
                REQUEST, LINEAGE, SOURCE, Set.of(SUBJECT), NOW,
                "00112233445566778899aabbccddeeff");

        assertEquals(2, store.jobs.size());
        assertTrue(store.jobs.containsKey(successor.jobs().getFirst().identity()));
    }

    private static HistoricalWindow window(
            String id, Instant latestActionableAt, String watermark, String scenarioId) {
        return new HistoricalWindow(
                SUBJECT, id, NOW.minusSeconds(28L * 86400), NOW,
                ZoneId.of("Asia/Shanghai"), Map.of("SRC-P0-CARD-001", 3L),
                Map.of("SRC-P0-CARD-001", watermark), 7, List.of("QG-1.0.0"),
                "personal-baseline", "1.2.0", scenarioId,
                uuid("019fcfea-6200-7000-8000-000000000030"),
                "sha256:" + "a".repeat(64), latestActionableAt);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static final class MemoryStore implements
            HistoricalWindowPort, MappingRecomputeJobPort, MappingRecomputePlanPort {
        private List<HistoricalWindow> windows;
        private final Map<MappingRecomputeIdentity, MappingRecomputeJob> jobs = new HashMap<>();
        private PlanRecord plan;

        private MemoryStore(List<HistoricalWindow> windows) {
            this.windows = windows;
        }

        @Override
        public List<HistoricalWindow> findBySubjectRefs(Set<String> subjectRefs) {
            return windows.stream().filter(window -> subjectRefs.contains(window.subjectRef())).toList();
        }

        @Override
        public Optional<MappingRecomputeJob> findByIdentity(MappingRecomputeIdentity identity) {
            return Optional.ofNullable(jobs.get(identity));
        }

        @Override
        public MappingRecomputeJob insertIfAbsent(MappingRecomputeJob job) {
            return jobs.computeIfAbsent(job.identity(), ignored -> job);
        }

        @Override
        public void recordPlan(
                UUID requestId, UUID correctionLineageId, String ownerSourceId,
                int jobCount, int historyOnlyWindowCount, Instant plannedAt, String traceId) {
            plan = new PlanRecord(requestId, ownerSourceId, jobCount, historyOnlyWindowCount);
        }
    }

    private record PlanRecord(UUID requestId, String sourceId, int jobs, int historyOnly) {}
}
