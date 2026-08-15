package cn.edu.suda.scholarsense.shared.observability;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Wraps Boot-managed span exporters before the SDK collects them. */
final class SpanExporterMonitoringBeanPostProcessor
        implements BeanPostProcessor, BeanFactoryAware {
    private BeanFactory beans;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beans = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof SpanExporter exporter
                && !(bean instanceof MonitoringSpanExporter)) {
            return new MonitoringSpanExporter(
                    exporter, beans.getBean(TelemetryExportMonitor.class));
        }
        return bean;
    }
}
