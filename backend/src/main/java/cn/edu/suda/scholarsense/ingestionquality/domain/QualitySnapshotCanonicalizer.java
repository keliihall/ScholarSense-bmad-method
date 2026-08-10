package cn.edu.suda.scholarsense.ingestionquality.domain;

import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.SourcePolicy;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Policy-bound QSHM material builder, canonical UTF-8 encoder and immutable hash calculator. */
public final class QualitySnapshotCanonicalizer {
    private static final String PROFILE_DIGEST =
            "sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2";
    private static final String POLICY_DIGEST =
            "sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8";
    private static final List<String> INCLUDED_FIELDS = List.of(
            "domainTag", "hashProfileVersion", "hashProfileDigest", "batchId", "sourceId",
            "assessedBatchStatus", "overallResult", "observationWindow", "cutoffAt",
            "watermark", "metricResults", "impactScopeCodes", "sourceOwnerRef",
            "approvalRef", "effectiveAt", "retentionScheduleVersion",
            "qualityMetricDecisionProfileVersion", "qualityMetricDecisionProfileDigest",
            "qualityGateVersion", "qualityGateDigest", "canonicalizationProfile",
            "manifestDigest", "sourceSchemaVersion", "sourceSchemaDigest", "lineageId",
            "supersedesSnapshotId");
    private static final List<String> EXCLUDED_FIELDS = List.of(
            "snapshotId", "evaluatedAt", "traceId", "aggregateVersion", "immutableHash");
    private static final List<String> METRIC_FIELDS = List.of(
            "metricId", "formulaId", "formulaVersion", "result", "applicable", "numerator",
            "denominator", "valueBasisPoints", "unit", "operator", "thresholdNumerator",
            "thresholdDenominator", "boundary", "reasonCode");
    private static final DateTimeFormatter UTC_MICROSECONDS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    private final ExecutableQualityPolicy policy;
    private final QualitySnapshotHashProfile profile;

    public QualitySnapshotCanonicalizer(
            ExecutableQualityPolicy policy,
            QualitySnapshotHashProfile profile) {
        this.policy = Objects.requireNonNull(policy);
        this.profile = Objects.requireNonNull(profile);
        requireProfile();
    }

    QualitySnapshotMaterial materialize(QualitySnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        SourcePolicy source = uniqueSource(snapshot.sourceId());
        requireBindings(snapshot, source);
        List<MetricDefinition> definitions = QualityMetricCalculator.orderedDefinitions(
                policy, source.sourceId());
        List<QualityMetricResult> orderedMetrics = orderedMetrics(
                snapshot.metricResults(), definitions, source);
        requireOverall(snapshot, orderedMetrics);
        canonicalInstant(snapshot.evaluatedAt());

        return new QualitySnapshotMaterial(
                snapshot.domainTag(), snapshot.hashProfileVersion(), snapshot.hashProfileDigest(),
                snapshot.batchId(), snapshot.sourceId(), snapshot.assessedBatchStatus(),
                snapshot.overallResult(), snapshot.observationWindow(), snapshot.cutoffAt(),
                snapshot.watermark(), orderedMetrics, snapshot.impactScopeCodes(),
                snapshot.sourceOwnerRef(), snapshot.approvalRef(), snapshot.effectiveAt(),
                snapshot.retentionScheduleVersion(),
                snapshot.qualityMetricDecisionProfileVersion(),
                snapshot.qualityMetricDecisionProfileDigest(), snapshot.qualityGateVersion(),
                snapshot.qualityGateDigest(), snapshot.canonicalizationProfile(),
                snapshot.manifestDigest(), snapshot.sourceSchemaVersion(),
                snapshot.sourceSchemaDigest(), snapshot.lineageId(),
                snapshot.supersedesSnapshotId());
    }

    byte[] canonicalUtf8(QualitySnapshotMaterial material) {
        Objects.requireNonNull(material);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("domainTag", material.domainTag());
        value.put("hashProfileVersion", material.hashProfileVersion());
        value.put("hashProfileDigest", material.hashProfileDigest());
        value.put("batchId", material.batchId().toString());
        value.put("sourceId", material.sourceId());
        value.put("assessedBatchStatus", material.assessedBatchStatus().wireValue());
        value.put("overallResult", material.overallResult().wireValue());
        value.put("observationWindow", Map.of(
                "startAt", canonicalInstant(material.observationWindow().startAt()),
                "endAt", canonicalInstant(material.observationWindow().endAt())));
        value.put("cutoffAt", canonicalInstant(material.cutoffAt()));
        value.put("watermark", material.watermark());
        value.put("metricResults", material.metricResults().stream()
                .map(QualitySnapshotCanonicalizer::metricValue)
                .toList());
        value.put("impactScopeCodes", material.impactScopeCodes());
        value.put("sourceOwnerRef", material.sourceOwnerRef());
        value.put("approvalRef", material.approvalRef());
        value.put("effectiveAt", canonicalInstant(material.effectiveAt()));
        value.put("retentionScheduleVersion", material.retentionScheduleVersion());
        value.put("qualityMetricDecisionProfileVersion",
                material.qualityMetricDecisionProfileVersion());
        value.put("qualityMetricDecisionProfileDigest",
                material.qualityMetricDecisionProfileDigest());
        value.put("qualityGateVersion", material.qualityGateVersion());
        value.put("qualityGateDigest", material.qualityGateDigest());
        value.put("canonicalizationProfile", material.canonicalizationProfile());
        value.put("manifestDigest", material.manifestDigest());
        value.put("sourceSchemaVersion", material.sourceSchemaVersion());
        value.put("sourceSchemaDigest", material.sourceSchemaDigest());
        value.put("lineageId", material.lineageId().toString());
        value.put("supersedesSnapshotId", nullableUuid(material.supersedesSnapshotId()));
        if (!value.keySet().equals(Set.copyOf(INCLUDED_FIELDS))) throw invalid();
        return QualityCanonicalJson.canonicalUtf8(value);
    }

    String immutableHash(QualitySnapshotMaterial material) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalUtf8(material)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Calculates a hash only after rebuilding policy-bound material from the snapshot. */
    public String immutableHash(QualitySnapshot snapshot) {
        return immutableHash(materialize(snapshot));
    }

    /** Rejects a snapshot whose stored hash is not its validated QSHM content hash. */
    public void verify(QualitySnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (!snapshot.immutableHash().equals(immutableHash(snapshot))) throw invalid();
    }

    private void requireProfile() {
        var ordering = profile.metricOrdering();
        var impacts = profile.impactScopeOrdering();
        var time = profile.timeCanonicalization();
        var escaping = profile.stringEscaping();
        if (!profile.hashProfileVersion().equals("QSHM-1.0.0")
                || !profile.domainTag().equals(
                        "scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1")
                || !profile.canonicalizationProfile().equals(
                        "SCHOLARSENSE-CANONICAL-JSON-1.0.0")
                || !profile.algorithm().equals("SHA-256")
                || !profile.digestPrefix().equals("sha256:")
                || !profile.includedTopLevelFields().equals(INCLUDED_FIELDS)
                || !profile.excludedSnapshotFields().equals(EXCLUDED_FIELDS)
                || !profile.metricResultFields().equals(METRIC_FIELDS)
                || !ordering.formulaCardinality().equals("exactly-once")
                || !impacts.order().equals("ascending-Unicode-code-point")
                || !impacts.duplicates().equals("reject")
                || !impacts.empty().equals("[]")
                || !time.semanticType().equals("Instant")
                || !time.timezone().equals("UTC")
                || !time.precision().equals("microsecond")
                || !time.format().equals("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                || !time.subMicrosecond().equals("reject")
                || !escaping.strategy().equals("minimal-json-escapes")
                || !escaping.unicodeScalarsOnly()) {
            throw invalid();
        }
    }

    private void requireBindings(QualitySnapshot snapshot, SourcePolicy source) {
        var gate = policy.controlledInputs().qualityGate();
        var schema = source.schemaBinding();
        if (!snapshot.domainTag().equals(profile.domainTag())
                || !snapshot.hashProfileVersion().equals(profile.hashProfileVersion())
                || !snapshot.hashProfileDigest().equals(PROFILE_DIGEST)
                || !snapshot.sourceOwnerRef().equals(source.owner())
                || !snapshot.approvalRef().equals(policy.approvalRef())
                || !snapshot.effectiveAt().equals(policy.effectiveAt())
                || !snapshot.retentionScheduleVersion().equals("RS-1.0.0")
                || !snapshot.qualityMetricDecisionProfileVersion().equals(policy.profileVersion())
                || !snapshot.qualityMetricDecisionProfileDigest().equals(POLICY_DIGEST)
                || !snapshot.qualityGateVersion().equals(gate.version())
                || !snapshot.qualityGateDigest().equals(gate.canonicalDigest())
                || !snapshot.canonicalizationProfile().equals(policy.canonicalization().profile())
                || !snapshot.sourceSchemaVersion().equals(schema.version())
                || !snapshot.sourceSchemaDigest().equals(schema.canonicalDigest())) {
            throw invalid();
        }
        canonicalInstant(snapshot.observationWindow().startAt());
        canonicalInstant(snapshot.observationWindow().endAt());
        canonicalInstant(snapshot.cutoffAt());
        canonicalInstant(snapshot.effectiveAt());
    }

    private SourcePolicy uniqueSource(String sourceId) {
        List<SourcePolicy> matches = policy.sources().stream()
                .filter(source -> source.sourceId().equals(sourceId))
                .toList();
        if (matches.size() != 1) throw invalid();
        return matches.getFirst();
    }

    private List<QualityMetricResult> orderedMetrics(
            List<QualityMetricResult> results,
            List<MetricDefinition> definitions,
            SourcePolicy source) {
        Map<String, QualityMetricResult> byFormula = new HashMap<>();
        for (QualityMetricResult result : results) {
            if (byFormula.putIfAbsent(result.formulaId(), result) != null) throw invalid();
        }
        if (byFormula.size() != definitions.size()) throw invalid();
        List<QualityMetricResult> ordered = new ArrayList<>(definitions.size());
        for (MetricDefinition definition : definitions) {
            QualityMetricResult result = byFormula.get(definition.formulaId());
            if (result == null) throw invalid();
            requireMetricBinding(result, definition, source);
            ordered.add(result);
        }
        return List.copyOf(ordered);
    }

    private static void requireMetricBinding(
            QualityMetricResult result,
            MetricDefinition definition,
            SourcePolicy source) {
        if (!result.metricId().equals(definition.metricId())
                || !result.formulaVersion().equals(definition.formulaVersion())
                || !result.unit().wireValue().equals(definition.unit())
                || !result.operator().wireValue().equals(definition.operator())
                || !result.thresholdNumerator().equals(
                        BigInteger.valueOf(definition.thresholdNumerator()))
                || !result.thresholdDenominator().equals(
                        BigInteger.valueOf(definition.thresholdDenominator()))
                || !result.boundary().wireValue().equals(definition.boundary())) {
            throw invalid();
        }
        Boolean expectedApplicability = switch (definition.applicability().predicateId()) {
            case "always" -> true;
            case "source-in-approved-set" ->
                    definition.applicability().sourceIds().contains(source.sourceId());
            case "source-field-group-present" -> !definition.fieldSet().isEmpty()
                    || source.sourceGates().stream().anyMatch(gate ->
                            gate.metricId().equals(definition.metricId())
                                    && !gate.fieldSet().isEmpty());
            case "overlap-records-present" -> null;
            default -> throw invalid();
        };
        if (expectedApplicability != null
                && expectedApplicability.booleanValue() != result.applicable()) {
            throw invalid();
        }
    }

    private static void requireOverall(
            QualitySnapshot snapshot,
            List<QualityMetricResult> metrics) {
        List<QualityMetricResult> applicable = metrics.stream()
                .filter(QualityMetricResult::applicable)
                .toList();
        if (applicable.isEmpty()) throw invalid();
        boolean failed = applicable.stream()
                .anyMatch(metric -> metric.result() == QualityMetricResultStatus.FAILED);
        QualityOverallResult expected = failed
                ? QualityOverallResult.QUALITY_FAILED
                : QualityOverallResult.QUALITY_PASSED;
        DataBatchStatus expectedStatus = failed
                ? DataBatchStatus.QUALITY_FAILED
                : DataBatchStatus.QUALITY_PASSED;
        if (snapshot.overallResult() != expected
                || snapshot.assessedBatchStatus() != expectedStatus) {
            throw invalid();
        }
    }

    private static Map<String, Object> metricValue(QualityMetricResult metric) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("metricId", metric.metricId());
        value.put("formulaId", metric.formulaId());
        value.put("formulaVersion", metric.formulaVersion());
        value.put("result", metric.result().wireValue());
        value.put("applicable", metric.applicable());
        value.put("numerator", metric.numerator());
        value.put("denominator", metric.denominator());
        value.put("valueBasisPoints", metric.valueBasisPoints());
        value.put("unit", metric.unit().wireValue());
        value.put("operator", metric.operator().wireValue());
        value.put("thresholdNumerator", metric.thresholdNumerator());
        value.put("thresholdDenominator", metric.thresholdDenominator());
        value.put("boundary", metric.boundary().wireValue());
        value.put("reasonCode", metric.reasonCode());
        return value;
    }

    private static String canonicalInstant(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) throw invalid();
        int year = value.atZone(ZoneOffset.UTC).getYear();
        if (year < 1 || year > 9_999) throw invalid();
        return UTC_MICROSECONDS.format(value);
    }

    private static String nullableUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static IngestionQualityException invalid() {
        return IngestionQualityDomainRules.invalid();
    }
}
