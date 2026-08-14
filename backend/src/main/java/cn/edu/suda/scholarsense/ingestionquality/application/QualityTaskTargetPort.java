package cn.edu.suda.scholarsense.ingestionquality.application;

@FunctionalInterface
public interface QualityTaskTargetPort {
    QualityTaskTargetResult deliver(QualityTaskTargetRequest request);
}
