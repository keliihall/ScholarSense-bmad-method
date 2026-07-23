package cn.edu.suda.scholarsense.auditoperations.application;

public record ArchiveObjectVersion(String objectId, String versionId) {
    public ArchiveObjectVersion {
        if (objectId == null || objectId.isBlank() || versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("AUDIT_ARCHIVE_OBJECT_VERSION_INVALID");
        }
    }
}
