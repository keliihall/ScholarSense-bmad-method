package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Server-owned projection evidence. No value in this record is accepted from an HTTP client. */
public record FieldProjectionEvidence(
        ProjectionObjectClass objectClass,
        String purpose,
        Instant serverNow,
        Optional<EffectiveWindow> taskWindow,
        boolean currentWorkItem,
        boolean assigned,
        boolean ownedSource,
        Set<String> fieldAllowlist,
        Optional<Set<String>> delegationFieldAllowlist) {
    public FieldProjectionEvidence {
        Objects.requireNonNull(objectClass, "objectClass");
        Objects.requireNonNull(serverNow, "serverNow");
        taskWindow = Objects.requireNonNull(taskWindow, "taskWindow");
        fieldAllowlist = Set.copyOf(fieldAllowlist);
        delegationFieldAllowlist = Objects.requireNonNull(delegationFieldAllowlist, "delegationFieldAllowlist")
                .map(Set::copyOf);
        if (purpose != null && !purpose.matches("[a-z][a-z0-9.-]{2,127}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_PURPOSE_INVALID");
        }
    }

    public static FieldProjectionEvidence auditSearch(String purpose, Instant serverNow) {
        return new FieldProjectionEvidence(
                ProjectionObjectClass.AUDIT_SEARCH_RECORD,
                purpose,
                serverNow,
                Optional.empty(),
                true,
                false,
                false,
                Set.of(),
                Optional.empty());
    }

    public static FieldProjectionEvidence transfer(
            String purpose,
            Instant serverNow,
            Optional<EffectiveWindow> taskWindow,
            Set<String> fieldAllowlist,
            Optional<Set<String>> delegationFieldAllowlist) {
        return new FieldProjectionEvidence(
                ProjectionObjectClass.TRANSFER_ORDER,
                purpose,
                serverNow,
                taskWindow,
                true,
                true,
                false,
                fieldAllowlist,
                delegationFieldAllowlist);
    }

    public static FieldProjectionEvidence subjectMappingRepair(
            Instant serverNow, boolean ownedSource) {
        return new FieldProjectionEvidence(
                ProjectionObjectClass.SUBJECT_MAPPING_EXCEPTION,
                "subject-mapping-repair",
                serverNow,
                Optional.empty(),
                true,
                false,
                ownedSource,
                Set.of(),
                Optional.empty());
    }

    public static FieldProjectionEvidence unknown(Instant serverNow) {
        return new FieldProjectionEvidence(
                ProjectionObjectClass.UNKNOWN,
                null,
                serverNow,
                Optional.empty(),
                false,
                false,
                false,
                Set.of(),
                Optional.empty());
    }

    public FieldProjectionEvidence withServerNow(Instant value) {
        return new FieldProjectionEvidence(
                objectClass,
                purpose,
                value,
                taskWindow,
                currentWorkItem,
                assigned,
                ownedSource,
                fieldAllowlist,
                delegationFieldAllowlist);
    }

    public FieldProjectionEvidence withCurrentWorkItem(boolean value) {
        return new FieldProjectionEvidence(
                objectClass,
                purpose,
                serverNow,
                taskWindow,
                value,
                assigned,
                ownedSource,
                fieldAllowlist,
                delegationFieldAllowlist);
    }
}
