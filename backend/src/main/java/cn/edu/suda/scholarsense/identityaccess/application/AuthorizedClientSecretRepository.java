package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.Optional;

@FunctionalInterface
public interface AuthorizedClientSecretRepository {
    default Optional<EncryptedAuthorizedClient> find(String sessionId, String registrationId) {
        return Optional.empty();
    }

    void save(EncryptedAuthorizedClient authorizedClient);
}
