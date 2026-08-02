package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Named, approved and profile-bound command for one idempotent V2 cutover attempt. */
public record ResponsibilityV2CutoverCommand(
        UUID commandId,
        CheckpointKey key,
        LocalDate businessDate,
        String operatorRef,
        String approvalRef,
        String profileDigest,
        Instant requestedAt,
        String traceId,
        String signatureDigest) {
    public static final String CONTROLLED_OPERATOR =
            "workload://identity-access/responsibility-v2-cutover";
    public static final String APPROVAL =
            "approval://Hei/story-1.6c/responsibility-v2-cutover";

    public ResponsibilityV2CutoverCommand {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (commandId == null
                || commandId.version() != 7
                || commandId.variant() != 2
                || !"responsibility".equals(key.consumerProjection())
                || operatorRef == null
                || !operatorRef.matches("[A-Za-z0-9:/._-]{8,160}")
                || approvalRef == null
                || !approvalRef.matches("[A-Za-z0-9:/._-]{8,160}")
                || profileDigest == null
                || !profileDigest.matches("[0-9a-f]{64}")
                || !requestedAt.equals(requestedAt.truncatedTo(
                        java.time.temporal.ChronoUnit.MICROS))
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")
                || signatureDigest == null
                || !signatureDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_CUTOVER_COMMAND_INVALID");
        }
    }

    /** Stable, length-unambiguous identity of the complete command payload. */
    public String canonicalDigest() {
        String canonical = new String(
                signingPayload(), StandardCharsets.UTF_8)
                + "\n" + signatureDigest;
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] signingPayload() {
        return canonicalPayload(
                commandId,
                key,
                businessDate,
                operatorRef,
                approvalRef,
                profileDigest,
                requestedAt,
                traceId);
    }

    public static ResponsibilityV2CutoverCommand signed(
            UUID commandId,
            CheckpointKey key,
            LocalDate businessDate,
            String operatorRef,
            String approvalRef,
            String profileDigest,
            Instant requestedAt,
            String traceId,
            ResponsibilityV2CutoverCommandSignaturePort signatures) {
        byte[] payload = canonicalPayload(
                commandId,
                key,
                businessDate,
                operatorRef,
                approvalRef,
                profileDigest,
                requestedAt,
                traceId);
        String signature = Objects.requireNonNull(signatures, "signatures")
                .sign(payload);
        return new ResponsibilityV2CutoverCommand(
                commandId,
                key,
                businessDate,
                operatorRef,
                approvalRef,
                profileDigest,
                requestedAt,
                traceId,
                signature);
    }

    private static byte[] canonicalPayload(
            UUID commandId,
            CheckpointKey key,
            LocalDate businessDate,
            String operatorRef,
            String approvalRef,
            String profileDigest,
            Instant requestedAt,
            String traceId) {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(requestedAt, "requestedAt");
        String canonical = String.join(
                "\n",
                "RESPONSIBILITY-V2-CUTOVER-COMMAND-1.0.0",
                commandId.toString(),
                field(key.sourceId()),
                field(key.feedId()),
                field(key.partitionId()),
                field(key.consumerProjection()),
                businessDate.toString(),
                field(operatorRef),
                field(approvalRef),
                profileDigest,
                requestedAt.toString(),
                traceId);
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] canonical) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "RESPONSIBILITY_V2_CUTOVER_DIGEST_UNAVAILABLE",
                    unavailable);
        }
    }

    private static String field(String value) {
        return value.length() + ":" + value;
    }
}
