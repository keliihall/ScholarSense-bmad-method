package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationIdPort;
import cn.edu.suda.scholarsense.identityaccess.application.UuidV7;
import java.time.Instant;
import java.util.UUID;

public final class UuidV7AccessInvalidationIdAdapter
        implements AccessInvalidationIdPort {
    @Override
    public UUID next(Instant instant) {
        return UUID.fromString(UuidV7.generate(instant));
    }
}
