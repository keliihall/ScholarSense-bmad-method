package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvidence;
import java.time.Instant;
import java.util.List;

/** Reads identity-org facts needed to validate responsibility recipients. */
@FunctionalInterface
public interface ResponsibilityRecipientEvidencePort {
    List<ResponsibilityRecipientEvidence> resolve(
            List<AuthoritativeResponsibilityRelation> relations,
            Instant serverNow);
}
