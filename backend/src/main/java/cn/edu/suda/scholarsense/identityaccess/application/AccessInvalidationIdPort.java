package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface AccessInvalidationIdPort {
    UUID next(Instant instant);
}
