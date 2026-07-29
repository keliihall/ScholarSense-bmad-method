package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.List;

public record IdentityReconciliationSnapshot(
        String scope,
        long sourceVersion,
        long watermark,
        List<IdentityReconciliationEntry> entries) {
    public IdentityReconciliationSnapshot {
        if (!"identity-org-sandbox-sample".equals(scope)
                || sourceVersion < 1
                || watermark < 1) {
            throw new IllegalArgumentException("IDENTITY_RECONCILIATION_SCOPE_INVALID");
        }
        entries = List.copyOf(entries);
        if (entries.stream().map(IdentityReconciliationEntry::stableKeyDigest)
                .distinct().count() != entries.size()) {
            throw new IllegalArgumentException("IDENTITY_RECONCILIATION_KEY_DUPLICATE");
        }
    }
}
