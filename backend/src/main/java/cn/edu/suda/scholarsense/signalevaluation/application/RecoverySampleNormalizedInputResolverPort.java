package cn.edu.suda.scholarsense.signalevaluation.application;

/** Provider-owned resolver for a sealed normalized snapshot selected by opaque reference. */
@FunctionalInterface
public interface RecoverySampleNormalizedInputResolverPort {
    RecoverySampleNormalizedInputResolution resolve(RecoverySampleResolutionCommand command);
}
