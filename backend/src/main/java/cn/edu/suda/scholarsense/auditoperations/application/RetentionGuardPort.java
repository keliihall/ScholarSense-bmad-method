package cn.edu.suda.scholarsense.auditoperations.application;

public interface RetentionGuardPort {
    RetentionGuardSnapshot snapshot(RetentionExecutionCommand command);

    boolean stillCurrent(RetentionGuardSnapshot expected, long fencingToken);
}
