package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyQualityState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record QualityEligibilityProcessingState(
        Map<UUID, QualityEligibilityInboxEntry> inboxEntries,
        QualityEligibilityInboxEntry currentInbox,
        QualityEligibilityCursor cursor,
        PendingQualityPair pendingPair,
        List<DependencyQualityState> dependencyStates,
        Map<String, QualityEligibilityCurrentState> currentEligibilities,
        Map<String, QualityFuseEpisodeState> activeEpisodes,
        Map<String, Long> latestEpisodeGenerations) {

    public QualityEligibilityProcessingState {
        inboxEntries = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(inboxEntries)));
        dependencyStates = List.copyOf(Objects.requireNonNull(dependencyStates));
        currentEligibilities = Map.copyOf(new LinkedHashMap<>(
                Objects.requireNonNull(currentEligibilities)));
        activeEpisodes = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(activeEpisodes)));
        latestEpisodeGenerations = Map.copyOf(new LinkedHashMap<>(
                Objects.requireNonNull(latestEpisodeGenerations)));
    }

    public static QualityEligibilityProcessingState empty() {
        return new QualityEligibilityProcessingState(
                Map.of(), null, null, null, List.of(), Map.of(), Map.of(), Map.of());
    }

    public QualityEligibilityProcessingState(
            Map<UUID, QualityEligibilityInboxEntry> inboxEntries,
            QualityEligibilityInboxEntry currentInbox,
            QualityEligibilityCursor cursor,
            PendingQualityPair pendingPair,
            List<DependencyQualityState> dependencyStates) {
        this(inboxEntries, currentInbox, cursor, pendingPair, dependencyStates,
                Map.of(), Map.of(), Map.of());
    }

    public QualityEligibilityProcessingState withCurrentInbox(
            QualityEligibilityInboxEntry entry) {
        return new QualityEligibilityProcessingState(
                inboxEntries, entry, cursor, pendingPair, dependencyStates,
                currentEligibilities, activeEpisodes, latestEpisodeGenerations);
    }

    public static String episodeKey(String sourceId, String dependencyId) {
        return Objects.requireNonNull(sourceId) + "@" + Objects.requireNonNull(dependencyId);
    }
}
