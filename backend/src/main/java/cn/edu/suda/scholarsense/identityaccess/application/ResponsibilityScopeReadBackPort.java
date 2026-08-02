package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface ResponsibilityScopeReadBackPort {
    ResponsibilityScopeReadBack readBack(
            String studentSourceRefDigest, Instant serverNow);

    /**
     * Reads one cascade target only. Implementations must not substitute a
     * student-wide decision when the exact lineage/account tuple is absent.
     */
    default ResponsibilityScopeReadBack readBack(
            AccessInvalidationLineageId accessLineageId,
            UUID counselorAccountId,
            String studentSourceRefDigest,
            Instant serverNow) {
        throw new IdentitySyncException(
                "RESPONSIBILITY_SCOPE_TARGETED_READBACK_UNAVAILABLE");
    }
}
