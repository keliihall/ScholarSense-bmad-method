package cn.edu.suda.scholarsense.signalevaluation.application;

import java.time.Instant;

public interface RecoverySampleProviderTimePort {
    long monotonicNanos();

    Instant trustedNow();
}
