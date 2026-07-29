package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface IdentitySloEvidencePort {
    void append(IdentitySloEvidence evidence);

    default void compensate(IdentitySloEvidence evidence, String reasonCode) {}

    default java.util.Optional<IdentitySloEvidence> nextCompensation() {
        return java.util.Optional.empty();
    }

    default void completeCompensation(
            java.util.UUID evidenceId, java.time.Instant completedAt) {}
}
