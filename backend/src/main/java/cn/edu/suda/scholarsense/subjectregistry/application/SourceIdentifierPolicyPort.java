package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierType;

@FunctionalInterface
public interface SourceIdentifierPolicyPort {
    SourceIdentifierRule requireApproved(
            String sourceId, String sourceContractVersion, IdentifierType identifierType);
}
