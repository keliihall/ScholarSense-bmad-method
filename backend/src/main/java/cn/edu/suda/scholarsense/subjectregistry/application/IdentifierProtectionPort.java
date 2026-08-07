package cn.edu.suda.scholarsense.subjectregistry.application;

public interface IdentifierProtectionPort {
    ProtectedIdentifierMaterial protect(
            String normalizedIdentifier, IdentifierProtectionContext context);

    String reveal(ProtectedIdentifierMaterial material);
}
