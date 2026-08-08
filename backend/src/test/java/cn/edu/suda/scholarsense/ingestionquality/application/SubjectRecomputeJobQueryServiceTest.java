package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeJobStatus;
import cn.edu.suda.scholarsense.subjectregistry.api.PendingSubjectRecomputeRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubjectRecomputeJobQueryServiceTest {
    private static final UUID JOB =
            UUID.fromString("019fcfea-6600-7000-8000-000000000001");
    private static final String SOURCE = "SRC-P0-CARD-001";
    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");
    private static final String TRACE = "00112233445566778899aabbccddeeff";
    private static final RecomputeJobActorContext ACTOR =
            new RecomputeJobActorContext("actor", "192.0.2.10");

    @Test
    void r7FallsBackToPlatformReadWhileR6UsesOwnedSourceRead() {
        List<CompositeAuthorizationRequest> requests = new ArrayList<>();
        var service = new SubjectRecomputeJobQueryService(
                jobPort(record(4)),
                request -> {
                    requests.add(request);
                    return decision(
                            "platform.read".equals(request.actionId())
                                    ? CompositeAuthorizationOutcome.ALLOW
                                    : CompositeAuthorizationOutcome.DENY,
                            request);
                },
                request -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                ignored -> Optional.empty());

        assertEquals("running", service.get(JOB, ACTOR, TRACE).status());
        assertEquals(List.of("data-quality.read", "platform.read"),
                requests.stream().map(CompositeAuthorizationRequest::actionId).toList());
        assertEquals("JOB", requests.getLast().objectClass());
        assertEquals(sha256(SOURCE), requests.getLast().objectTokenDigest());
    }

    @Test
    void staleAuthorizationOrObjectVersionIsRejectedBeforeProjection() {
        var staleAuthorization = new SubjectRecomputeJobQueryService(
                jobPort(record(4)), request -> decision(CompositeAuthorizationOutcome.ALLOW, request),
                request -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.STALE, "STALE"),
                ignored -> Optional.empty());
        assertEquals("INGESTION_QUALITY_FORBIDDEN", assertThrows(
                IngestionQualityApplicationException.class,
                () -> staleAuthorization.get(JOB, ACTOR, TRACE)).code());

        List<RecomputeJobRecord> versions = new ArrayList<>(List.of(record(4), record(5)));
        var changedObject = new SubjectRecomputeJobQueryService(
                ignored -> Optional.of(versions.removeFirst()),
                request -> decision(CompositeAuthorizationOutcome.ALLOW, request),
                request -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                ignored -> Optional.empty());
        assertEquals("INGESTION_QUALITY_FORBIDDEN", assertThrows(
                IngestionQualityApplicationException.class,
                () -> changedObject.get(JOB, ACTOR, TRACE)).code());
    }

    @Test
    void committedSubjectRequestIsQueryableWhileRelayHasNotCreatedIqPlanYet() {
        var service = new SubjectRecomputeJobQueryService(
                ignored -> Optional.empty(),
                request -> decision(CompositeAuthorizationOutcome.ALLOW, request),
                request -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                ignored -> Optional.of(new PendingSubjectRecomputeRequest(
                        JOB, SOURCE, NOW, TRACE)));

        var view = service.get(JOB, ACTOR, TRACE);
        assertEquals("queued", view.status());
        assertEquals(0, view.attemptNo());
        assertEquals(JOB, view.jobId());
    }

    private static RecomputeJobQueryPort jobPort(RecomputeJobRecord record) {
        return ignored -> Optional.of(record);
    }

    private static RecomputeJobRecord record(long version) {
        return new RecomputeJobRecord(
                JOB, MappingRecomputeJobStatus.RUNNING, 2, NOW, null, null,
                TRACE, SOURCE, version);
    }

    private static CompositeAuthorizationDecision decision(
            CompositeAuthorizationOutcome outcome, CompositeAuthorizationRequest request) {
        return new CompositeAuthorizationDecision(
                outcome, outcome == CompositeAuthorizationOutcome.ALLOW ? "ALLOW" : "DENY",
                Set.of(), Set.of(), Map.of(), Set.of(), "RFP-1.0.0",
                request.expectedObjectVersion(), NOW,
                new CompositeAuthorizationDecisionToken(
                        1, 1, 1, 1, 1, request.expectedObjectVersion(), "RFP-1.0.0"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
