package cn.edu.suda.scholarsense.auditoperations.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record ArchiveReadResult(String objectId, String versionId, byte[] content, String contentDigest) {
    public ArchiveReadResult {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public static ArchiveReadResult from(String objectId, String versionId, byte[] content) {
        return new ArchiveReadResult(objectId, versionId, content, sha256(content));
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
