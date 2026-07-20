package cn.edu.suda.scholarsense.identityaccess.api;

import cn.edu.suda.scholarsense.identityaccess.application.CurrentSessionProjection;

/** Transport-neutral public identity surface for other bounded contexts. */
@FunctionalInterface
public interface CurrentIdentitySessionPort {
    CurrentSessionProjection current(String sessionPseudonym);
}
