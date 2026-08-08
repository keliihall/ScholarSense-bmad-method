package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record SubjectLink(LinkType type, StudentRef source, List<StudentRef> targets) {
    public SubjectLink {
        Objects.requireNonNull(type);
        Objects.requireNonNull(source);
        targets = List.copyOf(Objects.requireNonNull(targets));
        if (new HashSet<>(targets).size() != targets.size() || targets.contains(source)) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_LINEAGE_INVALID);
        }
        if (type == LinkType.MERGED_INTO && targets.size() > 1) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_LINEAGE_INVALID);
        }
    }

    public static SubjectLink of(LinkType type, StudentRef source, List<StudentRef> targets) {
        return new SubjectLink(type, source, targets);
    }

    public boolean manualSelectionRequired() {
        return type == LinkType.SPLIT_INTO;
    }
}
