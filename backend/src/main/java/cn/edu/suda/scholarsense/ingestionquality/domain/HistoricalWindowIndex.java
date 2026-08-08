package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.ArrayList;
import java.util.List;

public final class HistoricalWindowIndex {
    private final List<HistoricalWindow> windows;

    private HistoricalWindowIndex(List<HistoricalWindow> windows) {
        this.windows = List.copyOf(windows);
    }

    public static HistoricalWindowIndex empty() {
        return new HistoricalWindowIndex(List.of());
    }

    public HistoricalWindowIndex append(HistoricalWindow window) {
        if (window == null || windows.stream().anyMatch(existing -> existing.overlaps(window))) {
            throw IngestionQualityDomainRules.invalid();
        }
        ArrayList<HistoricalWindow> next = new ArrayList<>(windows);
        next.add(window);
        return new HistoricalWindowIndex(next);
    }

    public List<HistoricalWindow> windows() {
        return windows;
    }
}
