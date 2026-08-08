package cn.edu.suda.scholarsense.subjectregistry.adapters.outbound;

public interface SubjectRegistryProtectionKeyPort {
    SubjectRegistryProtectionKeys active();
    SubjectRegistryProtectionKeys byReference(
            String environment, String keyRef, String keyVersion);
}
