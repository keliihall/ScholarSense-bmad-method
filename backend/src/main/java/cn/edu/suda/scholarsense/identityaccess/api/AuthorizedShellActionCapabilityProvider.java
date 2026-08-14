package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.List;

@FunctionalInterface
public interface AuthorizedShellActionCapabilityProvider {
    List<AuthorizedShellActionCapability> actionCapabilities();
}
