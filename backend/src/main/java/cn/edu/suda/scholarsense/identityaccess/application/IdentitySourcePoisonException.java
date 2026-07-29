package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.UUID;

/** A source envelope that was received but cannot be safely normalized or applied. */
public final class IdentitySourcePoisonException extends IdentitySyncException {
    private final UUID batchId;
    private final long sourceVersion;
    private final long sourceWatermark;
    private final String payloadDigest;

    public IdentitySourcePoisonException(
            String code,
            UUID batchId,
            long sourceVersion,
            long sourceWatermark,
            String payloadDigest) {
        super(code);
        this.batchId = batchId;
        this.sourceVersion = sourceVersion;
        this.sourceWatermark = sourceWatermark;
        this.payloadDigest = payloadDigest;
    }

    public UUID batchId() {
        return batchId;
    }

    public long sourceVersion() {
        return sourceVersion;
    }

    public long sourceWatermark() {
        return sourceWatermark;
    }

    public String payloadDigest() {
        return payloadDigest;
    }
}
