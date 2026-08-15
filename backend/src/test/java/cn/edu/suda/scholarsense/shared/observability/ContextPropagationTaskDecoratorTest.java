package cn.edu.suda.scholarsense.shared.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskDecorator;

class ContextPropagationTaskDecoratorTest {
    @Test
    void reusedWorkerThreadReceivesTheSubmittingTraceAndNeverLeaksThePreviousOne()
            throws Exception {
        ThreadLocal<String> trace = new ThreadLocal<>();
        ContextRegistry registry = new ContextRegistry()
                .registerThreadLocalAccessor("scholarsense.trace", trace);
        TaskDecorator decorator = ObservabilityKernelConfiguration.contextPropagationTaskDecorator(
                ContextSnapshotFactory.builder()
                        .contextRegistry(registry)
                        .clearMissing(true)
                        .build());

        try (var executor = Executors.newSingleThreadExecutor()) {
            trace.set("11111111111111111111111111111111");
            AtomicReference<String> first = new AtomicReference<>();
            executor.submit(decorator.decorate(() -> first.set(trace.get()))).get();
            assertEquals("11111111111111111111111111111111", first.get());

            trace.remove();
            AtomicReference<String> empty = new AtomicReference<>();
            executor.submit(decorator.decorate(() -> empty.set(trace.get()))).get();
            assertNull(empty.get());

            trace.set("22222222222222222222222222222222");
            AtomicReference<String> second = new AtomicReference<>();
            executor.submit(decorator.decorate(() -> second.set(trace.get()))).get();
            assertEquals("22222222222222222222222222222222", second.get());
        } finally {
            trace.remove();
        }
    }
}
