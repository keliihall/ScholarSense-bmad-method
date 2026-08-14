package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualityTaskTargetPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityTaskTargetRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityTaskTargetResult;

/** PIC-1.1.0 explicitly defers target activation; no network endpoint is implied or called. */
public final class DeferredQualityTaskTargetAdapter implements QualityTaskTargetPort {
    @Override
    public QualityTaskTargetResult deliver(QualityTaskTargetRequest request) {
        return QualityTaskTargetResult.retryable(
                "QUALITY_TASK_TARGET_ACTIVATION_DEFERRED");
    }
}
