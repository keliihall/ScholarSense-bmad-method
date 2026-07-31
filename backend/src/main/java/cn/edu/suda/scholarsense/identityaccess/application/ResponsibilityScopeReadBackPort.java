package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

@FunctionalInterface
public interface ResponsibilityScopeReadBackPort {
    ResponsibilityScopeReadBack readBack(
            String studentSourceRefDigest, Instant serverNow);
}
