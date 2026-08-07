package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public record HistoricalWindow(
        String subjectRef,
        String windowId,
        Instant startAt,
        Instant endAt,
        ZoneId timezone,
        Map<String, Long> sourceVersions,
        Map<String, String> sourceWatermarks,
        long mappingVersion,
        List<String> qualityGateVersions,
        String ruleId,
        String ruleVersion,
        String scenarioId,
        UUID lineageRunId,
        String inputDigest,
        Instant latestActionableAt) {

    public HistoricalWindow {
        subjectRef = IngestionQualityDomainRules.requireUuidV7(subjectRef);
        windowId = IngestionQualityDomainRules.requireText(windowId, 128);
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw IngestionQualityDomainRules.invalid();
        }
        if (!ZoneId.of("Asia/Shanghai").equals(timezone)) {
            throw IngestionQualityDomainRules.invalid();
        }
        sourceVersions = immutableVersions(sourceVersions);
        sourceWatermarks = immutableWatermarks(sourceWatermarks);
        if (!sourceVersions.keySet().equals(sourceWatermarks.keySet())) {
            throw IngestionQualityDomainRules.invalid();
        }
        mappingVersion = IngestionQualityDomainRules.requireVersion(mappingVersion);
        qualityGateVersions = immutableTexts(qualityGateVersions, 128);
        ruleId = IngestionQualityDomainRules.requireText(ruleId, 128);
        ruleVersion = IngestionQualityDomainRules.requireText(ruleVersion, 64);
        if (scenarioId != null) {
            scenarioId = IngestionQualityDomainRules.requireText(scenarioId, 128);
        }
        lineageRunId = IngestionQualityDomainRules.requireUuidV7(lineageRunId);
        inputDigest = IngestionQualityDomainRules.requireSha256(inputDigest);
    }

    public boolean isActionableAt(Instant serverNow) {
        return serverNow != null
                && latestActionableAt != null
                && serverNow.isBefore(latestActionableAt);
    }

    public String inputWatermarksDigest() {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(sourceWatermarks).forEach((source, watermark) -> canonical
                .append(source).append('=').append(watermark).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    boolean overlaps(HistoricalWindow other) {
        return sameWindowSeries(other)
                && startAt.isBefore(other.endAt)
                && other.startAt.isBefore(endAt);
    }

    private boolean sameWindowSeries(HistoricalWindow other) {
        return subjectRef.equals(other.subjectRef)
                && ruleId.equals(other.ruleId)
                && ruleVersion.equals(other.ruleVersion)
                && java.util.Objects.equals(scenarioId, other.scenarioId);
    }

    private static Map<String, Long> immutableVersions(Map<String, Long> values) {
        if (values == null || values.isEmpty()) {
            throw IngestionQualityDomainRules.invalid();
        }
        TreeMap<String, Long> copy = new TreeMap<>();
        values.forEach((key, value) -> {
            IngestionQualityDomainRules.requireText(key, 128);
            if (value == null) {
                throw IngestionQualityDomainRules.invalid();
            }
            copy.put(key, IngestionQualityDomainRules.requireVersion(value));
        });
        return Map.copyOf(copy);
    }

    private static Map<String, String> immutableWatermarks(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw IngestionQualityDomainRules.invalid();
        }
        TreeMap<String, String> copy = new TreeMap<>();
        values.forEach((key, value) -> copy.put(
                IngestionQualityDomainRules.requireText(key, 128),
                IngestionQualityDomainRules.requireText(value, 512)));
        return Map.copyOf(copy);
    }

    private static List<String> immutableTexts(List<String> values, int maximumLength) {
        if (values == null || values.isEmpty()) {
            throw IngestionQualityDomainRules.invalid();
        }
        ArrayList<String> copy = new ArrayList<>();
        for (String value : values) {
            String checked = IngestionQualityDomainRules.requireText(value, maximumLength);
            if (copy.contains(checked)) {
                throw IngestionQualityDomainRules.invalid();
            }
            copy.add(checked);
        }
        return List.copyOf(copy);
    }
}
