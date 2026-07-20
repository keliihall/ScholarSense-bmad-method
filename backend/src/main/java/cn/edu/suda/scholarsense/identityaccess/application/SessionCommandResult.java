package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

public record SessionCommandResult(
        String sessionPseudonym,
        SessionCommandType commandType,
        long sessionVersion,
        Instant completedAt,
        String nextAction,
        boolean remoteRevocationPending) {}
