package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;

/** Read boundary for the local authorization invalidation fence. */
@FunctionalInterface
public interface AccessInvalidationFenceQueryPort {
    boolean blocks(AccessInvalidationLineageId lineageId);

    static AccessInvalidationFenceQueryPort noOp() {
        return ignored -> false;
    }
}
