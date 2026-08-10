package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.Objects;

/** Exact QMDP/QSHM identity frozen when a batch is sealed. */
public record SealedQualityContractEvidence(
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

    public SealedQualityContractEvidence {
        qmdpProfileVersion = text(qmdpProfileVersion);
        qmdpPolicyRawDigest = digest(qmdpPolicyRawDigest);
        qmdpPolicyCanonicalDigest = digest(qmdpPolicyCanonicalDigest);
        qmdpContractLockVersion = text(qmdpContractLockVersion);
        qmdpContractLockRawDigest = digest(qmdpContractLockRawDigest);
        qmdpContractLockCanonicalDigest = digest(qmdpContractLockCanonicalDigest);
        qmdpAuthorityRef = text(qmdpAuthorityRef);
        qmdpApprovalRef = text(qmdpApprovalRef);
        qmdpEffectiveAt = microsecond(qmdpEffectiveAt);
        qshmProfileVersion = text(qshmProfileVersion);
        qshmProfileRawDigest = digest(qshmProfileRawDigest);
        qshmProfileCanonicalDigest = digest(qshmProfileCanonicalDigest);
        qshmContractLockVersion = text(qshmContractLockVersion);
        qshmContractLockRawDigest = digest(qshmContractLockRawDigest);
        qshmAuthorityRef = text(qshmAuthorityRef);
        qshmApprovalRef = text(qshmApprovalRef);
        qshmEffectiveAt = microsecond(qshmEffectiveAt);
    }

    private static String text(String value) {
        return IngestionQualityDomainRules.requireText(value, 256);
    }

    private static String digest(String value) {
        return IngestionQualityDomainRules.requireSha256(value);
    }

    private static Instant microsecond(Instant value) {
        Instant required = Objects.requireNonNull(value);
        if (required.getNano() % 1_000 != 0) throw IngestionQualityDomainRules.invalid();
        return required;
    }
}
