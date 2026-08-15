package cn.edu.suda.scholarsense.shared.observability;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.context.ContextSnapshotFactory;
import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

@Configuration(proxyBeanMethods = false)
public class ObservabilityKernelConfiguration {
    @Bean
    @ConditionalOnMissingBean
    TelemetryExportMonitor telemetryExportMonitor(MeterRegistry meters) {
        return new TelemetryExportMonitor(meters);
    }

    @Bean
    static BeanPostProcessor spanExporterMonitoringBeanPostProcessor() {
        return new SpanExporterMonitoringBeanPostProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    W3cTraceContextCodec w3cTraceContextCodec() {
        return new W3cTraceContextCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    TrustedIngressAllowlist observabilityTrustedIngressAllowlist(RuntimeConfiguration runtime) {
        return TrustedIngressAllowlist.forEnvironment(runtime.environment());
    }

    @Bean
    @ConditionalOnMissingBean(CurrentTraceSource.class)
    CurrentTraceSource currentTraceSource(Tracer tracer) {
        return new MicrometerCurrentTraceSource(tracer);
    }

    @Bean
    @ConditionalOnMissingBean(ObservationPort.class)
    ObservationPort observationPort(
            MeterRegistry meters,
            Tracer tracer,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec codec) {
        return new FailSafeObservationPort(
                new MicrometerObservationPort(meters, tracer, currentTrace, codec), codec);
    }

    @Bean
    @ConditionalOnMissingBean
    TrustedHttpClientFactory trustedHttpClientFactory(
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec codec,
            RuntimeConfiguration runtime,
            ObservationPort observations) {
        return new TrustedHttpClientFactory(
                currentTrace, codec, runtime.environment(), observations);
    }

    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    TaskDecorator contextPropagationTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }

    static TaskDecorator contextPropagationTaskDecorator(ContextSnapshotFactory factory) {
        return new ContextPropagatingTaskDecorator(factory);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<HttpTraceTrustBoundaryFilter> httpTraceTrustBoundaryFilter(
            TrustedIngressAllowlist trustedIngress,
            W3cTraceContextCodec codec) {
        FilterRegistrationBean<HttpTraceTrustBoundaryFilter> registration =
                new FilterRegistrationBean<>(new HttpTraceTrustBoundaryFilter(trustedIngress, codec));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<HttpTraceCorrelationFilter> httpTraceCorrelationFilter(
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec codec) {
        FilterRegistrationBean<HttpTraceCorrelationFilter> registration =
                new FilterRegistrationBean<>(new HttpTraceCorrelationFilter(currentTrace, codec, "web-api"));
        // Spring's ServerHttpObservationFilter is HIGHEST_PRECEDENCE + 1.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }
}
