package cn.edu.suda.scholarsense.auditoperations.adapters;

import cn.edu.suda.scholarsense.auditoperations.adapters.inbound.AuditWorkerScheduler;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditAlertWorkRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditAvailabilityObservationRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditBacklogMeasurementAdapter;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditEvidenceRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditLedgerRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.JdbcAuditVerificationRunRepository;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.MicrometerAuditMetricSink;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.SpringAuditTransactionAdapter;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.SpringAuditVerificationTransactionAdapter;
import cn.edu.suda.scholarsense.auditoperations.adapters.outbound.StructuredLogAuditAlertTransport;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.auditoperations.api.AuditAvailabilityQuery;
import cn.edu.suda.scholarsense.auditoperations.api.AuditLedgerIngress;
import cn.edu.suda.scholarsense.auditoperations.api.AuditLedgerIngressPort;
import cn.edu.suda.scholarsense.auditoperations.api.AuditProducerBacklogPort;
import cn.edu.suda.scholarsense.auditoperations.application.AlertOutboxPort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditAlertRelayProcessor;
import cn.edu.suda.scholarsense.auditoperations.application.AuditAlertTransport;
import cn.edu.suda.scholarsense.auditoperations.application.AuditAvailabilityAlertCoordinator;
import cn.edu.suda.scholarsense.auditoperations.application.AuditAvailabilityObserver;
import cn.edu.suda.scholarsense.auditoperations.application.AuditBacklogEvaluator;
import cn.edu.suda.scholarsense.auditoperations.application.AuditBacklogPolicy;
import cn.edu.suda.scholarsense.auditoperations.application.AuditClock;
import cn.edu.suda.scholarsense.auditoperations.application.AuditContractRejectionService;
import cn.edu.suda.scholarsense.auditoperations.application.AuditLedgerAppendService;
import cn.edu.suda.scholarsense.auditoperations.application.AuditLedgerVerifier;
import cn.edu.suda.scholarsense.auditoperations.application.AuditMetricSink;
import cn.edu.suda.scholarsense.auditoperations.application.AuditPolicyPort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditUuidV7;
import cn.edu.suda.scholarsense.auditoperations.application.CanonicalAuditHasher;
import cn.edu.suda.scholarsense.auditoperations.application.CurrentAuditAvailabilityService;
import cn.edu.suda.scholarsense.auditoperations.application.FindingIdPort;
import cn.edu.suda.scholarsense.auditoperations.application.LowCardinalityAuditMetrics;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.runtime.AuditRuntimeProfile;
import cn.edu.suda.scholarsense.shared.time.EvidenceBoundTrustedTimeSource;
import cn.edu.suda.scholarsense.shared.time.TimeSynchronizationStatusProvider;
import cn.edu.suda.scholarsense.shared.time.TrustedClockConstraints;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.net.URI;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.audit-ledger.enabled", havingValue = "true")
@EnableScheduling
public class AuditWorkerConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock auditNodeClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(TrustedClockConstraints.class)
    TrustedClockConstraints auditTrustedClockConstraints() {
        return new TrustedClockConstraints("PP-1.0.0", 100);
    }

    @Bean
    @ConditionalOnMissingBean(TimeSynchronizationStatusProvider.class)
    TimeSynchronizationStatusProvider unavailableTimeSynchronizationStatusProvider() {
        return java.util.Optional::empty;
    }

    @Bean
    @ConditionalOnMissingBean(TrustedTimeSource.class)
    TrustedTimeSource auditTrustedTimeSource(
            RuntimeConfiguration runtime,
            Clock clock,
            TrustedClockConstraints constraints,
            TimeSynchronizationStatusProvider status) {
        URI binding = URI.create(runtime.clockSourceReference());
        return new EvidenceBoundTrustedTimeSource(
                clock, binding.getPath().substring(1), constraints, status);
    }

    @Bean
    AuditClock auditClock(TrustedTimeSource time) {
        return () -> time.now().instant();
    }

    @Bean
    FindingIdPort auditFindingIds() {
        return AuditUuidV7::generate;
    }

    @Bean
    AuditRuntimeProfile auditRuntimeProfile(RuntimeConfiguration runtime) {
        return AuditRuntimeProfile.from(runtime);
    }

    @Bean
    AuditPolicyPort auditPolicy(AuditRuntimeProfile runtime) {
        return new AuditPolicyPort() {
            @Override public String ingestionPolicyVersion() {
                return runtime.ingestionPolicyVersion();
            }
            @Override public String hashProfileVersion() {
                return runtime.hashProfileVersion();
            }
        };
    }

    @Bean
    JdbcAuditLedgerRepository auditLedgerRepository(JdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcAuditLedgerRepository(jdbc, json);
    }

    @Bean
    JdbcAuditEvidenceRepository auditEvidenceRepository(
            JdbcTemplate jdbc,
            ObjectMapper json,
            FindingIdPort identifiers,
            SpringAuditTransactionAdapter transactions) {
        return new JdbcAuditEvidenceRepository(jdbc, json, identifiers, transactions);
    }

    @Bean
    SpringAuditTransactionAdapter auditTransactions(PlatformTransactionManager manager) {
        return new SpringAuditTransactionAdapter(new TransactionTemplate(manager));
    }

    @Bean
    CanonicalAuditHasher canonicalAuditHasher() {
        return new CanonicalAuditHasher();
    }

    @Bean
    AuditLedgerIngressPort auditLedgerIngress(
            JdbcAuditLedgerRepository ledger,
            JdbcAuditEvidenceRepository evidence,
            SpringAuditTransactionAdapter transactions,
            AuditClock clock,
            AuditPolicyPort policy,
            FindingIdPort identifiers,
            CanonicalAuditHasher hasher,
            LowCardinalityAuditMetrics metrics) {
        var append = new AuditLedgerAppendService(
                ledger, evidence, evidence, transactions, clock, policy, identifiers, hasher);
        var reject = new AuditContractRejectionService(
                evidence, evidence, transactions, clock, policy, identifiers);
        return new AuditLedgerIngress(append, reject, metrics);
    }

    @Bean
    AuditLedgerVerifier auditLedgerVerifier(
            JdbcAuditLedgerRepository ledger,
            CanonicalAuditHasher hasher,
            JdbcAuditEvidenceRepository evidence,
            FindingIdPort identifiers,
            AuditClock clock,
            AuditPolicyPort policy,
            JdbcAuditVerificationRunRepository runs,
            SpringAuditVerificationTransactionAdapter verificationTransactions,
            AuditRuntimeProfile runtime) {
        return new AuditLedgerVerifier(
                ledger, hasher, evidence, evidence, identifiers, clock, policy, runs,
                verificationTransactions,
                runtime.verifierBatchSize());
    }

    @Bean
    SpringAuditVerificationTransactionAdapter auditVerificationTransactions(
            PlatformTransactionManager manager) {
        return new SpringAuditVerificationTransactionAdapter(manager);
    }

    @Bean
    JdbcAuditVerificationRunRepository auditVerificationRuns(JdbcTemplate jdbc) {
        return new JdbcAuditVerificationRunRepository(jdbc);
    }

    @Bean
    JdbcAuditBacklogMeasurementAdapter auditBacklogMeasurements(
            AuditProducerBacklogPort producer,
            JdbcAuditEvidenceRepository evidence,
            JdbcTemplate jdbc) {
        return new JdbcAuditBacklogMeasurementAdapter(producer, evidence, jdbc);
    }

    @Bean
    JdbcAuditAvailabilityObservationRepository auditAvailabilityObservations(
            JdbcTemplate jdbc,
            ObjectMapper json,
            FindingIdPort identifiers) {
        return new JdbcAuditAvailabilityObservationRepository(jdbc, json, identifiers);
    }

    @Bean
    @Primary
    AuditAvailabilityObserver auditAvailabilityObserver(
            JdbcAuditAvailabilityObservationRepository observations,
            JdbcAuditEvidenceRepository evidence,
            FindingIdPort identifiers) {
        var alerts = new AuditAvailabilityAlertCoordinator(
                evidence, evidence, identifiers);
        return (availability, measurement) -> {
            alerts.observe(availability, measurement);
            observations.observe(availability, measurement);
        };
    }

    @Bean
    AuditAvailabilityPort auditAvailability(
            JdbcAuditBacklogMeasurementAdapter measurements,
            AuditClock clock,
            AuditAvailabilityObserver observer,
            JdbcAuditAvailabilityObservationRepository observations,
            SpringAuditTransactionAdapter transactions,
            AuditRuntimeProfile runtime) {
        var backlogPolicy = new AuditBacklogPolicy(
                runtime.ingestionPolicyVersion(),
                runtime.measurementStaleAfterSeconds(),
                runtime.degradedAgeSeconds(),
                runtime.degradedCount(),
                runtime.blockedAgeSeconds(),
                runtime.blockedCount(),
                runtime.recoveryHealthyObservations());
        var service = new CurrentAuditAvailabilityService(
                measurements,
                new AuditBacklogEvaluator(observations, backlogPolicy),
                clock,
                observer,
                transactions);
        return new AuditAvailabilityQuery(service, clock);
    }

    @Bean
    JdbcAuditAlertWorkRepository auditAlertWork(
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcAuditAlertWorkRepository(jdbc, new TransactionTemplate(manager));
    }

    @Bean
    AuditAlertTransport auditAlertTransport(RuntimeConfiguration runtime) {
        if (runtime.auditAlertTransportReference() == null) {
            throw new IllegalStateException("AUDIT_ALERT_TRANSPORT_BINDING_REQUIRED");
        }
        return new StructuredLogAuditAlertTransport();
    }

    @Bean
    AuditMetricSink auditMetricSink(RuntimeConfiguration runtime, MeterRegistry registry) {
        if (runtime.auditMetricBindingReference() == null) {
            throw new IllegalStateException("AUDIT_METRIC_BINDING_REQUIRED");
        }
        return new MicrometerAuditMetricSink(registry);
    }

    @Bean
    LowCardinalityAuditMetrics auditMetrics(AuditMetricSink metricSink) {
        return new LowCardinalityAuditMetrics(metricSink);
    }

    @Bean
    AuditAlertRelayProcessor auditAlertRelay(
            JdbcAuditAlertWorkRepository work,
            AuditAlertTransport transport,
            AuditClock clock,
            LowCardinalityAuditMetrics metrics,
            AuditRuntimeProfile runtime) {
        return new AuditAlertRelayProcessor(
                work, transport, clock,
                () -> ThreadLocalRandom.current().nextDouble(0.5, 1.0),
                metrics,
                runtime.alertBatchSize(),
                runtime.alertClaimLease());
    }

    @Bean
    AuditWorkerScheduler auditWorkerScheduler(
            AuditLedgerVerifier verifier,
            AuditAvailabilityPort availability,
            AuditAlertRelayProcessor alerts,
            LowCardinalityAuditMetrics metrics) {
        return new AuditWorkerScheduler(verifier, availability, alerts, metrics);
    }
}
