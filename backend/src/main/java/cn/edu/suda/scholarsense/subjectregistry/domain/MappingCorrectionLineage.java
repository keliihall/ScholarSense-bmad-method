package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class MappingCorrectionLineage {
    private final UUID lineageId;
    private final List<MappingCorrectionEvent> events;

    private MappingCorrectionLineage(UUID lineageId, List<MappingCorrectionEvent> events) {
        this.lineageId = SubjectRegistryDomainRules.requireUuidV7(
                lineageId, SubjectRegistryErrorCode.SUBJECT_REGISTRY_LINEAGE_INVALID);
        this.events = List.copyOf(events);
    }

    public static MappingCorrectionLineage empty(UUID lineageId) {
        return new MappingCorrectionLineage(lineageId, List.of());
    }

    public MappingCorrectionLineage append(MappingCorrectionEvent candidate) {
        if (!lineageId.equals(candidate.lineageId())
                || events.stream().anyMatch(event -> event.eventId().equals(candidate.eventId()))) {
            throw invalidLineage();
        }
        MappingCorrectionEvent current = currentEvent().orElse(null);
        if (current == null) {
            if (candidate.aggregateVersion() != 1 || candidate.supersedesId() != null) {
                throw invalidLineage();
            }
        } else if (!current.eventId().equals(candidate.supersedesId())
                || candidate.aggregateVersion() != current.aggregateVersion() + 1
                || candidate.effectiveAt().isBefore(current.effectiveAt())) {
            throw invalidLineage();
        }
        ArrayList<MappingCorrectionEvent> copy = new ArrayList<>(events);
        copy.add(candidate);
        if (new HashSet<>(copy.stream().map(MappingCorrectionEvent::eventId).toList()).size()
                != copy.size()) {
            throw invalidLineage();
        }
        return new MappingCorrectionLineage(lineageId, copy);
    }

    public Optional<MappingCorrectionEvent> currentEvent() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.getLast());
    }

    public List<MappingCorrectionEvent> events() {
        return events;
    }

    private static SubjectRegistryException invalidLineage() {
        return new SubjectRegistryException(
                SubjectRegistryErrorCode.SUBJECT_REGISTRY_LINEAGE_INVALID);
    }
}
