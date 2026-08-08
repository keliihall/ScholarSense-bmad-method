package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.ingestionquality.adapters.inbound.SubjectWindowRecomputeScheduler;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcSubjectWindowRecomputeStore;
import cn.edu.suda.scholarsense.ingestionquality.application.MappingRecomputeIdPort;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectWindowRecomputeProcessor;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Opt-in production scheduler; database leases make concurrent replicas safe. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {
            "scholarsense.identity.enabled",
            "scholarsense.ingestion-quality.subject-recompute-worker-enabled"
        },
        havingValue = "true")
@EnableScheduling
public class SubjectWindowRecomputeWorkerConfiguration {
    @Bean
    SubjectWindowRecomputeProcessor subjectWindowRecomputeProcessor(
            JdbcSubjectWindowRecomputeStore store,
            MappingRecomputeIdPort ids,
            TrustedTimeSource time) {
        return new SubjectWindowRecomputeProcessor(store, ids, new TrustedTimeClock(time));
    }

    @Bean
    SubjectWindowRecomputeScheduler subjectWindowRecomputeScheduler(
            SubjectWindowRecomputeProcessor processor) {
        return new SubjectWindowRecomputeScheduler(processor);
    }
}
