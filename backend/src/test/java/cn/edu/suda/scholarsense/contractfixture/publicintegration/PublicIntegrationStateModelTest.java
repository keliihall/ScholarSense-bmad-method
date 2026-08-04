package cn.edu.suda.scholarsense.contractfixture.publicintegration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.shared.outbox.DeliveryRecordKey;
import cn.edu.suda.scholarsense.shared.outbox.DeliveryStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicIntegrationStateModelTest {

    @Test
    void deliveryRecordKeyIsExactlyTheContractQuadruple() {
        var key = new DeliveryRecordKey(
                "Candidate",
                "candidate-01",
                "pic.public-task.v1",
                "PIC-1.0.0");

        assertEquals("Candidate", key.aggregateType());
        assertEquals("candidate-01", key.aggregateId());
        assertEquals("pic.public-task.v1", key.channelId());
        assertEquals("PIC-1.0.0", key.contractVersion());
        assertThrows(IllegalArgumentException.class,
                () -> new DeliveryRecordKey(" ", "a", "b", "c"));
    }

    @Test
    void deliveryStatusContainsNoBusinessOrApplyState() {
        assertEquals(
                List.of("PENDING", "RETRYING", "CONFIRMED", "FAILED"),
                Arrays.stream(DeliveryStatus.values()).map(Enum::name).toList());
    }

    @Test
    void provenanceIsAnExclusiveTaggedUnion() {
        var stream = PublicIntegrationStateModel.Provenance.stream(
                "018f0f9a-7b0d-7abc-8def-0123456789ab",
                "018f0f9a-7b0d-7abc-8def-0123456789ac",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "pic.public-task.v1",
                1,
                4);
        var intent = PublicIntegrationStateModel.Provenance.intent(
                "di1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                4);

        assertEquals(PublicIntegrationStateModel.ProvenanceMode.AGGREGATE_STREAM, stream.mode());
        assertEquals(PublicIntegrationStateModel.ProvenanceMode.INTENT_COMMAND, intent.mode());
        assertThrows(IllegalArgumentException.class,
                () -> new PublicIntegrationStateModel.Provenance(
                        PublicIntegrationStateModel.ProvenanceMode.AGGREGATE_STREAM,
                        null, null, null, null, null, null, null,
                        intent.deliveryIntentId(), intent.requestDigest(), 4));
    }

    @Test
    void generationIdentityIsStableAcrossProjectionAndIntentReplay() {
        var streamOne = PublicIntegrationStateModel.generationKeyForEvent(
                "urn:scholarsense:clue-care",
                "018f0f9a-7b0d-7abc-8def-0123456789ac");
        var streamTwo = PublicIntegrationStateModel.generationKeyForEvent(
                "urn:scholarsense:clue-care",
                "018f0f9a-7b0d-7abc-8def-0123456789ac");
        var intent = PublicIntegrationStateModel.deliveryIntentId(
                "wk1.YWJjZGVmZ2g", "overdue", 2);

        assertEquals(streamOne, streamTwo);
        assertTrue(streamOne.matches("g1\\.[A-Za-z0-9_-]{43}"));
        assertTrue(intent.matches("di1\\.[A-Za-z0-9_-]{43}"));
        assertTrue(PublicIntegrationStateModel.generationKeyForIntent(intent)
                .matches("g1\\.[A-Za-z0-9_-]{43}"));
    }

    @Test
    void queuedDeliveryGetsSequenceOnlyOnActivationAndOldGenerationSeals() {
        var key = new DeliveryRecordKey("Candidate", "candidate-01", "pic.public-task.v1", "PIC-1.0.0");
        var first = new PublicIntegrationStateModel.QueuedDelivery(
                key,
                "g1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "create",
                10,
                Instant.parse("2026-08-03T08:00:00Z"),
                PublicIntegrationStateModel.Provenance.stream(
                        "018f0f9a-7b0d-7abc-8def-0123456789ab",
                        "018f0f9a-7b0d-7abc-8def-0123456789ac",
                        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        "pic.public-task.v1", 1, 4));
        var state = PublicIntegrationStateModel.empty(key).activate(first);

        assertEquals(1, state.current().deliverySequence());
        assertEquals(DeliveryStatus.PENDING, state.current().status());
        assertEquals(1, state.transitionLedger().size());

        state = state.confirm(
                first.generationKey(), state.current().fencingToken());
        var second = new PublicIntegrationStateModel.QueuedDelivery(
                key,
                "g1.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "update",
                10,
                Instant.parse("2026-08-03T08:01:00Z"),
                PublicIntegrationStateModel.Provenance.stream(
                        "018f0f9a-7b0d-7abc-8def-0123456789ad",
                        "018f0f9a-7b0d-7abc-8def-0123456789ae",
                        "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                        "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                        "pic.public-task.v1", 2, 9));
        state = state.activate(second);

        assertEquals(2, state.current().deliverySequence());
        assertTrue(state.isSealed(first.generationKey()));
        var currentState = state;
        assertThrows(IllegalStateException.class,
                () -> currentState.retry(first.generationKey(), 1, true));
    }

    @Test
    void routeSequenceAndSparseSourceVersionHaveIndependentDecisions() {
        assertEquals(PublicIntegrationStateModel.RouteDecision.NEXT,
                PublicIntegrationStateModel.routeDecision(0, 1, null, 4, true));
        assertEquals(PublicIntegrationStateModel.RouteDecision.DUPLICATE,
                PublicIntegrationStateModel.routeDecision(1, 1, 4L, 4, true));
        assertEquals(PublicIntegrationStateModel.RouteDecision.CONFLICT,
                PublicIntegrationStateModel.routeDecision(1, 1, 4L, 4, false));
        assertEquals(PublicIntegrationStateModel.RouteDecision.GAP,
                PublicIntegrationStateModel.routeDecision(1, 3, 4L, 9, true));
        assertEquals(PublicIntegrationStateModel.RouteDecision.NEXT,
                PublicIntegrationStateModel.routeDecision(1, 2, 4L, 9, true));
        assertEquals(PublicIntegrationStateModel.RouteDecision.SOURCE_VERSION_CONFLICT,
                PublicIntegrationStateModel.routeDecision(1, 2, 4L, 4, true));
    }

    @Test
    void retryClassificationFailsClosedForContractAndMixedAcknowledgement() {
        assertTrue(PublicIntegrationStateModel.isRetryable("timeout"));
        assertTrue(PublicIntegrationStateModel.isRetryable("http-429"));
        assertFalse(PublicIntegrationStateModel.isRetryable("http-401"));
        assertFalse(PublicIntegrationStateModel.isRetryable("http-207"));
        assertFalse(PublicIntegrationStateModel.isRetryable("mixed-ack"));
    }
}
