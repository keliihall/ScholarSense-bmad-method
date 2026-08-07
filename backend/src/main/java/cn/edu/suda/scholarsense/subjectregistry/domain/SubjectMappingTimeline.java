package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class SubjectMappingTimeline {
    private final List<SubjectMapping> mappings;

    private SubjectMappingTimeline(List<SubjectMapping> mappings) {
        this.mappings = List.copyOf(mappings);
    }

    public static SubjectMappingTimeline empty() {
        return new SubjectMappingTimeline(List.of());
    }

    /** Restores already-isolated data so corrupt/ambiguous history remains fail-closed and inspectable. */
    public static SubjectMappingTimeline restoreForIsolation(List<SubjectMapping> mappings) {
        return new SubjectMappingTimeline(List.copyOf(Objects.requireNonNull(mappings)));
    }

    public SubjectMappingTimeline append(SubjectMapping candidate) {
        Objects.requireNonNull(candidate);
        if (mappings.stream().anyMatch(existing -> existing.mappingId().equals(candidate.mappingId()))) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_VERSION_CONFLICT);
        }
        mappings.stream()
                .filter(existing -> existing.mappingAggregateId().equals(candidate.mappingAggregateId()))
                .mapToLong(SubjectMapping::mappingVersion)
                .max()
                .ifPresent(maximum -> {
                    if (candidate.mappingVersion() <= maximum) {
                        throw new SubjectRegistryException(
                                SubjectRegistryErrorCode.SUBJECT_REGISTRY_VERSION_CONFLICT);
                    }
                });
        if (mappings.stream().anyMatch(existing ->
                existing.identifierKey().equals(candidate.identifierKey())
                        && existing.effectiveInterval().overlaps(candidate.effectiveInterval()))) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_MAPPING_OVERLAP);
        }
        ArrayList<SubjectMapping> copy = new ArrayList<>(mappings);
        copy.add(candidate);
        copy.sort(Comparator.comparing(mapping -> mapping.effectiveInterval().effectiveFrom()));
        return new SubjectMappingTimeline(copy);
    }

    public SubjectResolution resolve(IdentifierKey key, Instant at) {
        List<StudentRef> candidates = mappings.stream()
                .filter(mapping -> mapping.identifierKey().equals(key) && mapping.activeAt(at))
                .map(SubjectMapping::studentRef)
                .distinct()
                .toList();
        if (candidates.isEmpty()) return SubjectResolution.noMatch();
        if (candidates.size() == 1) return SubjectResolution.unique(candidates.getFirst());
        return SubjectResolution.ambiguous();
    }

    public List<SubjectMapping> mappings() {
        return mappings;
    }
}
