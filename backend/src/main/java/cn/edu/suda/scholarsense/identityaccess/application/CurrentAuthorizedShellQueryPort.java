package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface CurrentAuthorizedShellQueryPort {
    CurrentAuthorizedShellProjection current(
            String internalSessionId, String traceId, String sourceIp);
}
