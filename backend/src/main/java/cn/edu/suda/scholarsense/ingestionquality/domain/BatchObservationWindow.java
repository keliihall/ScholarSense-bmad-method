package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;

public record BatchObservationWindow(Instant startAt, Instant endAt) {
    public BatchObservationWindow {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw IngestionQualityDomainRules.invalid();
        }
    }
}
