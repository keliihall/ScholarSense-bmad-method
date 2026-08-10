package cn.edu.suda.scholarsense.ingestionquality.application;

/** Controlled source of the exact executable QMDP/QSHM runtime contract. */
@FunctionalInterface
public interface ExecutableQualityPolicyPort {
    VerifiedQualityContract loadVerified();
}
