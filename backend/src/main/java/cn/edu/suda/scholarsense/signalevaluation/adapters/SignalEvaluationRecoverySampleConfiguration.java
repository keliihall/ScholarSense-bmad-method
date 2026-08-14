package cn.edu.suda.scholarsense.signalevaluation.adapters;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.signalevaluation.adapters.outbound.JdbcRecoverySampleNormalizedInputResolver;
import cn.edu.suda.scholarsense.signalevaluation.adapters.outbound.JdbcRecoverySampleReplayStore;
import cn.edu.suda.scholarsense.signalevaluation.adapters.outbound.JdbcRecoverySampleNormalizedInputStore;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleNormalizedInputPort;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleRecomputeProvider;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleRecomputeProviderPort;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleProviderTimePort;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleRecomputeUseCase;
import cn.edu.suda.scholarsense.signalevaluation.adapters.inbound.RecoverySampleRetentionScheduler;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real bounded sample provider with a dedicated least-privilege database principal. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "scholarsense.signal-evaluation.recovery-sample-enabled",
        havingValue = "true")
public class SignalEvaluationRecoverySampleConfiguration {
    @Bean
    @ConfigurationProperties("scholarsense.signal-evaluation.recovery-sample-datasource")
    DataSourceProperties signalEvaluationRecoveryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("signalEvaluationRecoveryDataSource")
    DataSource signalEvaluationRecoveryDataSource(
            @Qualifier("signalEvaluationRecoveryDataSourceProperties")
                    DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean("signalEvaluationRecoveryJdbc")
    JdbcTemplate signalEvaluationRecoveryJdbc(
            @Qualifier("signalEvaluationRecoveryDataSource") DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    @ConditionalOnProperty(
            name = "scholarsense.signal-evaluation.recovery-retention-enabled",
            havingValue = "true")
    @ConfigurationProperties(
            "scholarsense.signal-evaluation.recovery-retention-datasource")
    DataSourceProperties signalEvaluationRecoveryRetentionDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("signalEvaluationRecoveryRetentionDataSource")
    @ConditionalOnProperty(
            name = "scholarsense.signal-evaluation.recovery-retention-enabled",
            havingValue = "true")
    DataSource signalEvaluationRecoveryRetentionDataSource(
            @Qualifier("signalEvaluationRecoveryRetentionDataSourceProperties")
                    DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean("signalEvaluationRecoveryRetentionJdbc")
    @ConditionalOnProperty(
            name = "scholarsense.signal-evaluation.recovery-retention-enabled",
            havingValue = "true")
    JdbcTemplate signalEvaluationRecoveryRetentionJdbc(
            @Qualifier("signalEvaluationRecoveryRetentionDataSource") DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    @ConditionalOnProperty(
            name = "scholarsense.signal-evaluation.recovery-retention-enabled",
            havingValue = "true")
    RecoverySampleRetentionScheduler recoverySampleRetentionScheduler(
            @Qualifier("signalEvaluationRecoveryRetentionJdbc") JdbcTemplate jdbc,
            TrustedTimeSource time) {
        return new RecoverySampleRetentionScheduler(jdbc, time);
    }

    @Bean
    RecoverySampleProviderTimePort recoverySampleProviderTime(TrustedTimeSource trustedTime) {
        return new RecoverySampleProviderTimePort() {
            @Override public long monotonicNanos() { return System.nanoTime(); }
            @Override public java.time.Instant trustedNow() {
                return trustedTime.now().instant();
            }
        };
    }

    @Bean
    RecoverySampleNormalizedInputPort recoverySampleNormalizedInput(
            @Qualifier("signalEvaluationRecoveryJdbc") JdbcTemplate jdbc,
            ObjectMapper json) {
        return new JdbcRecoverySampleNormalizedInputStore(jdbc, json);
    }

    @Bean
    RecoverySampleRecomputeProviderPort recoverySampleRecomputeProvider(
            @Qualifier("signalEvaluationRecoveryJdbc") JdbcTemplate jdbc,
            ObjectMapper json,
            RecoverySampleProviderTimePort time) {
        var resolver = new JdbcRecoverySampleNormalizedInputResolver(
                jdbc, json, time::trustedNow);
        var replays = new JdbcRecoverySampleReplayStore(jdbc, json);
        return new RecoverySampleRecomputeProvider(
                new RecoverySampleRecomputeUseCase(resolver, time, replays));
    }
}
