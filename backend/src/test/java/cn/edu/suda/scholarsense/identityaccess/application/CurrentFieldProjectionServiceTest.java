package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionObjectClass;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionObjectEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionRequest;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionResult;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionSafeDocument;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionValueReference;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import cn.edu.suda.scholarsense.identityaccess.api.SensitiveValueReference;
import cn.edu.suda.scholarsense.identityaccess.api.SensitiveProjectionAuditRecord;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.CurrentFieldProjectionService;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldProjectionCatalog;
import cn.edu.suda.scholarsense.identityaccess.domain.FieldProjectionEvaluator;
import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CurrentFieldProjectionServiceTest {
    @Test
    void authorizes_rechecks_decrypts_clear_only_audits_then_reuses_one_safe_document() {
        TestDependencies dependencies = new TestDependencies();
        CurrentFieldProjectionService service = dependencies.service();

        FieldProjectionResult result = service.project(request("key-state-v1"));

        assertTrue(result.allowed());
        assertEquals("AUDIT-0001", result.fields().get(0).clearValue().orElseThrow());
        assertEquals("[MASKED-IDENTITY]", result.fields().get(1).maskedValue().orElseThrow());
        assertEquals(1, dependencies.resolveCalls.get());
        assertEquals(1, dependencies.decryptCalls.get());
        assertEquals(1, dependencies.auditCalls.get());
        assertEquals(2, dependencies.recheckCalls.get());
        assertEquals(2, dependencies.keyStateCalls.get());
        assertEquals(1, dependencies.lastAudit.clearCount());
        assertEquals(1, dependencies.lastAudit.maskedCount());
        assertEquals(0, dependencies.lastAudit.hiddenCount());

        FieldProjectionSafeDocument document = FieldProjectionSafeDocument.from(result);
        assertEquals(document.jsonValues(), document.exportValues());
        assertEquals(Set.of("recordId", "actorDisplayRef"), document.jsonValues().keySet());
    }

    @Test
    void stale_authorization_or_key_state_returns_no_fields_before_value_resolution() {
        TestDependencies stale = new TestDependencies();
        stale.recheckOutcome = CompositeAuthorizationRecheckOutcome.STALE;
        FieldProjectionResult staleResult = stale.service().project(request("key-state-v1"));
        assertFalse(staleResult.allowed());
        assertEquals("FIELD_PROJECTION_DECISION_STALE", staleResult.reasonCode());
        assertTrue(staleResult.fields().isEmpty());
        assertEquals(0, stale.resolveCalls.get());
        assertEquals(0, stale.decryptCalls.get());
        assertEquals(1, stale.auditCalls.get());
        assertEquals("DECISION_STALE", stale.lastAudit.result());

        TestDependencies rotated = new TestDependencies();
        rotated.currentKeyState = "key-state-v2";
        FieldProjectionResult rotatedResult = rotated.service().project(request("key-state-v1"));
        assertFalse(rotatedResult.allowed());
        assertEquals("FIELD_PROJECTION_KEY_STATE_STALE", rotatedResult.reasonCode());
        assertTrue(rotatedResult.fields().isEmpty());
        assertEquals(0, rotated.resolveCalls.get());
        assertEquals(0, rotated.decryptCalls.get());
        assertEquals(1, rotated.auditCalls.get());
        assertEquals("KEY_STATE_STALE", rotated.lastAudit.result());
    }

    @Test
    void server_owned_safe_values_are_resolved_only_for_clear_fields_without_crypto() {
        TestDependencies dependencies = new TestDependencies();
        AtomicInteger clearReads = new AtomicInteger();
        AtomicInteger maskedReads = new AtomicInteger();
        FieldProjectionRequest source = request("key-state-v1");
        FieldProjectionRequest safe = new FieldProjectionRequest(
                source.authorizationRequest(),
                source.objectClass(),
                source.objectEvidence(),
                List.of(
                        FieldProjectionValueReference.serverOwned(
                                "recordId", "B", "string", () -> {
                                    clearReads.incrementAndGet();
                                    return "AUDIT-0002";
                                }),
                        FieldProjectionValueReference.serverOwned(
                                "actorDisplayRef", "I", "string", () -> {
                                    maskedReads.incrementAndGet();
                                    return "must-not-be-read";
                                })));

        FieldProjectionResult result = dependencies.service().project(safe);

        assertTrue(result.allowed());
        assertEquals("AUDIT-0002", result.fields().getFirst().clearValue().orElseThrow());
        assertEquals(1, clearReads.get());
        assertEquals(0, maskedReads.get());
        assertEquals(0, dependencies.resolveCalls.get());
        assertEquals(0, dependencies.decryptCalls.get());
    }

    @Test
    void audit_or_transaction_commit_failure_never_releases_prepared_content() {
        TestDependencies auditFailure = new TestDependencies();
        auditFailure.failAudit = true;
        FieldProjectionResult auditResult = auditFailure.service().project(request("key-state-v1"));
        assertFalse(auditResult.allowed());
        assertEquals("FIELD_PROJECTION_AUDIT_COMMIT_FAILED", auditResult.reasonCode());
        assertTrue(auditResult.fields().isEmpty());

        TestDependencies commitFailure = new TestDependencies();
        commitFailure.failCommit = true;
        FieldProjectionResult commitResult = commitFailure.service().project(request("key-state-v1"));
        assertFalse(commitResult.allowed());
        assertEquals("FIELD_PROJECTION_AUDIT_COMMIT_FAILED", commitResult.reasonCode());
        assertTrue(commitResult.fields().isEmpty());
    }

    @Test
    void denied_or_dependency_authorization_is_mapped_to_stable_empty_result() {
        TestDependencies denied = new TestDependencies();
        denied.authorizationOutcome = CompositeAuthorizationOutcome.DENY;
        FieldProjectionResult deniedResult = denied.service().project(request("key-state-v1"));
        assertEquals("FIELD_PROJECTION_DENIED", deniedResult.reasonCode());
        assertTrue(deniedResult.fields().isEmpty());
        assertEquals(1, denied.auditCalls.get());
        assertEquals("DENIED", denied.lastAudit.result());

        TestDependencies unavailable = new TestDependencies();
        unavailable.authorizationOutcome = CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE;
        FieldProjectionResult unavailableResult = unavailable.service().project(request("key-state-v1"));
        assertEquals("FIELD_PROJECTION_DEPENDENCY_UNAVAILABLE", unavailableResult.reasonCode());
        assertTrue(unavailableResult.fields().isEmpty());
        assertEquals(1, unavailable.auditCalls.get());
        assertEquals("DEPENDENCY_UNAVAILABLE", unavailable.lastAudit.result());

        TestDependencies thrown = new TestDependencies();
        thrown.failAuthorization = true;
        FieldProjectionResult thrownResult = thrown.service().project(request("key-state-v1"));
        assertEquals("FIELD_PROJECTION_DEPENDENCY_UNAVAILABLE", thrownResult.reasonCode());
        assertEquals(1, thrown.auditCalls.get());
        assertEquals("DEPENDENCY_UNAVAILABLE", thrown.lastAudit.result());
    }

    @Test
    void duplicate_value_references_fail_closed_without_resolving_or_decrypting() {
        TestDependencies dependencies = new TestDependencies();
        FieldProjectionRequest source = request("key-state-v1");
        FieldProjectionRequest duplicate = new FieldProjectionRequest(
                source.authorizationRequest(),
                source.objectClass(),
                source.objectEvidence(),
                List.of(
                        source.valueReferences().getFirst(),
                        new FieldProjectionValueReference(
                                "recordId", "B", "string",
                                new SensitiveValueReference(
                                        "VALUE-REF-DUPLICATE", "BASIC", "kms/audit", "key-v4"))));

        FieldProjectionResult result = dependencies.service().project(duplicate);

        assertFalse(result.allowed());
        assertEquals("FIELD_PROJECTION_EVIDENCE_INVALID", result.reasonCode());
        assertTrue(result.fields().isEmpty());
        assertEquals(0, dependencies.resolveCalls.get());
        assertEquals(0, dependencies.decryptCalls.get());
        assertEquals(1, dependencies.auditCalls.get());
        assertEquals("EVIDENCE_INVALID", dependencies.lastAudit.result());
    }

    @Test
    void concurrent_authority_or_key_changes_fail_before_values_audit_or_return() throws Exception {
        for (String changedEvidence : List.of(
                "identity", "relation", "grant", "task", "purpose", "fieldAllowlist",
                "objectVersion", "policy")) {
            TestDependencies dependencies = new TestDependencies();
            dependencies.blockRecheck = true;
            CompletableFuture<FieldProjectionResult> pending = CompletableFuture.supplyAsync(
                    () -> dependencies.service().project(request("key-state-v1")));

            assertTrue(dependencies.recheckEntered.await(5, TimeUnit.SECONDS), changedEvidence);
            dependencies.recheckOutcome = CompositeAuthorizationRecheckOutcome.STALE;
            dependencies.releaseRecheck.countDown();
            FieldProjectionResult result = pending.get(5, TimeUnit.SECONDS);

            assertFalse(result.allowed(), changedEvidence);
            assertEquals("FIELD_PROJECTION_DECISION_STALE", result.reasonCode(), changedEvidence);
            assertTrue(result.fields().isEmpty(), changedEvidence);
            assertEquals(0, dependencies.resolveCalls.get(), changedEvidence);
            assertEquals(0, dependencies.decryptCalls.get(), changedEvidence);
            assertEquals(1, dependencies.auditCalls.get(), changedEvidence);
            assertEquals("DECISION_STALE", dependencies.lastAudit.result(), changedEvidence);
        }

        TestDependencies keyChanged = new TestDependencies();
        keyChanged.blockRecheck = true;
        CompletableFuture<FieldProjectionResult> pending = CompletableFuture.supplyAsync(
                () -> keyChanged.service().project(request("key-state-v1")));
        assertTrue(keyChanged.recheckEntered.await(5, TimeUnit.SECONDS));
        keyChanged.currentKeyState = "key-state-v2";
        keyChanged.releaseRecheck.countDown();
        FieldProjectionResult result = pending.get(5, TimeUnit.SECONDS);
        assertFalse(result.allowed());
        assertEquals("FIELD_PROJECTION_KEY_STATE_STALE", result.reasonCode());
        assertTrue(result.fields().isEmpty());
        assertEquals(0, keyChanged.resolveCalls.get());
        assertEquals(0, keyChanged.decryptCalls.get());
        assertEquals(1, keyChanged.auditCalls.get());
        assertEquals("KEY_STATE_STALE", keyChanged.lastAudit.result());
    }

    @Test
    void kms_failure_has_no_plaintext_fallback_or_audit_bypass() {
        TestDependencies dependencies = new TestDependencies();
        dependencies.failCrypto = true;

        FieldProjectionResult result = dependencies.service().project(request("key-state-v1"));

        assertFalse(result.allowed());
        assertEquals("FIELD_PROJECTION_CRYPTO_FAILED", result.reasonCode());
        assertTrue(result.fields().isEmpty());
        assertEquals(1, dependencies.resolveCalls.get());
        assertEquals(1, dependencies.decryptCalls.get());
        assertEquals(1, dependencies.auditCalls.get());
        assertEquals("CRYPTO_FAILED", dependencies.lastAudit.result());
    }

    @Test
    void finalCurrentChecksAfterMaterializationDiscardPreparedPlaintextBeforeAuditAndReturn() {
        TestDependencies revoked = new TestDependencies();
        revoked.staleOnRecheckCall = 2;

        FieldProjectionResult revokedResult = revoked.service().project(request("key-state-v1"));

        assertFalse(revokedResult.allowed());
        assertEquals("FIELD_PROJECTION_DECISION_STALE", revokedResult.reasonCode());
        assertTrue(revokedResult.fields().isEmpty());
        assertEquals(1, revoked.decryptCalls.get());
        assertEquals(2, revoked.recheckCalls.get());
        assertEquals(1, revoked.auditCalls.get());
        assertEquals("DECISION_STALE", revoked.lastAudit.result());

        TestDependencies rotated = new TestDependencies();
        rotated.rotateKeyOnSecondCheck = true;

        FieldProjectionResult rotatedResult = rotated.service().project(request("key-state-v1"));

        assertFalse(rotatedResult.allowed());
        assertEquals("FIELD_PROJECTION_KEY_STATE_STALE", rotatedResult.reasonCode());
        assertTrue(rotatedResult.fields().isEmpty());
        assertEquals(1, rotated.decryptCalls.get());
        assertEquals(2, rotated.keyStateCalls.get());
        assertEquals(1, rotated.auditCalls.get());
        assertEquals("KEY_STATE_STALE", rotated.lastAudit.result());
    }

    @Test
    void every_request_reauthorizes_rechecks_and_audits_without_an_allow_cache() {
        TestDependencies dependencies = new TestDependencies();
        CurrentFieldProjectionService service = dependencies.service();

        assertTrue(service.project(request("key-state-v1")).allowed());
        assertTrue(service.project(request("key-state-v1")).allowed());

        assertEquals(2, dependencies.authorizationCalls.get());
        assertEquals(4, dependencies.recheckCalls.get());
        assertEquals(2, dependencies.resolveCalls.get());
        assertEquals(2, dependencies.decryptCalls.get());
        assertEquals(2, dependencies.auditCalls.get());
    }

    private static FieldProjectionRequest request(String keyStateVersion) {
        CompositeAuthorizationRequest authorization = new CompositeAuthorizationRequest(
                "actor-pseudonym-0001",
                "AGGREGATE_REPORT",
                "audit.search-business-metadata",
                "a".repeat(64),
                7,
                Optional.empty(),
                Optional.empty(),
                "0123456789abcdef0123456789abcdef");
        return new FieldProjectionRequest(
                authorization,
                FieldProjectionObjectClass.AUDIT_SEARCH_RECORD,
                new FieldProjectionObjectEvidence(
                        "audit.search-business-metadata",
                        Instant.parse("2026-07-17T08:00:00Z"),
                        Optional.empty(),
                        true,
                        false,
                        false,
                        Set.of(),
                        Optional.empty(),
                        keyStateVersion),
                List.of(
                        new FieldProjectionValueReference(
                                "recordId", "B", "string",
                                new SensitiveValueReference(
                                        "VALUE-REF-RECORD", "BASIC", "kms/audit", "key-v4")),
                        new FieldProjectionValueReference(
                                "actorDisplayRef", "I", "string",
                                new SensitiveValueReference(
                                        "VALUE-REF-ACTOR", "IDENTITY", "kms/audit", "key-v4"))));
    }

    private static final class TestDependencies {
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private final AtomicInteger decryptCalls = new AtomicInteger();
        private final AtomicInteger auditCalls = new AtomicInteger();
        private final AtomicInteger authorizationCalls = new AtomicInteger();
        private final AtomicInteger recheckCalls = new AtomicInteger();
        private final AtomicInteger keyStateCalls = new AtomicInteger();
        private final CountDownLatch recheckEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRecheck = new CountDownLatch(1);
        private CompositeAuthorizationOutcome authorizationOutcome = CompositeAuthorizationOutcome.ALLOW;
        private CompositeAuthorizationRecheckOutcome recheckOutcome = CompositeAuthorizationRecheckOutcome.CURRENT;
        private String currentKeyState = "key-state-v1";
        private boolean blockRecheck;
        private boolean failCrypto;
        private boolean failAudit;
        private boolean failCommit;
        private boolean failAuthorization;
        private boolean rotateKeyOnSecondCheck;
        private int staleOnRecheckCall;
        private SensitiveProjectionAuditRecord lastAudit;

        private CurrentFieldProjectionService service() {
            SensitiveFieldCryptoPort crypto = new SensitiveFieldCryptoPort() {
                @Override
                public FieldCiphertextEnvelope encrypt(
                        SensitiveFieldCryptoContext context, WipeablePlaintext plaintext) {
                    throw new UnsupportedOperationException("test does not write");
                }

                @Override
                public WipeablePlaintext decrypt(
                        SensitiveFieldCryptoContext context, FieldCiphertextEnvelope envelope) {
                    decryptCalls.incrementAndGet();
                    if (failCrypto) {
                        throw new SensitiveFieldCryptoException("FIELD_CRYPTO_DEPENDENCY_UNAVAILABLE");
                    }
                    return WipeablePlaintext.copyOf("AUDIT-0001".getBytes(StandardCharsets.UTF_8));
                }
            };
            SensitiveReadTransactionPort transaction = new SensitiveReadTransactionPort() {
                @Override
                public <T> T execute(java.util.function.Supplier<T> work) {
                    T value = work.get();
                    if (failCommit) {
                        throw new IllegalStateException("commit failed");
                    }
                    return value;
                }
            };
            return new CurrentFieldProjectionService(
                    request -> {
                        authorizationCalls.incrementAndGet();
                        if (failAuthorization) {
                            throw new IllegalStateException("authorization unavailable");
                        }
                        return authorizationDecision(request, authorizationOutcome);
                    },
                    request -> {
                        int call = recheckCalls.incrementAndGet();
                        if (blockRecheck) {
                            recheckEntered.countDown();
                            try {
                                if (!releaseRecheck.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("recheck release timed out");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("recheck interrupted", interrupted);
                            }
                        }
                        CompositeAuthorizationRecheckOutcome outcome = call == staleOnRecheckCall
                                ? CompositeAuthorizationRecheckOutcome.STALE : recheckOutcome;
                        return new CompositeAuthorizationRecheckDecision(
                                outcome,
                                outcome == CompositeAuthorizationRecheckOutcome.CURRENT
                                        ? "DECISION_CURRENT"
                                        : "DECISION_STALE");
                    },
                    new FieldProjectionEvaluator(),
                    FieldProjectionCatalog.approved(),
                    RoleFieldPolicyCatalog.approved(),
                    reference -> {
                        resolveCalls.incrementAndGet();
                        return new FieldCiphertextEnvelope(
                                reference.keyRef(), reference.keyVersion(), new byte[12],
                                new byte[16], "TEST-ONLY");
                    },
                    new SensitiveFieldCryptoService(crypto),
                    () -> {
                        int call = keyStateCalls.incrementAndGet();
                        return rotateKeyOnSecondCheck && call == 2
                                ? "key-state-v2" : currentKeyState;
                    },
                    record -> {
                        auditCalls.incrementAndGet();
                        lastAudit = record;
                        if (failAudit) {
                            throw new IllegalStateException("audit failed");
                        }
                    },
                    transaction,
                    "scholarsense-backend",
                    "production");
        }
    }

    private static CompositeAuthorizationDecision authorizationDecision(
            CompositeAuthorizationRequest request,
            CompositeAuthorizationOutcome outcome) {
        CompositeAuthorizationDecisionToken token = new CompositeAuthorizationDecisionToken(
                1, 2, 3, 4, 5, request.expectedObjectVersion(), "RFP-1.0.0");
        return new CompositeAuthorizationDecision(
                outcome,
                outcome == CompositeAuthorizationOutcome.ALLOW
                        ? "ROLE_SCOPE_MATCHED"
                        : "SCOPE_NOT_PROVEN",
                outcome == CompositeAuthorizationOutcome.ALLOW
                        ? Set.of("R3-STUDENT-AFFAIRS")
                        : Set.of(),
                outcome == CompositeAuthorizationOutcome.ALLOW
                        ? Set.of("SCHOOL_GOVERNANCE")
                        : Set.of(),
                outcome == CompositeAuthorizationOutcome.ALLOW
                        ? Map.of("B", FieldVisibility.CLEAR, "I", FieldVisibility.MASKED)
                        : Map.of(),
                Set.of(),
                "RFP-1.0.0",
                request.expectedObjectVersion(),
                Instant.parse("2026-07-17T08:00:00Z"),
                token);
    }
}
