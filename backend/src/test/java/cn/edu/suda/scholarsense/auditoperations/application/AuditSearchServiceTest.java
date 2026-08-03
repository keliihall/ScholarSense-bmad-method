package cn.edu.suda.scholarsense.auditoperations.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchCriteria;
import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchView;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionFieldResult;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionResult;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditSearchServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @Test
    void freezesAtProjectionWatermarkUsesHistoricalTokensAndAuditsBeforeReturning() {
        Fixture fixture = new Fixture("r3");
        fixture.rows.add(row(9, "ast_v1_k7_" + "a".repeat(64), "ost_v1_k7_" + "b".repeat(64)));
        fixture.rows.add(row(8, "ast_v1_k8_" + "c".repeat(64), "ost_v1_k8_" + "d".repeat(64)));

        AuditSearchPage result = fixture.service.search(criteria(AuditSearchView.BUSINESS, null));

        assertEquals(10, result.asOfSequence());
        assertEquals(12, result.sourceLedgerHead());
        assertEquals(10, result.projectionWatermark());
        assertEquals(List.of("k7", "k8"), fixture.queriedKeyVersions);
        assertEquals(Instant.parse("2023-07-23T00:00:00Z"), fixture.tokenRetainedFrom);
        assertEquals(List.of("query", "audit:accepted"), fixture.order);
        assertEquals(List.of(9L, 8L), result.items().stream()
                .map(item -> (Long) item.fields().get("ledgerSequence")).toList());
        assertTrue(result.items().getFirst().fields().containsKey("actorDisplayRef"));
        assertEquals("[MASKED-TECHNICAL]", result.items().getFirst().fields().get("eventType"));
        assertEquals(Set.of(
                "recordId", "ledgerSequence", "occurredAt", "outcome", "factSchemaVersion",
                "policyVersion", "retentionScheduleVersion", "actorDisplayRef", "objectDisplayRef",
                "businessActionCategory", "businessObjectCategory", "rolePackageSummary",
                "projectionScope", "producerModule", "eventType", "reasonCode", "traceId",
                "integrityStatus", "archiveStatus", "projectionStatus", "sourceNetworkRecorded"),
                result.items().getFirst().fields().keySet());
        assertFalse(result.toString().contains("ast_v1"));
        assertFalse(fixture.audits.getFirst().filterDigest().contains("anonymous-sensitive-actor"));
        assertEquals(List.of("actor", "page", "size", "view"), fixture.audits.getFirst().filterTypes());
    }

    @Test
    void requestedSnapshotAboveWatermarkFailsWithoutPartialRowsAfterAuditing() {
        Fixture fixture = new Fixture("r3");

        AuditSearchException failure = assertThrows(
                AuditSearchException.class,
                () -> fixture.service.search(criteria(AuditSearchView.BUSINESS, 11L)));

        assertEquals("AUDIT_SEARCH_PROJECTION_NOT_CAUGHT_UP", failure.code());
        assertTrue(fixture.rowsRequested == 0);
        assertEquals("rejected", fixture.audits.getFirst().outcome());
    }

    @Test
    void denyAndAuditCommitFailureReturnNoSensitiveRows() {
        Fixture denied = new Fixture("other");
        AuditSearchException forbidden = assertThrows(
                AuditSearchException.class,
                () -> denied.service.search(criteria(AuditSearchView.BUSINESS, null)));
        assertEquals("AUDIT_SEARCH_FORBIDDEN", forbidden.code());
        assertEquals(0, denied.rowsRequested);
        assertEquals("rejected", denied.audits.getFirst().outcome());

        Fixture auditFailure = new Fixture("r3");
        auditFailure.rows.add(row(9, "ast_v1_k7_" + "a".repeat(64), null));
        auditFailure.failAudit = true;
        AuditSearchException failed = assertThrows(
                AuditSearchException.class,
                () -> auditFailure.service.search(criteria(AuditSearchView.BUSINESS, null)));
        assertEquals("AUDIT_SEARCH_AUDIT_COMMIT_FAILED", failed.code());
    }

    @Test
    void technicalProjectionOmitsIdentityFieldsAndOnlyReturnsApprovedTechnicalMetadata() {
        Fixture fixture = new Fixture("r7");
        fixture.rows.add(row(9, "ast_v1_k7_" + "a".repeat(64), "ost_v1_k7_" + "b".repeat(64)));

        var fields = fixture.service.search(criteria(AuditSearchView.TECHNICAL, null))
                .items().getFirst().fields();

        assertFalse(fields.containsKey("actorDisplayRef"));
        assertFalse(fields.containsKey("objectDisplayRef"));
        assertEquals("identity-access", fields.get("producerModule"));
        assertEquals("identity-access.local-audit-fact.recorded.v1", fields.get("eventType"));
        assertEquals(true, fields.get("sourceNetworkRecorded"));
        assertFalse(fields.keySet().stream().anyMatch(key -> key.toLowerCase().contains("token")));
        assertEquals(Set.of(
                "recordId", "ledgerSequence", "occurredAt", "outcome", "factSchemaVersion",
                "policyVersion", "retentionScheduleVersion", "businessActionCategory",
                "businessObjectCategory", "rolePackageSummary", "projectionScope", "producerModule",
                "eventType", "reasonCode", "traceId", "integrityStatus", "archiveStatus",
                "projectionStatus", "sourceNetworkRecorded"), fields.keySet());
    }

    @Test
    void policyOrTokenProfileDriftFailsClosedBeforeQueryingRows() {
        Fixture policyDrift = new Fixture("r3");
        policyDrift.policyDrift = true;
        assertEquals("AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", assertThrows(AuditSearchException.class,
                () -> policyDrift.service.search(criteria(AuditSearchView.BUSINESS, null))).code());
        assertEquals(0, policyDrift.rowsRequested);

        Fixture tokenDrift = new Fixture("r3");
        tokenDrift.tokenDrift = true;
        assertEquals("AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", assertThrows(AuditSearchException.class,
                () -> tokenDrift.service.search(criteria(AuditSearchView.BUSINESS, null))).code());
        assertEquals(0, tokenDrift.rowsRequested);
    }

    @Test
    void actionAndScopeMustMatchWhileFieldVisibilityComesFromThePublicProjector() {
        AuthorizedAuditSearchDecision valid = Fixture.authorization("r3", AuditSearchView.BUSINESS);
        List<AuthorizedAuditSearchDecision> drifts = List.of(
                new AuthorizedAuditSearchDecision(
                        true, valid.rfpVersion(), "audit.search-technical-metadata",
                        valid.scopes(), valid.fieldProjection(), null),
                new AuthorizedAuditSearchDecision(
                        true, valid.rfpVersion(), valid.action(),
                        Set.of("audit-domain", "unexpected-scope"), valid.fieldProjection(), null));

        for (AuthorizedAuditSearchDecision drift : drifts) {
            Fixture fixture = new Fixture("r3");
            fixture.decisionOverride = drift;
            assertEquals("AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", assertThrows(
                    AuditSearchException.class,
                    () -> fixture.service.search(criteria(AuditSearchView.BUSINESS, null))).code());
            assertEquals(0, fixture.rowsRequested);
            assertEquals("rejected", fixture.audits.getFirst().outcome());
        }

        Fixture ignoredLegacyMatrix = new Fixture("r3");
        ignoredLegacyMatrix.decisionOverride = new AuthorizedAuditSearchDecision(
                true, valid.rfpVersion(), valid.action(), valid.scopes(), Map.of(), null);
        ignoredLegacyMatrix.rows.add(row(9, null, null));
        assertTrue(ignoredLegacyMatrix.service.search(criteria(AuditSearchView.BUSINESS, null))
                .items().getFirst().fields().containsKey("businessActionCategory"));
        assertEquals(1, ignoredLegacyMatrix.projectionCalls);
    }

    @Test
    void objectTypeFilterNeverReplacesTheFixedAuthorizationScope() {
        Fixture fixture = new Fixture("r3");
        fixture.rows.add(row(9, null, null));
        AuditSearchCriteria filtered = new AuditSearchCriteria(
                "r3", AuditSearchView.BUSINESS, null, "candidate", null, null,
                null, null, null, null, 0, 25, null, "trace-search-test-001");

        fixture.service.search(filtered);

        assertEquals("candidate", fixture.authorizationRequests.getFirst().objectType());
        assertEquals("audit-domain", fixture.authorizationRequests.getFirst().scope());
    }

    private static AuditSearchCriteria criteria(AuditSearchView view, Long asOf) {
        return new AuditSearchCriteria(
                "r3", view, "anonymous-sensitive-actor", null, null, null,
                null, null, null, null, 0, 25, asOf, "trace-search-test-001");
    }

    private static AuditSearchRow row(long sequence, String actorToken, String objectToken) {
        return new AuditSearchRow(
                UUID.fromString("019d2c7d-4000-7000-8000-%012d".formatted(sequence)),
                sequence, NOW.minusSeconds(10 - sequence), "accepted",
                "LOCAL-AUDIT-FACT-1.0.0", "RFP-1.0.0", "RS-1.0.0",
                actorToken, "identity-session", objectToken, "identity.session.view",
                "0".repeat(32), "identity-access",
                "identity-access.local-audit-fact.recorded.v1", "IDENTITY_SESSION_VIEWED",
                "identity", "identity-session", "R3", "CURRENT_ORGANIZATION", true);
    }

    private static final class Fixture {
        private final List<AuditSearchRow> rows = new ArrayList<>();
        private final List<SearchAuditEvent> audits = new ArrayList<>();
        private final List<String> order = new ArrayList<>();
        private final List<String> queriedKeyVersions = new ArrayList<>();
        private final List<AuthorizedAuditSearchRequest> authorizationRequests = new ArrayList<>();
        private boolean failAudit;
        private boolean policyDrift;
        private boolean tokenDrift;
        private AuthorizedAuditSearchDecision decisionOverride;
        private Instant tokenRetainedFrom;
        private int rowsRequested;
        private int projectionCalls;
        private final AuditSearchService service;

        private Fixture(String session) {
            AuditSearchQueryPort query = new AuditSearchQueryPort() {
                @Override public AuditSearchSnapshot snapshot() {
                    return new AuditSearchSnapshot(12, 10, NOW);
                }

                @Override public AuditSearchResultSlice search(
                        AuditSearchCriteria criteria,
                        List<String> actorTokens,
                        List<String> objectTokens,
                        long asOfSequence) {
                    rowsRequested++;
                    order.add("query");
                    return new AuditSearchResultSlice(List.copyOf(rows), rows.size());
                }
            };
            service = new AuditSearchService(
                    query,
                    request -> {
                        authorizationRequests.add(request);
                        return decisionOverride != null
                                ? decisionOverride
                                : policyDrift
                                ? new AuthorizedAuditSearchDecision(true, "RFP-2.0.0",
                                        "audit.search-business-metadata", Set.of("audit-domain"),
                                        authorization("r3", AuditSearchView.BUSINESS).fieldProjection(), null)
                                : authorization(session, request.view());
                    },
                    tokenQuery -> {
                        tokenRetainedFrom = tokenQuery.retainedFrom();
                        var result = List.of(
                                new AuditTokenQueryValue("ast_v1_k7_" + "a".repeat(64),
                                        tokenDrift ? "AUDIT-TOKENIZATION-2.0.0" : "AUDIT-TOKENIZATION-1.0.0", "k7"),
                                new AuditTokenQueryValue("ast_v1_k8_" + "c".repeat(64), "AUDIT-TOKENIZATION-1.0.0", "k8"));
                        queriedKeyVersions.addAll(result.stream().map(AuditTokenQueryValue::keyVersion).toList());
                        return result;
                    },
                    event -> {
                        audits.add(event);
                        if (failAudit) throw new IllegalStateException("injected audit failure");
                        order.add("audit:" + event.outcome());
                    },
                    () -> NOW,
                    requested -> session,
                    request -> {
                        projectionCalls++;
                        boolean business = "AGGREGATE_REPORT".equals(
                                request.authorizationRequest().objectClass());
                        List<FieldProjectionFieldResult> projected = new ArrayList<>();
                        for (var reference : request.valueReferences()) {
                            FieldVisibility visibility = visibility(business, reference.fieldClass());
                            if (visibility == FieldVisibility.HIDDEN) continue;
                            if (visibility == FieldVisibility.MASKED) {
                                projected.add(new FieldProjectionFieldResult(
                                        reference.fieldName(), visibility, java.util.Optional.empty(),
                                        java.util.Optional.of(mask(reference.fieldClass()))));
                            } else {
                                projected.add(new FieldProjectionFieldResult(
                                        reference.fieldName(), visibility,
                                        java.util.Optional.of(reference.serverOwnedValueReference()
                                                .orElseThrow().resolve()),
                                        java.util.Optional.empty()));
                            }
                        }
                        return new FieldProjectionResult(
                                true, "FIELD_PROJECTION_ALLOWED", projected);
                    },
                    "audit-search-v1");
        }

        private static FieldVisibility visibility(boolean business, String fieldClass) {
            if ("B".equals(fieldClass)) return FieldVisibility.CLEAR;
            if (business && "G".equals(fieldClass)) return FieldVisibility.CLEAR;
            if (business && Set.of("I", "T").contains(fieldClass)) return FieldVisibility.MASKED;
            if (!business && "T".equals(fieldClass)) return FieldVisibility.CLEAR;
            if (!business && "G".equals(fieldClass)) return FieldVisibility.MASKED;
            return FieldVisibility.HIDDEN;
        }

        private static Object mask(String fieldClass) {
            return switch (fieldClass) {
                case "I" -> "[MASKED-IDENTITY]";
                case "G" -> "[MASKED-CATEGORY]";
                case "T" -> "[MASKED-TECHNICAL]";
                default -> throw new IllegalArgumentException("unexpected mask");
            };
        }

        private static AuthorizedAuditSearchDecision authorization(String session, AuditSearchView view) {
            if (!(session.equals("r3") && view == AuditSearchView.BUSINESS)
                    && !(session.equals("r7") && view == AuditSearchView.TECHNICAL)) {
                return new AuthorizedAuditSearchDecision(
                        false, "RFP-1.0.0", null, Set.of(), Map.of(), "AUDIT_SEARCH_FORBIDDEN");
            }
            Map<String, AuditFieldVisibility> fields = view == AuditSearchView.BUSINESS
                    ? Map.of("B", AuditFieldVisibility.CLEAR, "I", AuditFieldVisibility.MASKED,
                            "C", AuditFieldVisibility.HIDDEN, "S", AuditFieldVisibility.HIDDEN,
                            "E", AuditFieldVisibility.HIDDEN, "N", AuditFieldVisibility.HIDDEN,
                            "G", AuditFieldVisibility.CLEAR, "T", AuditFieldVisibility.MASKED)
                    : Map.of("B", AuditFieldVisibility.CLEAR, "I", AuditFieldVisibility.HIDDEN,
                            "C", AuditFieldVisibility.HIDDEN, "S", AuditFieldVisibility.HIDDEN,
                            "E", AuditFieldVisibility.HIDDEN, "N", AuditFieldVisibility.HIDDEN,
                            "G", AuditFieldVisibility.MASKED, "T", AuditFieldVisibility.CLEAR);
            return new AuthorizedAuditSearchDecision(
                    true, "RFP-1.0.0",
                    view == AuditSearchView.BUSINESS
                            ? "audit.search-business-metadata" : "audit.search-technical-metadata",
                    Set.of("audit-domain"), fields, null);
        }
    }
}
