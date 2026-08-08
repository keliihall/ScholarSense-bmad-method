package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.HistoricalWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeIdentity;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeJob;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class MappingRecomputePlanner {
    private final HistoricalWindowPort windows;
    private final MappingRecomputeJobPort jobs;
    private final MappingRecomputePlanPort plans;
    private final MappingRecomputeIdPort ids;

    public MappingRecomputePlanner(
            HistoricalWindowPort windows,
            MappingRecomputeJobPort jobs,
            MappingRecomputePlanPort plans,
            MappingRecomputeIdPort ids) {
        this.windows = Objects.requireNonNull(windows);
        this.jobs = Objects.requireNonNull(jobs);
        this.plans = Objects.requireNonNull(plans);
        this.ids = Objects.requireNonNull(ids);
    }

    public MappingRecomputePlan plan(
            UUID requestId, UUID correctionLineageId, String ownerSourceId,
            Set<String> affectedStudentRefs,
            Instant serverNow, String traceId) {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(correctionLineageId);
        Objects.requireNonNull(serverNow);
        if (affectedStudentRefs == null || affectedStudentRefs.isEmpty()) {
            throw new IllegalArgumentException("affectedStudentRefs");
        }
        Set<String> immutableRefs = Set.copyOf(affectedStudentRefs);
        List<MappingRecomputeJob> selected = new ArrayList<>();
        Set<MappingRecomputeIdentity> returned = new HashSet<>();
        int historyOnly = 0;
        for (HistoricalWindow window : windows.findBySubjectRefs(immutableRefs)) {
            if (!window.isActionableAt(serverNow)
                    || window.scenarioId() == null || window.scenarioId().isBlank()) {
                historyOnly++;
                continue;
            }
            MappingRecomputeIdentity identity = new MappingRecomputeIdentity(
                    correctionLineageId, window.subjectRef(), window.ruleId(), window.ruleVersion(),
                    window.scenarioId(), window.windowId(), window.inputWatermarksDigest());
            MappingRecomputeJob job = jobs.findByIdentity(identity).orElseGet(() -> jobs.insertIfAbsent(
                    MappingRecomputeJob.queued(
                            ids.nextId(), ownerSourceId, identity,
                            window.latestActionableAt(), serverNow, traceId)));
            if (returned.add(identity)) {
                selected.add(job);
            }
        }
        MappingRecomputePlan plan = new MappingRecomputePlan(selected, historyOnly);
        plans.recordPlan(
                requestId, correctionLineageId, ownerSourceId,
                selected.size(), historyOnly, serverNow, traceId);
        return plan;
    }
}
