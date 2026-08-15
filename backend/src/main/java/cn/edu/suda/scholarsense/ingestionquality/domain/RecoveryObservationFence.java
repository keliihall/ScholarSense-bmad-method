package cn.edu.suda.scholarsense.ingestionquality.domain;

public record RecoveryObservationFence(
        String policyVersion,
        String policyDigest,
        String memberSetDigest,
        String watermarksDigest) {

    public RecoveryObservationFence {
        if (!"QRP-1.0.0".equals(policyVersion)) throw RecoveryObservationFact.invalid();
        RecoveryObservationFact.requireDigest(policyDigest);
        RecoveryObservationFact.requireDigest(memberSetDigest);
        RecoveryObservationFact.requireDigest(watermarksDigest);
    }
}
