package cn.edu.suda.scholarsense.identityaccess.api;

/** Implemented by the module that owns the protected object and its current scope facts. */
@FunctionalInterface
public interface AuthorizationObjectEvidenceQueryPort {
    AuthorizationObjectEvidence resolve(AuthorizationObjectEvidenceQuery query);

    static AuthorizationObjectEvidenceQueryPort notInstalled() {
        return ignored -> AuthorizationObjectEvidence.notInstalled();
    }
}
