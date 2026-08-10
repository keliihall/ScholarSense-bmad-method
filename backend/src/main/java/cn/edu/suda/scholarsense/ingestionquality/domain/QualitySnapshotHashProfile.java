package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable runtime projection of the approved QSHM-1.0.0 profile. */
public record QualitySnapshotHashProfile(
        String hashProfileVersion,
        String addendumId,
        String decisionId,
        String authorityRef,
        String approvalRef,
        Instant approvedAt,
        Instant effectiveAt,
        String status,
        String domainTag,
        String canonicalizationProfile,
        String algorithm,
        String digestPrefix,
        List<String> includedTopLevelFields,
        List<String> excludedSnapshotFields,
        List<String> metricResultFields,
        MetricOrdering metricOrdering,
        ImpactScopeOrdering impactScopeOrdering,
        List<String> nullableMaterialFields,
        TimeCanonicalization timeCanonicalization,
        StringEscaping stringEscaping,
        OperandClosure operandClosure,
        List<ControlledInput> controlledInputs) {

    public QualitySnapshotHashProfile {
        hashProfileVersion = required(hashProfileVersion);
        addendumId = required(addendumId);
        decisionId = required(decisionId);
        authorityRef = required(authorityRef);
        approvalRef = required(approvalRef);
        Objects.requireNonNull(approvedAt);
        Objects.requireNonNull(effectiveAt);
        status = required(status);
        domainTag = required(domainTag);
        canonicalizationProfile = required(canonicalizationProfile);
        algorithm = required(algorithm);
        digestPrefix = required(digestPrefix);
        includedTopLevelFields = List.copyOf(includedTopLevelFields);
        excludedSnapshotFields = List.copyOf(excludedSnapshotFields);
        metricResultFields = List.copyOf(metricResultFields);
        Objects.requireNonNull(metricOrdering);
        Objects.requireNonNull(impactScopeOrdering);
        nullableMaterialFields = List.copyOf(nullableMaterialFields);
        Objects.requireNonNull(timeCanonicalization);
        Objects.requireNonNull(stringEscaping);
        Objects.requireNonNull(operandClosure);
        controlledInputs = List.copyOf(controlledInputs);
    }

    public record MetricOrdering(String common, String sourceGates, String formulaCardinality) {
        public MetricOrdering {
            common = required(common);
            sourceGates = required(sourceGates);
            formulaCardinality = required(formulaCardinality);
        }
    }

    public record ImpactScopeOrdering(String order, String duplicates, String empty) {
        public ImpactScopeOrdering {
            order = required(order);
            duplicates = required(duplicates);
            empty = Objects.requireNonNull(empty);
        }
    }

    public record TimeCanonicalization(
            String semanticType,
            String timezone,
            String precision,
            String format,
            String subMicrosecond) {
        public TimeCanonicalization {
            semanticType = required(semanticType);
            timezone = required(timezone);
            precision = required(precision);
            format = required(format);
            subMicrosecond = required(subMicrosecond);
        }
    }

    public record StringEscaping(
            String strategy,
            String quotationMark,
            String reverseSolidus,
            String backspace,
            String formFeed,
            String lineFeed,
            String carriageReturn,
            String tab,
            String otherControls,
            String solidus,
            String nonAscii,
            boolean unicodeScalarsOnly) {
        public StringEscaping {
            strategy = required(strategy);
            quotationMark = required(quotationMark);
            reverseSolidus = required(reverseSolidus);
            backspace = required(backspace);
            formFeed = required(formFeed);
            lineFeed = required(lineFeed);
            carriageReturn = required(carriageReturn);
            tab = required(tab);
            otherControls = required(otherControls);
            solidus = required(solidus);
            nonAscii = required(nonAscii);
        }
    }

    public record OperandClosure(
            boolean independentField,
            String ratio,
            String count,
            String duration,
            String compositeAnd,
            String notApplicable) {
        public OperandClosure {
            ratio = required(ratio);
            count = required(count);
            duration = required(duration);
            compositeAnd = required(compositeAnd);
            notApplicable = required(notApplicable);
        }
    }

    /** Canonical digest is absent only for the approved raw Markdown authority input. */
    public record ControlledInput(String path, String rawDigest, String canonicalDigest) {
        public ControlledInput {
            path = required(path);
            rawDigest = required(rawDigest);
            if (canonicalDigest != null) canonicalDigest = required(canonicalDigest);
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("quality hash profile value is required");
        }
        return value;
    }
}
