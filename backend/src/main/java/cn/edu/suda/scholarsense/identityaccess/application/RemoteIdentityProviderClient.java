package cn.edu.suda.scholarsense.identityaccess.application;

public interface RemoteIdentityProviderClient {
    RemoteRefreshTokens refresh(String registrationId, char[] refreshToken);

    void revokeAndEndSession(
            String registrationId,
            char[] refreshToken,
            SessionCommandType commandType);
}
