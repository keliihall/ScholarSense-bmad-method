package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Objects;

/** Exact policy/profile identity captured for final-commit revalidation. */
public record QualityContractAttestation(
        String qmdpProfileVersion,
        String qmdpPolicyRawDigest,
        String qmdpPolicyCanonicalDigest,
        String qmdpContractLockVersion,
        String qmdpContractLockRawDigest,
        String qmdpContractLockCanonicalDigest,
        String qmdpAuthorityRef,
        String qmdpApprovalRef,
        Instant qmdpEffectiveAt,
        String qshmProfileVersion,
        String qshmProfileRawDigest,
        String qshmProfileCanonicalDigest,
        String qshmContractLockVersion,
        String qshmContractLockRawDigest,
        String qshmAuthorityRef,
        String qshmApprovalRef,
        Instant qshmEffectiveAt) {

    public QualityContractAttestation {
        Objects.requireNonNull(qmdpProfileVersion);
        Objects.requireNonNull(qmdpPolicyRawDigest);
        Objects.requireNonNull(qmdpPolicyCanonicalDigest);
        Objects.requireNonNull(qmdpContractLockVersion);
        Objects.requireNonNull(qmdpContractLockRawDigest);
        Objects.requireNonNull(qmdpContractLockCanonicalDigest);
        Objects.requireNonNull(qmdpAuthorityRef);
        Objects.requireNonNull(qmdpApprovalRef);
        Objects.requireNonNull(qmdpEffectiveAt);
        Objects.requireNonNull(qshmProfileVersion);
        Objects.requireNonNull(qshmProfileRawDigest);
        Objects.requireNonNull(qshmProfileCanonicalDigest);
        Objects.requireNonNull(qshmContractLockVersion);
        Objects.requireNonNull(qshmContractLockRawDigest);
        Objects.requireNonNull(qshmAuthorityRef);
        Objects.requireNonNull(qshmApprovalRef);
        Objects.requireNonNull(qshmEffectiveAt);
    }
}
