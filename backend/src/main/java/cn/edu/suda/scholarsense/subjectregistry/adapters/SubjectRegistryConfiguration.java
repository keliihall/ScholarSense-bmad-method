package cn.edu.suda.scholarsense.subjectregistry.adapters;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityState;
import cn.edu.suda.scholarsense.shared.time.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.subjectregistry.adapters.outbound.AesGcmHmacIdentifierProtectionAdapter;
import cn.edu.suda.scholarsense.subjectregistry.adapters.outbound.FileSubjectRegistryProtectionKeyPort;
import cn.edu.suda.scholarsense.subjectregistry.adapters.outbound.FrozenSourceIdentifierPolicy;
import cn.edu.suda.scholarsense.subjectregistry.adapters.outbound.JdbcSubjectRegistryStore;
import cn.edu.suda.scholarsense.subjectregistry.adapters.outbound.JdbcSubjectRegistryTransactionAdapter;
import cn.edu.suda.scholarsense.subjectregistry.adapters.outbound.SubjectRegistryProtectionKeyPort;
import cn.edu.suda.scholarsense.subjectregistry.adapters.outbound.TrustedTimeSubjectRegistryIds;
import cn.edu.suda.scholarsense.subjectregistry.application.ProtectedIdentifierMaterial;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectMappingAuthorizationProbePort;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectMappingExceptionRecord;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryIdPort;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryService;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierKey;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierType;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionCode;
import cn.edu.suda.scholarsense.subjectregistry.domain.ProtectedIdentifierToken;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMappingException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"scholarsense.identity.enabled", "scholarsense.subject-registry.enabled"},
        havingValue = "true")
public class SubjectRegistryConfiguration {
    @Bean
    @ConfigurationProperties("scholarsense.subject-registry.datasource")
    DataSourceProperties subjectRegistryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("subjectRegistryDataSource")
    DataSource subjectRegistryDataSource(
            @Qualifier("subjectRegistryDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    SubjectRegistryPostgreSqlConnectionProfile subjectRegistryOnlineConnectionProfile(
            @Qualifier("subjectRegistryDataSource") DataSource dataSource,
            @Qualifier("subjectRegistryDataSourceProperties") DataSourceProperties properties,
            RuntimeConfiguration runtime) {
        return SubjectRegistryPostgreSqlDataSourceStartupGate.verifyOnline(
                dataSource, runtime.environment().wireName(), properties.getUsername());
    }

    @Bean("subjectRegistryJdbc")
    JdbcTemplate subjectRegistryJdbc(
            @Qualifier("subjectRegistryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean("subjectRegistryTransactionManager")
    PlatformTransactionManager subjectRegistryTransactionManager(
            @Qualifier("subjectRegistryDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    SubjectRegistryIdPort subjectRegistryIds(TrustedTimeSource time) {
        return new TrustedTimeSubjectRegistryIds(time);
    }

    @Bean
    SubjectRegistryProtectionKeyPort subjectRegistryProtectionKeys(
            @Value("${scholarsense.subject-registry.environment}") String environment,
            @Value("${scholarsense.subject-registry.key-ref}") String keyRef,
            @Value("${scholarsense.subject-registry.key-version}") String keyVersion,
            @Value("${scholarsense.subject-registry.encryption-key-path}") String encryptionPath,
            @Value("${scholarsense.subject-registry.search-key-path}") String searchPath) {
        return new FileSubjectRegistryProtectionKeyPort(
                environment, keyRef, keyVersion, Path.of(encryptionPath), Path.of(searchPath));
    }

    @Bean
    AesGcmHmacIdentifierProtectionAdapter subjectRegistryProtection(
            SubjectRegistryProtectionKeyPort keys) {
        return new AesGcmHmacIdentifierProtectionAdapter(keys);
    }

    @Bean
    FrozenSourceIdentifierPolicy subjectRegistrySourcePolicy() {
        return new FrozenSourceIdentifierPolicy();
    }

    @Bean
    JdbcSubjectRegistryStore jdbcSubjectRegistryStore(
            @Qualifier("subjectRegistryJdbc") JdbcTemplate jdbc,
            ObjectMapper json,
            SubjectRegistryIdPort ids,
            SubjectRegistryPostgreSqlConnectionProfile subjectRegistryOnlineConnectionProfile) {
        return new JdbcSubjectRegistryStore(jdbc, json, ids);
    }

    @Bean
    JdbcSubjectRegistryTransactionAdapter subjectRegistryTransactions(
            @Qualifier("subjectRegistryTransactionManager") PlatformTransactionManager manager) {
        return new JdbcSubjectRegistryTransactionAdapter(new TransactionTemplate(manager));
    }

    @Bean
    SubjectMappingAuthorizationProbePort subjectMappingAuthorizationProbe() {
        ProtectedIdentifierToken token = ProtectedIdentifierToken.of(
                "probe", "kms://subject-registry/probe", "v1",
                "hmac-sha256:" + "0".repeat(64));
        SubjectMappingException exception = SubjectMappingException.open(
                UUID.fromString("019fcfea-7000-7000-8000-000000000001"),
                new IdentifierKey("SRC-P0-STUDENT-001", IdentifierType.STUDENT_NUMBER, token),
                MappingExceptionCode.NO_MATCH, "authorization-probe", Instant.EPOCH);
        SubjectMappingExceptionRecord probe = new SubjectMappingExceptionRecord(
                exception, new ProtectedIdentifierMaterial(
                        token, "aesgcm-v1:AAAAAAAAAAAAAAAA:BBBBBBBBBBBBBBBBBBBBBBBB",
                        "STUDENT_OFFICIAL_REF"));
        return () -> probe;
    }

    @Bean
    SubjectRegistryService subjectRegistryService(
            JdbcSubjectRegistryStore store,
            AesGcmHmacIdentifierProtectionAdapter protection,
            FrozenSourceIdentifierPolicy policy,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            JdbcSubjectRegistryTransactionAdapter transactions,
            AuditAvailabilityPort auditAvailability,
            TrustedTimeSource time,
            SubjectRegistryIdPort ids,
            SubjectMappingAuthorizationProbePort probe) {
        return new SubjectRegistryService(
                store, protection, policy, store, authorization, recheck,
                transactions, store, store, auditAvailability, time, ids, probe);
    }

    @Bean
    AuthorizedShellCapabilityProvider subjectRegistryShellCapabilities() {
        return () -> List.of(
                new AuthorizedShellCapability(
                        "subject-mapping-exceptions", "主体映射异常",
                        "data-quality.subject-mapping-exceptions",
                        AuthorizedShellCapabilityState.AVAILABLE,
                        Set.of("R6-DATA-OWNER")),
                new AuthorizedShellCapability(
                        "subject-recompute-jobs", "主体重算作业",
                        "subject-registry.recompute-jobs",
                        AuthorizedShellCapabilityState.AVAILABLE,
                        Set.of("R6-DATA-OWNER", "R7-PLATFORM-OPS")));
    }
}
