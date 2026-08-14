package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface QualityRecoveryConfirmationRelayPort {
    List<QualityRecoveryConfirmationClaim> claim(int limit, Instant trustedNow);

    boolean markDelivered(UUID eventId, String payloadDigest, Instant trustedNow);

    boolean release(
            UUID eventId, String payloadDigest, String errorCode, Instant trustedNow);
}
