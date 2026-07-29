package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface IdentityArchiveNormalizationPort {
    NormalizedIdentityBatch normalize(
            IdentityArchivedEnvelope archive,
            char[] plaintext,
            IdentityAuthorityReferencePort references);
}
