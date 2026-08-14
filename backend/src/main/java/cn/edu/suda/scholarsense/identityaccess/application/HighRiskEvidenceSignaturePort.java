package cn.edu.suda.scholarsense.identityaccess.application;

public interface HighRiskEvidenceSignaturePort {
    SignedValue sign(String canonicalValue);

    /** Verifies both the canonical digest and the signature against a trusted key version. */
    default boolean verify(
            String canonicalValue, String keyVersion, String signature, String digest) {
        SignedValue expected = sign(canonicalValue);
        return expected.keyVersion().equals(keyVersion)
                && expected.signature().equals(signature)
                && expected.digest().equals(digest);
    }

    record SignedValue(String keyVersion, String signature, String digest) {}
}
