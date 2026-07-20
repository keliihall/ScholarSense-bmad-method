package cn.edu.suda.scholarsense.identityaccess.application;

public record SessionRefresh(
        String sessionId,
        long expectedSessionVersion,
        String registrationId,
        String sourceIp,
        String traceId) {}
