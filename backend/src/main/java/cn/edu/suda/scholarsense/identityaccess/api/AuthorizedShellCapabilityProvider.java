package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.List;

/** Cross-module route/capability manifest provider. Providers must not return mock work items. */
@FunctionalInterface
public interface AuthorizedShellCapabilityProvider {
    List<AuthorizedShellCapability> capabilities();
}
