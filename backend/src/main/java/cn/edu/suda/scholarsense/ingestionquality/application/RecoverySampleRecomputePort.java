package cn.edu.suda.scholarsense.ingestionquality.application;

@FunctionalInterface
public interface RecoverySampleRecomputePort {
    RecoverySampleRecomputeOutcome recompute(RecoverySampleRecomputeCommand command);
}
