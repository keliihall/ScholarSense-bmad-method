package cn.edu.suda.scholarsense.shared.time;

/** The value is frozen by PP-1.0.0 and cannot be widened by runtime injection. */
public record TrustedClockConstraints(String performanceProfileVersion, int maximumSkewMs) {
    public TrustedClockConstraints {
        if (!"PP-1.0.0".equals(performanceProfileVersion) || maximumSkewMs != 100) {
            throw new IllegalArgumentException("AUDIT_CLOCK_POLICY_INVALID");
        }
    }
}
