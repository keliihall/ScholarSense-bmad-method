package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.List;

@FunctionalInterface
public interface IdentityCascadeScopeReadBackPort {
    List<IdentityCascadeScopeReadBack> readBack(
            IdentityRecordKind recordKind,
            String externalRefDigest,
            long sourceVersion,
            long sourceWatermark,
            long aggregateVersion,
            Instant serverNow);

    static IdentityCascadeScopeReadBackPort noOp() {
        return (recordKind, externalRefDigest, sourceVersion,
                sourceWatermark, aggregateVersion, serverNow) -> List.of();
    }
}
