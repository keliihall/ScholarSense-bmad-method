package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Objects;
import java.util.Set;

/**
 * One object-owning module's evidence contribution.
 *
 * <p>Object classes have a single owner. The runtime composes contributions and fails closed
 * when more than one provider claims the same class.
 */
public interface AuthorizationObjectEvidenceProvider {
    Set<String> supportedObjectClasses();

    AuthorizationObjectEvidence resolve(AuthorizationObjectEvidenceQuery query);

    static AuthorizationObjectEvidenceProvider forObjectClasses(
            Set<String> objectClasses, AuthorizationObjectEvidenceQueryPort delegate) {
        Set<String> supported = Set.copyOf(Objects.requireNonNull(objectClasses));
        if (supported.isEmpty()
                || supported.stream().anyMatch(value ->
                        value == null || !value.matches("[A-Z][A-Z0-9_]{2,63}"))) {
            throw new IllegalArgumentException(
                    "IDENTITY_AUTHORIZATION_OBJECT_PROVIDER_CLASSES_INVALID");
        }
        Objects.requireNonNull(delegate);
        return new AuthorizationObjectEvidenceProvider() {
            @Override
            public Set<String> supportedObjectClasses() {
                return supported;
            }

            @Override
            public AuthorizationObjectEvidence resolve(AuthorizationObjectEvidenceQuery query) {
                return delegate.resolve(query);
            }
        };
    }
}
