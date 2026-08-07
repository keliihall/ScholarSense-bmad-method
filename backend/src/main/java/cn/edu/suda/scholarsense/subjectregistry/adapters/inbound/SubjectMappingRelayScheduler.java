package cn.edu.suda.scholarsense.subjectregistry.adapters.inbound;

import cn.edu.suda.scholarsense.subjectregistry.adapters.SubjectMappingRelayProcessor;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Worker-only trigger for committed subject mapping events. */
public final class SubjectMappingRelayScheduler {
    private final SubjectMappingRelayProcessor processor;

    public SubjectMappingRelayScheduler(SubjectMappingRelayProcessor processor) {
        this.processor = Objects.requireNonNull(processor);
    }

    @Scheduled(
            initialDelayString = "${scholarsense.subject-registry.relay-initial-delay:PT5S}",
            fixedDelayString = "${scholarsense.subject-registry.relay-interval:PT5S}")
    public void relay() {
        processor.runBatch();
    }
}
