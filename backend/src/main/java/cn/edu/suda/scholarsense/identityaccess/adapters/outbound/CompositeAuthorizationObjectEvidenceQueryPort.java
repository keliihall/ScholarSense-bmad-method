package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQuery;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQueryPort;
import java.util.List;
import java.util.Objects;

/** Routes an object query to its one owning module without changing RFP policy semantics. */
public final class CompositeAuthorizationObjectEvidenceQueryPort
        implements AuthorizationObjectEvidenceQueryPort {
    private final List<AuthorizationObjectEvidenceProvider> providers;

    public CompositeAuthorizationObjectEvidenceQueryPort(
            List<AuthorizationObjectEvidenceProvider> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers));
    }

    @Override
    public AuthorizationObjectEvidence resolve(AuthorizationObjectEvidenceQuery query) {
        Objects.requireNonNull(query);
        List<AuthorizationObjectEvidenceProvider> owners = providers.stream()
                .filter(provider -> provider.supportedObjectClasses().contains(query.objectClass()))
                .toList();
        if (owners.isEmpty()) {
            return AuthorizationObjectEvidence.notInstalled();
        }
        if (owners.size() != 1) {
            return AuthorizationObjectEvidence.unavailable();
        }
        try {
            AuthorizationObjectEvidence evidence = owners.getFirst().resolve(query);
            return evidence == null ? AuthorizationObjectEvidence.unavailable() : evidence;
        } catch (RuntimeException unavailable) {
            return AuthorizationObjectEvidence.unavailable();
        }
    }
}
