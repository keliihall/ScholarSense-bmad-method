package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationNode;
import java.util.Optional;

/**
 * Read-only current-reference resolver used while normalizing a true incremental batch.
 *
 * <p>A change record may reference an unchanged account, organization, or parent from a prior
 * committed watermark. Implementations must read only the current projection for the same
 * checkpoint key.
 */
public interface IdentityAuthorityReferencePort {
    Optional<AuthoritativeAccount> findAccount(
            CheckpointKey key, String externalRefDigest);

    Optional<OrganizationNode> findOrganization(
            CheckpointKey key, String externalRefDigest);
}
