package cn.edu.suda.scholarsense.signalevaluation.api;

/** State-changing public owner port; implementations seal/replay exact digest-only input. */
@FunctionalInterface
public interface RecoverySampleNormalizedInputPort {
    void stage(RecoverySampleNormalizedInput input);
}
