package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcCurrentNaturalPersonBindingQueryAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcHighRiskApprovalRepository;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcHighRiskExecutionLeaseRepository;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalService;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskExecutionAuthorizationService;
import cn.edu.suda.scholarsense.identityaccess.api.RecoveryCheckerBindingResolver;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskApprovalUseCase;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskEvidenceSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskExecutionAuthorizationUseCase;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenQualityRecoveryPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenRecoverySourceClassRegistryLoader;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcQualityRecoveryCommandStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcRecoveryBackfillAndReconciliationAdapter;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcRecoveryValidationExternalWork;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcRecoveryValidationWork;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.SignalEvaluationRecoverySampleRecomputeAdapter;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseRecoveryService;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryAuthorizationGuard;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryCommandActor;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryExecutionCommit;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryConfirmationRelayProcessor;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryValidationJobProcessor;
import cn.edu.suda.scholarsense.rulegovernance.adapters.outbound.JdbcRuleVersionBusinessOwnerBindingQueryAdapter;
import cn.edu.suda.scholarsense.signalevaluation.adapters.outbound.JdbcRecoverySampleNormalizedInputResolver;
import cn.edu.suda.scholarsense.signalevaluation.adapters.outbound.JdbcRecoverySampleNormalizedInputStore;
import cn.edu.suda.scholarsense.signalevaluation.adapters.outbound.JdbcRecoverySampleReplayStore;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleRecomputeProvider;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleProviderTimePort;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleRecomputeUseCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL proof that immutable owner facts, the real provider and D4 projection close. */
class QualityRecoveryRuntimePostgreSqlIT {
    private static final String ONLINE_LOGIN = "scholarsense_iq_recovery_online_test_login";
    private static final String WORKER_LOGIN = "scholarsense_iq_recovery_worker_test_login";
    private static final String SIGNAL_LOGIN = "scholarsense_se_recovery_worker_test_login";
    private static final String IDENTITY_LOGIN = "scholarsense_ia_recovery_online_test_login";
    private static final String RULE_LOGIN = "scholarsense_rg_recovery_reader_test_login";
    private static final String SOURCE = "SRC-P0-CAMPUS-ACCESS-001";
    private static final String DEPENDENCY = "DEP-P0-CAMPUS-ACCESS-001";
    private static final String RULE = "ACC-SAFE-001";
    private static final String TRACE = "11111111111111111111111111111111";
    private static final UUID MAKER_ACCOUNT = uuid(6001);
    private static final UUID CHECKER_ACCOUNT = uuid(6002);
    private static final String BUSINESS_OWNER =
            "sha256:bbd8062ac9da3d5c6f68b739c3af8225630cfbea4940cb53fb6f874206c6b2de";
    private static final String QRP =
            "sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366";
    private static final String QG =
            "sha256:0a6701cc598be754813bdddbe7e0cbff7b65c1e03ca2cd026d63ee2d366a4e3a";
    private static final String QMDP =
            "sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8";
    private static final String QSHM =
            "sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void threeApprovedStreamingBatchesAndRealSampleProviderProduceSelfContainedD4Evidence()
            throws Exception {
        Fixture fixture = fixture();
        try {
            var outcome = fixture.processor().runBatch();

            assertEquals(1, outcome.claimed());
            assertEquals(1, outcome.succeeded());
            String validationResult = fixture.admin().queryForObject("""
                    select result::text from ingestion_quality.iq_recovery_validation_result
                     where recovery_request_id=?
                    """, String.class, fixture.requestId());
            assertTrue(tree(validationResult).required("qualified").asBoolean(), validationResult);
            assertEquals("validation-succeeded", fixture.admin().queryForObject("""
                    select status from ingestion_quality.iq_quality_recovery_request_current
                     where recovery_request_id=?
                    """, String.class, fixture.requestId()));
            JsonNode evidence = tree(fixture.admin().queryForObject("""
                    select evidence::text from ingestion_quality.iq_quality_recovery_evidence_pack
                     where recovery_request_id=?
                    """, String.class, fixture.requestId()));
            JsonNode preview = tree(fixture.admin().queryForObject("""
                    select preview::text from ingestion_quality.iq_quality_recovery_preview
                     where recovery_request_id=? and invalidated_at is null
                    """, String.class, fixture.requestId()));
            assertEquals("QUALITY-RECOVERY-EVIDENCE-PACK-1.1.0",
                    evidence.required("schemaVersion").asText());
            assertEquals("installed-and-verified",
                    evidence.required("runtimeEvidenceClaim").asText());
            assertEquals(3, evidence.at("/batchEvidence/actualConsecutivePassedBatches").asLong());
            assertEquals(101, evidence.at("/sampleEvidence/populationCount").asLong());
            assertEquals(100, evidence.at("/sampleEvidence/selectedCount").asLong());
            assertEquals(0, evidence.at("/sampleEvidence/mismatchCount").asLong());
            assertTrue(evidence.at("/sampleEvidence/strata").isArray());
            assertEquals("QUALITY-RECOVERY-IMPACT-PREVIEW-1.0.0",
                    preview.required("schemaVersion").asText());
            assertEquals("recovering", preview.required("targetState").asText());
            assertFalse(preview.required("authorizesExecution").asBoolean());
            assertEquals("PT60M", preview.required("observationDuration").asText());
            assertEquals("2.5c", preview.required("finalActionabilityOwnerStory").asText());
            QualityRecoveryExecutionCommit commit = executeApprovedRecovery(fixture);
            assertEquals("recovering", commit.state());
            assertTrue(commit.transitionApplied());
            assertEquals("recovering", fixture.admin().queryForObject("""
                    select status from ingestion_quality.iq_quality_eligibility_current
                     where rule_id=? and rule_version='1.0.0'
                    """, String.class, RULE));
            assertEquals("open", fixture.admin().queryForObject("""
                    select status from ingestion_quality.iq_quality_recovery_task_current
                     where task_id=?
                    """, String.class, commit.taskId()));
            assertTrue(fixture.admin().queryForObject("""
                    select active from ingestion_quality.iq_quality_fuse_episode_current
                     where episode_id=?
                    """, Boolean.class, commit.episodeId()));
            assertEquals(1, fixture.admin().queryForObject("""
                    select count(*) from ingestion_quality.iq_quality_recovery_execution_jti
                    """, Integer.class));
            assertEquals(1, fixture.admin().queryForObject("""
                    select count(*) from ingestion_quality.iq_quality_recovery_audit
                     where recovery_request_id=? and outcome='accepted'
                    """, Integer.class, fixture.requestId()));
            assertEquals(1, fixture.admin().queryForObject("""
                    select count(*) from ingestion_quality.iq_quality_recovery_outbox
                     where recovery_request_id=? and status='pending'
                    """, Integer.class, fixture.requestId()));
            assertEquals(1, fixture.admin().queryForObject("""
                    select count(*) from ingestion_quality.iq_quality_recovery_outbox
                     where recovery_request_id=? and status='delivered'
                       and event_type=
                         'scholarsense.ingestion-quality.recovery-lease.confirmation.v1'
                    """, Integer.class, fixture.requestId()));
            assertNotNull(fixture.admin().queryForObject("""
                    select confirmed_at from ingestion_quality.iq_quality_recovery_execution_jti
                     where recovery_request_id=?
                    """, Instant.class, fixture.requestId()));
            assertEquals("executed", fixture.admin().queryForObject("""
                    select state from identity_access.ia_high_risk_execution_lease_current
                     where execution_jti=?
                    """, String.class, commit.executionJti()));
        } finally {
            fixture.close();
        }
    }

    @Test
    void currentMemberDriftChangesTheEvidenceFenceAndRejectsTheSealedResult() throws Exception {
        Fixture fixture = fixture();
        try {
            JdbcRecoveryValidationWork work = fixture.work();
            Instant now = fixture.workerNow();
            var claim = work.claim(fixture.jobId(), digest("worker"), now, java.time.Duration.ofSeconds(120));
            var external = fixture.external();
            var first = external.execute(new cn.edu.suda.scholarsense.ingestionquality.application
                    .RecoveryValidationExecutionRequest(fixture.jobId(), claim.leaseGeneration(), 0, null));
            assertTrue(work.checkpoint(fixture.jobId(), claim.leaseGeneration(), 0,
                    first.checkpoint(), fixture.workerNow()));
            var second = external.execute(new cn.edu.suda.scholarsense.ingestionquality.application
                    .RecoveryValidationExecutionRequest(fixture.jobId(), claim.leaseGeneration(), 1,
                            first.checkpoint()));
            assertTrue(work.checkpoint(fixture.jobId(), claim.leaseGeneration(), 1,
                    second.checkpoint(), fixture.workerNow()));
            var completed = external.execute(new cn.edu.suda.scholarsense.ingestionquality.application
                    .RecoveryValidationExecutionRequest(fixture.jobId(), claim.leaseGeneration(), 2,
                            second.checkpoint()));
            String sealed = completed.result().readinessEvidence().evidenceDigest();

            driftCurrentMember(fixture.admin());
            String current = tree(fixture.online().queryForObject("""
                    select ingestion_quality.iq_build_quality_recovery_readiness_evidence(?,?)::text
                    """, String.class, fixture.requestId(),
                    Timestamp.from(completed.result().readinessEvidence().trustedStartedAt())))
                    .required("evidenceDigest").asText();
            assertNotEquals(sealed, current);
            assertFalse(work.complete(fixture.jobId(), claim.leaseGeneration(),
                    completed.result(), fixture.workerNow()));
            assertEquals(0, fixture.admin().queryForObject("""
                    select count(*) from ingestion_quality.iq_quality_recovery_evidence_pack
                     where recovery_request_id=?
                    """, Integer.class, fixture.requestId()));
        } finally {
            fixture.close();
        }
    }

    private static Fixture fixture() throws Exception {
        JdbcTemplate admin = admin();
        cleanup(admin);
        createLogins(admin);
        Instant now = admin.queryForObject(
                "select date_trunc('microseconds',clock_timestamp())", Instant.class);
        QualityRecoveryCommandActor maker = actor(MAKER_ACCOUNT, "maker", now.plusSeconds(3600));
        QualityRecoveryCommandActor checker = actor(
                CHECKER_ACCOUNT, "checker", now.plusSeconds(3600));
        seedCheckerIdentity(admin, now);
        seedQualityFacts(admin, now);
        UUID episodeId = uuid(7001);
        UUID taskId = uuid(7002);
        UUID eligibilityId = uuid(7003);
        seedFuseOwnerFacts(admin, now, episodeId, taskId, eligibilityId);
        seedWindows(admin, now);

        JdbcTemplate online = new JdbcTemplate(login(ONLINE_LOGIN));
        JsonNode context = tree(online.queryForObject("""
                select ingestion_quality.iq_load_quality_recovery_command_context(?)::text
                """, String.class, taskId));
        UUID requestId = uuid(7101);
        String selectionSeed = digest("selection-seed");
        LinkedHashMap<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("recoveryRequestId", requestId.toString());
        aggregate.put("requestVersion", 1);
        aggregate.put("taskId", taskId.toString());
        aggregate.put("taskVersion", 1);
        aggregate.put("episodeId", episodeId.toString());
        aggregate.put("episodeVersion", 1);
        aggregate.put("episodeGeneration", 1);
        aggregate.put("sourceId", SOURCE);
        aggregate.put("dependencyId", DEPENDENCY);
        aggregate.put("actionType", "quality-fuse.recover");
        aggregate.put("currentState", "fused");
        aggregate.put("targetState", "recovering");
        aggregate.put("status", "requested");
        aggregate.put("affectedRuleVersionsDigest",
                context.required("affectedRuleVersionsDigest").asText());
        aggregate.put("memberSetDigest", context.required("memberSetDigest").asText());
        aggregate.put("watermarksDigest", context.required("watermarksDigest").asText());
        aggregate.put("qrpVersion", "QRP-1.0.0");
        aggregate.put("qrpDigest", QRP);
        aggregate.put("makerPrincipalDigest", maker.naturalPersonPrincipalDigest());
        aggregate.put("authorizationContextDigest", authorizationContextDigest(1));
        aggregate.put("authenticationStateDigest", authenticationStateDigest(maker));
        aggregate.put("authorizationGeneration", 7);
        aggregate.put("scopeDigest", digest(SOURCE));
        aggregate.put("impactScopeDigest",
                context.required("affectedRuleVersionsDigest").asText());
        aggregate.put("selectionSeed", selectionSeed);
        aggregate.put("reasonCode", "QUALITY_EVIDENCE_REVALIDATION_REQUESTED");
        aggregate.put("requestedAt", now.toString());
        aggregate.put("requestDigest", digest("request"));
        aggregate.put("idempotencyInputDigest", digest("request-input"));
        aggregate.put("traceId", TRACE);
        online.queryForObject("""
                select ingestion_quality.iq_submit_quality_recovery_request(?,?::jsonb)::text
                """, String.class, digest("request-key"), JSON.writeValueAsString(aggregate));

        UUID jobId = uuid(7102);
        String inputDigest = digest("validation-input");
        Map<String, Object> binding = Map.ofEntries(
                Map.entry("recoveryRequestId", requestId.toString()),
                Map.entry("episodeId", episodeId.toString()),
                Map.entry("taskId", taskId.toString()),
                Map.entry("inputDigest", inputDigest),
                Map.entry("qualityRecoveryPolicyVersion", "QRP-1.0.0"),
                Map.entry("qualityRecoveryPolicyDigest", QRP),
                Map.entry("ruleVersionsDigest",
                        context.required("affectedRuleVersionsDigest").asText()),
                Map.entry("memberSetDigest", context.required("memberSetDigest").asText()),
                Map.entry("watermarksDigest", context.required("watermarksDigest").asText()),
                Map.entry("selectionSeed", selectionSeed), Map.entry("traceId", TRACE));
        Map<String, Object> job = Map.of(
                "jobId", jobId.toString(), "jobVersion", 1,
                "recoveryRequestId", requestId.toString(), "inputDigest", inputDigest,
                "binding", binding, "status", "queued", "attemptCount", 0,
                "leaseGeneration", 0);
        online.queryForObject("""
                select ingestion_quality.iq_submit_recovery_validation_job(?,?::jsonb)::text
                """, String.class, digest("validation-key"), JSON.writeValueAsString(job));

        JdbcTemplate worker = new JdbcTemplate(login(WORKER_LOGIN));
        JdbcTemplate signal = new JdbcTemplate(login(SIGNAL_LOGIN));
        var providerTime = new RecoverySampleProviderTimePort() {
            @Override public long monotonicNanos() { return System.nanoTime(); }
            @Override public Instant trustedNow() { return dbNow(signal); }
        };
        var sampleProvider = new RecoverySampleRecomputeProvider(
                new RecoverySampleRecomputeUseCase(
                        new JdbcRecoverySampleNormalizedInputResolver(
                                signal, JSON, providerTime::trustedNow),
                        providerTime, new JdbcRecoverySampleReplayStore(signal, JSON)));
        var sampleInput = new JdbcRecoverySampleNormalizedInputStore(signal, JSON);
        var sampleAdapter = new SignalEvaluationRecoverySampleRecomputeAdapter(sampleProvider);
        var policy = FrozenQualityRecoveryPolicyLoader.load(Path.of("..", "contracts"), JSON);
        var registry = FrozenRecoverySourceClassRegistryLoader.load(
                Path.of("..", "contracts"), JSON);
        var work = new JdbcRecoveryValidationWork(worker, JSON);
        var historicalWork = new JdbcRecoveryBackfillAndReconciliationAdapter(worker, JSON);
        var external = new JdbcRecoveryValidationExternalWork(
                worker, JSON, sampleInput, sampleAdapter, historicalWork, historicalWork,
                policy, registry,
                () -> dbNow(worker), () -> uuid(7201));
        var processor = new RecoveryValidationJobProcessor(
                work, external, () -> dbNow(worker), digest("worker"));
        return new Fixture(admin, online, worker, requestId, jobId, work, external, processor,
                maker, checker);
    }

    private static QualityRecoveryExecutionCommit executeApprovedRecovery(Fixture fixture) {
        JdbcTemplate identity = new JdbcTemplate(login(IDENTITY_LOGIN));
        JdbcTemplate rules = new JdbcTemplate(login(RULE_LOGIN));
        AtomicInteger sequence = new AtomicInteger(7300);
        java.util.function.Supplier<UUID> ids = () -> uuid(sequence.incrementAndGet());
        HighRiskEvidenceSignaturePort signatures = canonical ->
                new HighRiskEvidenceSignaturePort.SignedValue(
                        "test-k1", "A".repeat(43), digest(canonical));
        JdbcHighRiskApprovalRepository approvalRepository =
                new JdbcHighRiskApprovalRepository(identity, JSON);
        var approvalPort = new HighRiskApprovalService(
                new HighRiskApprovalUseCase(approvalRepository, ids::get, signatures));
        JdbcHighRiskExecutionLeaseRepository leaseRepository =
                new JdbcHighRiskExecutionLeaseRepository(identity, JSON);
        var executionPort = new HighRiskExecutionAuthorizationService(
                new HighRiskExecutionAuthorizationUseCase(
                        approvalRepository, leaseRepository, ids::get, signatures,
                        () -> dbNow(identity)));
        var ownerBindings = new JdbcRuleVersionBusinessOwnerBindingQueryAdapter(rules, JSON);
        var ownerProbe = ownerBindings.resolve(
                new cn.edu.suda.scholarsense.rulegovernance.api
                        .RuleVersionBusinessOwnerBindingQuery(
                                List.of(digest(RULE + "@1.0.0")), null,
                                dbNow(identity), TRACE));
        assertEquals(cn.edu.suda.scholarsense.rulegovernance.api
                        .RuleVersionBusinessOwnerBindingResult.Availability.AVAILABLE,
                ownerProbe.availability(), ownerProbe.reasonCode());
        assertEquals(List.of(digest(RULE + "@1.0.0")), ownerProbe.bindings().stream()
                .map(value -> value.ruleVersionDigest()).toList(), ownerProbe.toString());
        RecoveryCheckerBindingResolver checkers = new RecoveryCheckerBindingResolver(
                ownerBindings,
                new JdbcCurrentNaturalPersonBindingQueryAdapter(identity, JSON));
        RecoveryCheckerBindingResolver.Resolution checkerResolution = checkers.resolve(
                List.of(digest(RULE + "@1.0.0")), null, dbNow(identity), TRACE);
        assertTrue(checkerResolution.available(), checkerResolution.reasonCode());
        CompositeAuthorizationPort authorization = request -> authorizationDecision(
                request.expectedObjectVersion(), dbNow(fixture.online()));
        CompositeAuthorizationRecheckPort recheck = ignored ->
                new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT,
                        "AUTHORIZATION_CURRENT");
        QualityFuseRecoveryService service = new QualityFuseRecoveryService(
                new JdbcQualityRecoveryCommandStore(fixture.online(), JSON), authorization,
                new QualityRecoveryAuthorizationGuard(authorization, recheck), checkers,
                approvalPort, executionPort, () -> dbNow(fixture.online()), ids);

        var pending = service.requestApproval(
                fixture.requestId(), 3, "approval-request-key", fixture.maker(), TRACE);
        assertEquals("approval-pending", pending.status());
        var approved = service.decide(
                fixture.requestId(), 4, 1, "approve", "approval-decision-key",
                fixture.checker(), TRACE);
        assertEquals("approval-approved", approved.status());
        QualityRecoveryExecutionCommit commit = service.execute(
                fixture.requestId(), 5, "execution-key", fixture.maker(), TRACE);
        QualityRecoveryExecutionCommit replay = service.execute(
                fixture.requestId(), 5, "execution-key", fixture.maker(), TRACE);
        assertEquals(commit, replay);
        var confirmationStore = new JdbcQualityRecoveryCommandStore(fixture.online(), JSON);
        var confirmationRelay = new QualityRecoveryConfirmationRelayProcessor(
                confirmationStore, executionPort, () -> dbNow(fixture.online()));
        assertEquals(1, confirmationRelay.runBatch(100));
        assertEquals(0, confirmationRelay.runBatch(100));
        return commit;
    }

    private static CompositeAuthorizationDecision authorizationDecision(
            long objectVersion, Instant now) {
        return new CompositeAuthorizationDecision(
                CompositeAuthorizationOutcome.ALLOW, "AUTHORIZED",
                java.util.Set.of("R6-DATA-OWNER"), java.util.Set.of("OWNED_SOURCE"),
                Map.of(), java.util.Set.of(), "RFP-1.0.0", objectVersion, now,
                new CompositeAuthorizationDecisionToken(
                        1, 1, 1, 7, 1, objectVersion, "RFP-1.0.0"));
    }

    private static String authorizationContextDigest(long objectVersion) {
        return digest(String.join("\n", "RFP-1.0.0", "1", "1", "1", "7", "1",
                Long.toString(objectVersion)));
    }

    private static String authenticationStateDigest(QualityRecoveryCommandActor actor) {
        return digest(String.join("\n", actor.sessionPseudonym(),
                Long.toString(actor.sessionVersion()), actor.sessionExpiresAt().toString(),
                actor.identityProfileVersion()));
    }

    private static QualityRecoveryCommandActor actor(
            UUID accountId, String name, Instant expiresAt) {
        return new QualityRecoveryCommandActor(
                "session-" + name, "actor-" + name, accountId,
                digest(name + "-natural-person"), digest(name + "-person-binding"),
                java.util.Set.of("R6-DATA-OWNER"), 1, expiresAt, "ISP-1.0.0");
    }

    private static void seedCheckerIdentity(JdbcTemplate jdbc, Instant now) {
        seedAccountNaturalPerson(
                jdbc, MAKER_ACCOUNT, "maker", digest("maker-natural-person"), now);
        seedAccountNaturalPerson(
                jdbc, CHECKER_ACCOUNT, "checker", digest("checker-natural-person"), now);
        String checkerPrincipal = digest("checker-natural-person");
        jdbc.update("""
                insert into identity_access.ia_business_owner_natural_person_history(
                  business_owner_key_digest,binding_version,account_id,
                  natural_person_principal_digest,effective_from,effective_to,
                  authorization_generation,recorded_at,legal_hold)
                values (?,1,?,?,?,null,7,?,false)
                """, BUSINESS_OWNER, CHECKER_ACCOUNT, checkerPrincipal,
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now));
        jdbc.update("""
                insert into identity_access.ia_business_owner_natural_person_current(
                  business_owner_key_digest,binding_version,account_id,
                  natural_person_principal_digest,effective_from,effective_to,
                  authorization_generation,legal_hold)
                values (?,1,?,?,?,null,7,false)
                """, BUSINESS_OWNER, CHECKER_ACCOUNT, checkerPrincipal,
                Timestamp.from(now.minusSeconds(60)));
    }

    private static void seedAccountNaturalPerson(
            JdbcTemplate jdbc, UUID accountId, String name,
            String personPrincipal, Instant now) {
        jdbc.update("""
                insert into identity_access.ia_authoritative_account_current(
                  account_id,source_id,feed_id,partition_id,consumer_projection,
                  external_ref_digest,subject_binding_token,status,effective_from,effective_to,
                  source_version,source_watermark,aggregate_version,mapping_version,applied_at,
                  trace_id,retention_schedule_version,retention_owner,retention_effective_at,
                  legal_hold)
                values (?,'SRC-P0-RESPONSIBILITY-001','recovery-e2e','p0','identity-org',
                  ?,?,'active',?,null,1,1,1,'IDENTITY-ROLE-MAPPING-1.0.0',?,?,'RS-1.0.0',
                  'identity-access',(?::timestamptz + interval '2 years'),false)
                """, accountId, digestHex(name + "-external"),
                "binding_v1_k1_" + digestHex(name + "-binding"),
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now), TRACE,
                Timestamp.from(now));
        jdbc.update("""
                insert into identity_access.ia_account_natural_person_history(
                  account_id,binding_version,natural_person_principal_digest,
                  authority_evidence_digest,effective_from,effective_to,recorded_at,
                  legal_hold)
                values (?,1,?,?,?,null,?,false)
                """, accountId, personPrincipal, digest(name + "-person-authority"),
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now));
        jdbc.update("""
                insert into identity_access.ia_account_natural_person_current(
                  account_id,binding_version,natural_person_principal_digest,
                  authority_evidence_digest,effective_from,effective_to,legal_hold)
                values (?,1,?,?,?,null,false)
                """, accountId, personPrincipal, digest(name + "-person-authority"),
                Timestamp.from(now.minusSeconds(60)));
    }

    private static void seedQualityFacts(JdbcTemplate jdbc, Instant now) {
        List<Source> sources = List.of(
                new Source("SRC-P0-ACCOMMODATION-001", "DEP-P0-ACCOMMODATION-001", "ACCOMMODATION-SLICE-1.0.0"),
                new Source("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", "BC-1.0.0"),
                new Source(SOURCE, DEPENDENCY, "CAMPUS-ACCESS-SLICE-1.0.0"),
                new Source("SRC-P0-DEVICE-001", "DEP-P0-DEVICE-001", "DEVICE-SLICE-1.0.0"),
                new Source("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001", "DORM-ACCESS-SLICE-1.0.0"),
                new Source("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001", "LEAVE-SLICE-1.0.0"),
                new Source("SRC-P0-TIMETABLE-001", "DEP-P0-TIMETABLE-001", "TIMETABLE-SLICE-1.0.0"));
        int ordinal = 0;
        jdbc.execute("alter table ingestion_quality.iq_data_batch disable trigger iq_data_batch_state_guard");
        jdbc.execute("alter table ingestion_quality.iq_quality_snapshot disable trigger iq_quality_snapshot_insert_guard");
        try {
            for (Source source : sources) {
                int versions = SOURCE.equals(source.sourceId()) ? 3 : 1;
                for (int version = 1; version <= versions; version++) {
                ordinal++;
                UUID batch = uuid(1000 + ordinal);
                UUID snapshot = uuid(2000 + ordinal);
                UUID lineage = uuid(3000 + ordinal);
                byte[] business = (source.sourceId() + ":" + version)
                        .getBytes(StandardCharsets.UTF_8);
                Instant received = now.minusSeconds(10_800 - ordinal);
                Instant sealed = received.plusSeconds(10);
                Instant evaluated = sealed.plusSeconds(10);
                Instant published = evaluated.plusSeconds(10);
                jdbc.update("""
                        insert into ingestion_quality.iq_data_batch(
                          batch_id,source_id,business_key_utf8,business_key_digest,source_version,
                          lineage_id,effective_at,declared_manifest_digest,status,aggregate_version,
                          record_count,valid_record_count,rejected_record_count,
                          observation_start_at,observation_end_at,cutoff_at,business_timezone,
                          watermark_utf8,source_schema_version,source_schema_digest,
                          data_catalog_version,data_catalog_digest,quality_gate_version,
                          quality_gate_digest,qmdp_version,qmdp_digest,source_occurred_at,
                          scheduled_due_at,lane_id,sealed_contract_evidence,received_at,sealed_at,
                          evaluated_at,published_at,trace_id)
                        values (?,?,?,encode(sha256(?),'hex'),?,?,?,'sha256:'||repeat('9',64),
                          'published',4,100,100,0,?,?,?,'Asia/Shanghai',?,?,?,
                          'DCC-1.1.0','sha256:aeb19962071e2144a85bb2e07fd124eff0d69edf93f7202e821204616a60f219',
                          'QG-1.0.0',?,'QMDP-1.0.0',?,?,?,?,'{"story":"2.5b"}'::jsonb,
                          ?,?,?,?,?)
                        """, batch, source.sourceId(), business, business, version, lineage,
                        Timestamp.from(received), Timestamp.from(received),
                        Timestamp.from(received.plusSeconds(60)), Timestamp.from(received.plusSeconds(60)),
                        ("wm-" + source.sourceId() + "-" + version).getBytes(StandardCharsets.UTF_8),
                        source.schemaVersion(), digest("schema:" + source.sourceId()), QG, QMDP,
                        Timestamp.from(received), Timestamp.from(received), "recovery-test",
                        Timestamp.from(received), Timestamp.from(sealed), Timestamp.from(evaluated),
                        Timestamp.from(published), TRACE);
                String immutable = digest("snapshot:" + source.sourceId() + ":" + version);
                jdbc.update("""
                        insert into ingestion_quality.iq_quality_snapshot(
                          snapshot_id,batch_id,domain_tag,hash_profile_version,hash_profile_digest,
                          source_id,assessed_batch_status,overall_result,observation_start_at,
                          observation_end_at,cutoff_at,watermark_utf8,source_owner_ref,approval_ref,
                          effective_at,retention_schedule_version,qmdp_version,qmdp_digest,
                          quality_gate_version,quality_gate_digest,canonicalization_profile,
                          manifest_digest,source_schema_version,source_schema_digest,lineage_id,
                          evaluated_at,trace_id,aggregate_version,immutable_hash,retention_due_at,
                          legal_hold,retention_scope_digest)
                        values (?,?,'scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1',
                          'QSHM-1.0.0',?,?,'quality-passed','quality-passed',?,?,?,?,?,
                          'AUTH-2026-08-08-001',?,'RS-1.0.0','QMDP-1.0.0',?,'QG-1.0.0',?,
                          'SCHOLARSENSE-CANONICAL-JSON-1.0.0','sha256:'||repeat('9',64),?,?,?,
                          ?,?,3,?,(?::timestamptz + interval '2 years'),false,?)
                        """, snapshot, batch, QSHM, source.sourceId(), Timestamp.from(received),
                        Timestamp.from(received.plusSeconds(60)), Timestamp.from(received.plusSeconds(60)),
                        ("wm-" + source.sourceId() + "-" + version).getBytes(StandardCharsets.UTF_8),
                        source.sourceId(), Timestamp.from(received), QMDP, QG,
                        source.schemaVersion(), digest("schema:" + source.sourceId()), lineage,
                        Timestamp.from(evaluated), TRACE, immutable, Timestamp.from(evaluated),
                        digest("retention:" + source.sourceId()));
                jdbc.update("""
                        insert into ingestion_quality.iq_quality_snapshot_metric(
                          snapshot_id,metric_ordinal,metric_id,formula_id,formula_version,result,
                          applicable,numerator,denominator,value_basis_points,unit,operator,
                          threshold_numerator,threshold_denominator,boundary,reason_code)
                        values (?,0,'completeness',?,'1.0.0','passed',true,100,100,10000,
                          'basis-point','>=',99,100,'inclusive',null)
                        """, snapshot, "QMDP-1.0.0/" + source.sourceId() + "/completeness");
                    if (version == versions) {
                    jdbc.update("""
                            insert into ingestion_quality.iq_dependency_quality_current(
                              dependency_id,source_id,source_version,dependency_version,lineage_id,
                              lineage_revision,status,version_continuous,watermark_utf8,snapshot_id,
                              snapshot_immutable_hash,qmdp_version,qmdp_digest,qshm_version,
                              qshm_digest,updated_at)
                            values (?,?,?,?,?,0,'eligible',true,?, ?,?,'QMDP-1.0.0',?,
                              'QSHM-1.0.0',?,?)
                            """, source.dependencyId(), source.sourceId(), version, version, lineage,
                            ("wm-" + source.sourceId() + "-" + version).getBytes(StandardCharsets.UTF_8),
                            snapshot, immutable, QMDP, QSHM, Timestamp.from(now));
                    }
                }
            }
        } finally {
            jdbc.execute("alter table ingestion_quality.iq_quality_snapshot enable trigger iq_quality_snapshot_insert_guard");
            jdbc.execute("alter table ingestion_quality.iq_data_batch enable trigger iq_data_batch_state_guard");
        }
        jdbc.update("""
                insert into ingestion_quality.iq_quality_dependency_cursor(
                  source_id,dependency_id,source_version,lineage_id,lineage_revision,batch_id,
                  stage,paused,aggregate_version,updated_at)
                select source_id,?,source_version,lineage_id,0,batch_id,'terminal',false,3,?
                  from ingestion_quality.iq_data_batch where source_id=? and source_version=3
                """, DEPENDENCY, Timestamp.from(now), SOURCE);
    }

    private static void seedFuseOwnerFacts(
            JdbcTemplate jdbc, Instant now, UUID episodeId, UUID taskId, UUID eligibilityId) {
        jdbc.update("""
                insert into ingestion_quality.iq_quality_eligibility_history(
                  eligibility_id,rule_id,rule_version,registry_version,registry_digest,
                  catalog_version,catalog_digest,rule_catalog_version,rule_catalog_digest,
                  aggregate_version,status,reason_code,composition_operator,threshold,
                  effective_at,occurred_at,trace_id,producer,retention_due_at,legal_hold)
                select ?,?,'1.0.0',registry_version,registry_digest,catalog_version,catalog_digest,
                  rule_catalog_version,rule_catalog_digest,1,'fused','REQUIRED_MEMBER_FUSED',
                  'all-of',null,?,?,?,'ingestion-quality',(?::timestamptz + interval '2 years'),false
                  from ingestion_quality.iq_rule_dependency_registry
                 where registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
                """, eligibilityId, RULE, Timestamp.from(now.minusSeconds(120)),
                Timestamp.from(now.minusSeconds(60)), TRACE, Timestamp.from(now.minusSeconds(60)));
        jdbc.update("""
                insert into ingestion_quality.iq_quality_eligibility_current(
                  rule_id,rule_version,registry_version,eligibility_id,aggregate_version,status,
                  reason_code,composition_operator,threshold,effective_at,occurred_at)
                values (?,'1.0.0','RULE-DEPENDENCY-REGISTRY-1.0.0',?,1,'fused',
                  'REQUIRED_MEMBER_FUSED','all-of',null,?,?)
                """, RULE, eligibilityId, Timestamp.from(now.minusSeconds(120)),
                Timestamp.from(now.minusSeconds(60)));
        jdbc.update("""
                insert into ingestion_quality.iq_quality_eligibility_member_history
                select ?,1,member.member_ordinal,member.source_id,dependency.source_version,
                  member.dependency_id,dependency.dependency_version,member.requirement,'eligible',
                  true,dependency.watermark_utf8,dependency.watermark_utf8,dependency.snapshot_id,
                  trim(dependency.snapshot_immutable_hash),'QMDP-1.0.0',?,
                  'QSHM-1.0.0',?,dependency.lineage_id,false
                  from ingestion_quality.iq_rule_dependency_member member
                  join ingestion_quality.iq_dependency_quality_current dependency
                    on dependency.dependency_id=member.dependency_id
                 where member.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
                   and member.rule_id=? and member.rule_version='1.0.0'
                 order by member.member_ordinal
                """, eligibilityId, QMDP, QSHM, RULE);
        UUID triggerBatch = jdbc.queryForObject("""
                select batch_id from ingestion_quality.iq_data_batch
                 where source_id=? and source_version=1
                """, UUID.class, SOURCE);
        UUID triggerSnapshot = jdbc.queryForObject("""
                select snapshot_id from ingestion_quality.iq_quality_snapshot
                 where batch_id=?
                """, UUID.class, triggerBatch);
        String triggerHash = jdbc.queryForObject("""
                select trim(immutable_hash) from ingestion_quality.iq_quality_snapshot
                 where snapshot_id=?
                """, String.class, triggerSnapshot);
        jdbc.update("""
                insert into ingestion_quality.iq_quality_fuse_episode_history(
                  episode_id,generation,aggregate_version,source_id,dependency_id,
                  work_item_key_version,status,trigger_event_id,trigger_batch_id,
                  trigger_snapshot_id,trigger_snapshot_hash,trigger_reason_code,
                  dependency_version,evidence,transitions,watermark_utf8,occurred_at,trace_id,
                  legal_hold)
                values (?,1,1,?,?,'k7','active',?,?,?,?, 'REQUIRED_MEMBER_FUSED',3,
                  '{"story":"2.5b"}'::jsonb,'[]'::jsonb,convert_to('wm-campus-3','UTF8'),?,?,false)
                """, episodeId, SOURCE, DEPENDENCY, uuid(7004), triggerBatch,
                triggerSnapshot, triggerHash, Timestamp.from(now.minusSeconds(60)), TRACE);
        jdbc.update("""
                insert into ingestion_quality.iq_quality_fuse_episode_current(
                  episode_id,source_id,dependency_id,work_item_key_version,generation,
                  aggregate_version,active,updated_at)
                values (?,?,?,'k7',1,1,true,?)
                """, episodeId, SOURCE, DEPENDENCY, Timestamp.from(now.minusSeconds(60)));
        jdbc.update("""
                insert into ingestion_quality.iq_quality_recovery_task_history(
                  task_id,aggregate_version,episode_id,episode_generation,work_item_key,
                  work_item_key_version,source_id,dependency_id,status,priority,due_at,owner_ref,
                  trigger,current_evidence,watermark_utf8,occurred_at,legal_hold)
                values (?,1,?,1,?,'k7',?,?,'open','P0',?,'owner:test',
                  '{"story":"2.5b"}'::jsonb,'{"state":"fused"}'::jsonb,
                  convert_to('wm-campus-3','UTF8'),?,false)
                """, taskId, episodeId, "qf:" + "a".repeat(64), SOURCE, DEPENDENCY,
                Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now.minusSeconds(60)));
        jdbc.update("""
                insert into ingestion_quality.iq_quality_recovery_task_current(
                  task_id,episode_id,episode_generation,work_item_key,work_item_key_version,
                  source_id,dependency_id,aggregate_version,status,priority,due_at,owner_ref,
                  occurred_at,updated_at)
                values (?,?,1,?,'k7',?,?,1,'open','P0',?,'owner:test',?,?)
                """, taskId, episodeId, "qf:" + "a".repeat(64), SOURCE, DEPENDENCY,
                Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.minusSeconds(60)));
        jdbc.update("""
                insert into ingestion_quality.iq_quality_recovery_task_affected_rule
                values (?,?,'1.0.0')
                """, taskId, RULE);
    }

    private static void seedWindows(JdbcTemplate jdbc, Instant now) throws Exception {
        for (int index = 0; index < 101; index++) {
            String normalizedInputDigest = normalizedWindowDigest(
                    Map.of(SOURCE, 3L), Map.of(SOURCE, "wm-campus-3"),
                    7, List.of("QG-1.0.0"), RULE, "1.0.0",
                    "recovery-validation");
            String expectedDigest = digest(
                    "RECOVERY-NORMALIZED-WINDOW-1.0.0\n" + normalizedInputDigest);
            jdbc.update("""
                    insert into ingestion_quality.iq_historical_window(
                      window_id,subject_ref,start_at,end_at,timezone,source_versions,
                      source_watermarks,mapping_version,quality_gate_versions,rule_id,
                      rule_version,scenario_id,lineage_run_id,input_digest,
                      latest_actionable_at,created_at)
                    values (?,?,?,?,'Asia/Shanghai',jsonb_build_object(?,3),
                      jsonb_build_object(?,'wm-campus-3'),7,'["QG-1.0.0"]'::jsonb,
                      ?,'1.0.0','recovery-validation',?,?,?,?)
                    """, "recovery-window-" + index, uuid(8000 + index),
                    Timestamp.from(now.minusSeconds(1800)), Timestamp.from(now.minusSeconds(600)),
                    SOURCE, SOURCE, RULE, uuid(9001), expectedDigest,
                    Timestamp.from(now.plusSeconds(7200)), Timestamp.from(now.minusSeconds(3600)));
        }
    }

    private static String normalizedWindowDigest(
            Map<String, Long> sourceVersions,
            Map<String, String> sourceWatermarks,
            long mappingVersion,
            List<String> qualityGateVersions,
            String ruleId,
            String ruleVersion,
            String scenarioId) throws Exception {
        String material = JSON.writeValueAsString(sourceVersions) + "\u001f"
                + JSON.writeValueAsString(sourceWatermarks) + "\u001f"
                + mappingVersion + "\u001f"
                + JSON.writeValueAsString(qualityGateVersions) + "\u001f"
                + ruleId + "\u001f" + ruleVersion + "\u001f" + scenarioId;
        return digest(material);
    }

    private static void driftCurrentMember(JdbcTemplate jdbc) {
        Instant now = dbNow(jdbc);
        jdbc.update("""
                insert into ingestion_quality.iq_quality_eligibility_history(
                  eligibility_id,rule_id,rule_version,registry_version,registry_digest,
                  catalog_version,catalog_digest,rule_catalog_version,rule_catalog_digest,
                  aggregate_version,status,reason_code,composition_operator,threshold,
                  effective_at,occurred_at,trace_id,producer,retention_due_at,legal_hold)
                select eligibility_id,rule_id,rule_version,registry_version,registry_digest,
                  catalog_version,catalog_digest,rule_catalog_version,rule_catalog_digest,
                  2,'fused','REQUIRED_MEMBER_FUSED',composition_operator,threshold,
                  effective_at,?,trace_id,producer,(?::timestamptz + interval '2 years'),legal_hold
                  from ingestion_quality.iq_quality_eligibility_history
                 where rule_id=? and aggregate_version=1
                """, Timestamp.from(now), Timestamp.from(now), RULE);
        jdbc.update("""
                insert into ingestion_quality.iq_quality_eligibility_member_history
                select eligibility_id,2,member_ordinal,source_id,source_version,dependency_id,
                  dependency_version,requirement,
                  case when dependency_id=? then 'fused' else state end,
                  version_continuous,source_watermark_utf8,dependency_watermark_utf8,
                  snapshot_id,snapshot_immutable_hash,qmdp_version,qmdp_digest,qshm_version,
                  qshm_digest,lineage_id,dependency_id=? or failed
                  from ingestion_quality.iq_quality_eligibility_member_history
                 where aggregate_version=1 and eligibility_id=(select eligibility_id
                   from ingestion_quality.iq_quality_eligibility_current where rule_id=?)
                """, DEPENDENCY, DEPENDENCY, RULE);
        jdbc.update("""
                update ingestion_quality.iq_quality_eligibility_current
                   set aggregate_version=2,status='fused',reason_code='REQUIRED_MEMBER_FUSED',
                       occurred_at=?
                 where rule_id=?
                """, Timestamp.from(now), RULE);
    }

    private static void cleanup(JdbcTemplate jdbc) {
        jdbc.execute("""
                truncate table
                  signal_evaluation.se_recovery_sample_replay,
                  signal_evaluation.se_recovery_sample_normalized_input,
                  ingestion_quality.iq_quality_recovery_outbox,
                  ingestion_quality.iq_quality_recovery_audit,
                  ingestion_quality.iq_quality_recovery_idempotency,
                  ingestion_quality.iq_quality_recovery_execution_jti,
                  ingestion_quality.iq_recovery_backfill_window_result,
                  ingestion_quality.iq_recovery_backfill_run,
                  ingestion_quality.iq_quality_recovery_preview,
                  ingestion_quality.iq_quality_recovery_evidence_pack,
                  ingestion_quality.iq_recovery_validation_checkpoint,
                  ingestion_quality.iq_recovery_validation_attempt,
                  ingestion_quality.iq_recovery_validation_result,
                  ingestion_quality.iq_recovery_validation_job,
                  ingestion_quality.iq_quality_recovery_request_current,
                  ingestion_quality.iq_quality_recovery_request_history,
                  ingestion_quality.iq_quality_task_outbox,
                  ingestion_quality.iq_quality_task_delivery_history,
                  ingestion_quality.iq_quality_task_delivery,
                  ingestion_quality.iq_quality_recovery_task_affected_rule,
                  ingestion_quality.iq_quality_recovery_task_current,
                  ingestion_quality.iq_quality_recovery_task_history,
                  ingestion_quality.iq_quality_fuse_episode_current,
                  ingestion_quality.iq_quality_fuse_episode_history,
                  ingestion_quality.iq_quality_eligibility_outbox,
                  ingestion_quality.iq_quality_eligibility_audit,
                  ingestion_quality.iq_quality_eligibility_idempotency,
                  ingestion_quality.iq_quality_eligibility_member_history,
                  ingestion_quality.iq_quality_eligibility_current,
                  ingestion_quality.iq_quality_eligibility_history,
                  ingestion_quality.iq_dependency_quality_current,
                  ingestion_quality.iq_quality_dependency_cursor,
                  ingestion_quality.iq_historical_window,
                  ingestion_quality.iq_quality_snapshot,
                  ingestion_quality.iq_data_batch cascade
                """);
        jdbc.execute("""
                truncate table
                  identity_access.ia_high_risk_approval_decision_idempotency,
                  identity_access.ia_high_risk_execution_lease_current,
                  identity_access.ia_high_risk_execution_lease_history,
                  identity_access.ia_high_risk_approval_receipt,
                  identity_access.ia_high_risk_approval_current,
                  identity_access.ia_high_risk_approval_history,
                  identity_access.ia_business_owner_natural_person_current,
                  identity_access.ia_business_owner_natural_person_history,
                  identity_access.ia_account_natural_person_current,
                  identity_access.ia_account_natural_person_history,
                  identity_access.ia_high_risk_audit restart identity cascade
                """);
        jdbc.update("delete from identity_access.ia_authoritative_account_current where account_id in (?,?)",
                CHECKER_ACCOUNT, MAKER_ACCOUNT);
    }

    private static void createLogins(JdbcTemplate admin) {
        dropLogins(admin);
        admin.execute("create role " + ONLINE_LOGIN + " login inherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
        admin.execute("grant scholarsense_ingestion_quality_online to " + ONLINE_LOGIN);
        admin.execute("create role " + WORKER_LOGIN + " login inherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
        admin.execute("grant scholarsense_ingestion_quality_recovery_worker to " + WORKER_LOGIN);
        admin.execute("create role " + SIGNAL_LOGIN + " login inherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
        admin.execute("grant scholarsense_signal_evaluation_recovery_worker, scholarsense_signal_evaluation_input_authority to " + SIGNAL_LOGIN);
        admin.execute("create role " + IDENTITY_LOGIN + " login inherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
        admin.execute("grant scholarsense_identity_online to " + IDENTITY_LOGIN);
        admin.execute("create role " + RULE_LOGIN + " login inherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
        admin.execute("grant scholarsense_rule_governance_reader to " + RULE_LOGIN);
    }

    private static void dropLogins(JdbcTemplate admin) {
        admin.execute("drop role if exists " + ONLINE_LOGIN + ", " + WORKER_LOGIN + ", "
                + SIGNAL_LOGIN + ", " + IDENTITY_LOGIN + ", " + RULE_LOGIN);
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(login(required("scholarsense.audit.pg.user")));
    }

    private static DriverManagerDataSource login(String user) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(required("scholarsense.audit.pg.url"));
        source.setUsername(user);
        return source;
    }

    private static Instant dbNow(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "select date_trunc('microseconds',clock_timestamp())", Instant.class);
    }

    private static JsonNode tree(String value) throws Exception {
        assertNotNull(value);
        return JSON.readTree(value);
    }

    private static String digest(String value) {
        try {
            return "sha256:" + digestHex(value);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String digestHex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019ff7a0-0000-7000-8000-" + String.format("%012x", suffix));
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private record Source(String sourceId, String dependencyId, String schemaVersion) {}

    private record Fixture(
            JdbcTemplate admin,
            JdbcTemplate online,
            JdbcTemplate worker,
            UUID requestId,
            UUID jobId,
            JdbcRecoveryValidationWork work,
            JdbcRecoveryValidationExternalWork external,
            RecoveryValidationJobProcessor processor,
            QualityRecoveryCommandActor maker,
            QualityRecoveryCommandActor checker) implements AutoCloseable {
        Instant workerNow() { return dbNow(worker); }
        @Override public void close() {
            cleanup(admin);
            dropLogins(admin);
        }
    }
}
