package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;

@FunctionalInterface
public interface AccessInvalidationAuditPort {
    void factAppended(AccessInvalidationFact fact);
}
