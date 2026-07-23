package cn.edu.suda.scholarsense.auditoperations.application;

public record RetentionSchedule(String scheduleVersion, int retentionYears, boolean approved) {
    public RetentionSchedule {
        if (scheduleVersion == null || !scheduleVersion.matches("RS-[0-9]+\\.[0-9]+\\.[0-9]+")
                || retentionYears < 1) {
            throw new IllegalArgumentException("AUDIT_RETENTION_SCHEDULE_INVALID");
        }
    }

    public static RetentionSchedule rs100() {
        return new RetentionSchedule("RS-1.0.0", 3, true);
    }
}
