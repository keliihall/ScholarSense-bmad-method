package cn.edu.suda.scholarsense.auditoperations.adapters.inbound;

import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditAlertRelayProcessor;
import cn.edu.suda.scholarsense.auditoperations.application.AuditLedgerVerifier;
import cn.edu.suda.scholarsense.auditoperations.application.LedgerVerificationResult;
import cn.edu.suda.scholarsense.auditoperations.application.LowCardinalityAuditMetrics;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class AuditWorkerScheduler {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AuditLedgerVerifier verifier;
    private final AuditAvailabilityPort availability;
    private final AuditAlertRelayProcessor alerts;
    private final LowCardinalityAuditMetrics metrics;

    public AuditWorkerScheduler(
            AuditLedgerVerifier verifier,
            AuditAvailabilityPort availability,
            AuditAlertRelayProcessor alerts) {
        this(verifier, availability, alerts,
                new LowCardinalityAuditMetrics((name, labels) -> {}));
    }

    public AuditWorkerScheduler(
            AuditLedgerVerifier verifier,
            AuditAvailabilityPort availability,
            AuditAlertRelayProcessor alerts,
            LowCardinalityAuditMetrics metrics) {
        this.verifier = Objects.requireNonNull(verifier);
        this.availability = Objects.requireNonNull(availability);
        this.alerts = Objects.requireNonNull(alerts);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Scheduled(
            initialDelayString = "${scholarsense.audit.verifier.initial-delay}",
            fixedDelayString = "${scholarsense.audit.verifier.interval}")
    public void verifyAndMeasure() {
        String traceId = traceId();
        LedgerVerificationResult verification = verifier.verifyFull(traceId);
        metrics.record("audit.verifier.result", Map.of(
                "mode", "full-chain",
                "outcome", verification.healthy() ? "healthy" : "unhealthy"));
        var current = availability.current(traceId);
        metrics.record("audit.availability.state", Map.of(
                "state", current.state().name().toLowerCase(Locale.ROOT)));
    }

    @Scheduled(
            initialDelayString = "${scholarsense.audit.alert.initial-delay}",
            fixedDelayString = "${scholarsense.audit.alert.interval}")
    public void deliverAlerts() {
        alerts.runBatch();
    }

    private static String traceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
