package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.ExecutableQualityPolicyPort;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityContractAttestation;
import cn.edu.suda.scholarsense.ingestionquality.application.VerifiedQualityContract;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.AdvanceHorizonRule;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.Applicability;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.Calculation;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.Canonicalization;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.ControlledInputs;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.DigestBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.DurationRule;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.FreshnessLane;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.FreshnessManifestBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.FreshnessRule;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.LocalCutoffRule;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.NonMetricConstraint;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.NumericSemantics;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.Operand;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.OverallResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.SourcePolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.WindowSemantics;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotHashProfile;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotHashProfile.ControlledInput;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotHashProfile.ImpactScopeOrdering;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotHashProfile.MetricOrdering;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotHashProfile.OperandClosure;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotHashProfile.StringEscaping;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshotHashProfile.TimeCanonicalization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Loads the exact approved QMDP/QSHM chain and every referenced controlled input. */
public final class FrozenExecutableQualityPolicyLoader implements ExecutableQualityPolicyPort {
    private static final Path POLICY = Path.of(
            "contracts/ingestion-quality/batch-quality/executable-quality-policy-1.0.0.json");
    private static final Path POLICY_LOCK = Path.of(
            "contracts/ingestion-quality/batch-quality/executable-quality-contract-lock-1.0.0.json");
    private static final Path HASH_PROFILE = Path.of(
            "contracts/ingestion-quality/batch-quality/quality-snapshot-hash-profile-1.0.0.json");
    private static final Path HASH_LOCK = Path.of(
            "contracts/ingestion-quality/batch-quality/quality-snapshot-hash-contract-lock-1.0.0.json");

    private static final String POLICY_RAW =
            "sha256:1e7703748a7bda56034ab189700a82a503674d364ecac67c1eb35fe6ee8c0d84";
    private static final String POLICY_CANONICAL =
            "sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8";
    private static final String POLICY_LOCK_RAW =
            "sha256:b93d5547e6b28aa7281f736bd2440800eea590987d68ae8ab28ffd6a225cdb6f";
    private static final String POLICY_LOCK_CANONICAL =
            "sha256:386f02acbdb9310fbe93e155e021023154005b2e9e01673f93c2ecdc881f8fce";
    private static final String HASH_PROFILE_RAW =
            "sha256:2389902346fa1cef377bba7d8d575b03ef41e7f0614262b540df2e494f7fb882";
    private static final String HASH_PROFILE_CANONICAL =
            "sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2";
    private static final String HASH_LOCK_RAW =
            "sha256:95eeb36ad905079eabf3f83addb40c335e0d9c862c4c582a5981e21b2379dc82";

    private static final List<String> COMMON_METRIC_ORDER = List.of(
            "PRIMARY_KEY_COMPLETENESS_BP",
            "P0_SUBJECT_MAPPING_BP",
            "REQUIRED_FIELD_VALIDITY_BP",
            "VALID_RECORD_RATE_BP",
            "CORE_FIELD_COVERAGE_BP",
            "FRESHNESS_WITHIN_SLO_BP",
            "UNRESOLVED_INTERVAL_CONFLICT_COUNT",
            "DUPLICATE_BUSINESS_KEY_COUNT",
            "VERSION_REGRESSION_COUNT",
            "SOURCE_CONTINUITY_GATE",
            "SCHEMA_ALLOWLIST_COMPATIBILITY_BP",
            "FORBIDDEN_FIELD_COUNT");
    private static final List<String> SOURCE_ORDER = List.of(
            "SRC-P0-STUDENT-001",
            "SRC-P0-RESPONSIBILITY-001",
            "SRC-P0-ACCOMMODATION-001",
            "SRC-P0-CARD-001",
            "SRC-P0-CAMPUS-ACCESS-001",
            "SRC-P0-DORM-ACCESS-001",
            "SRC-P0-DEVICE-001",
            "SRC-P0-LEAVE-001",
            "SRC-P0-CALENDAR-001",
            "SRC-P0-TIMETABLE-001",
            "SRC-P1-OFFCAMPUS-001",
            "SRC-P1-NETWORK-001",
            "SRC-P1-ACADEMIC-001",
            "SRC-P1-CARE-LIST-001",
            "SRC-P1-PSYCH-DEID-001",
            "SRC-P1-AID-001",
            "SRC-P1-WORK-VISIT-001");

    private final Path repositoryRoot;

    public FrozenExecutableQualityPolicyLoader(Path repositoryRoot) {
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot)
                .toAbsolutePath().normalize();
    }

    @Override
    public VerifiedQualityContract loadVerified() {
        try {
            Artifact policyArtifact = artifact(POLICY, POLICY_RAW, POLICY_CANONICAL);
            Artifact policyLockArtifact = artifact(
                    POLICY_LOCK, POLICY_LOCK_RAW, POLICY_LOCK_CANONICAL);
            Artifact hashProfileArtifact = artifact(
                    HASH_PROFILE, HASH_PROFILE_RAW, HASH_PROFILE_CANONICAL);
            Artifact hashLockArtifact = artifact(HASH_LOCK, HASH_LOCK_RAW, null);

            ExecutableQualityPolicy policy = policy(policyArtifact.document());
            QualitySnapshotHashProfile hashProfile = hashProfile(hashProfileArtifact.document());
            verifyPolicyLock(policyLockArtifact.document(), policy);
            verifyHashChain(
                    hashProfileArtifact.document(), hashLockArtifact.document(), hashProfile);
            verifyLockedArtifacts(
                    policyLockArtifact.document(), hashLockArtifact.document());
            verifyReferencedChain(policy, hashProfile);

            QualityContractAttestation attestation = new QualityContractAttestation(
                    policy.profileVersion(), POLICY_RAW, POLICY_CANONICAL,
                    text(policyLockArtifact.document(), "lockVersion"),
                    POLICY_LOCK_RAW, POLICY_LOCK_CANONICAL,
                    policy.authorityRef(), policy.approvalRef(), policy.effectiveAt(),
                    hashProfile.hashProfileVersion(), HASH_PROFILE_RAW, HASH_PROFILE_CANONICAL,
                    text(hashLockArtifact.document(), "lockVersion"), HASH_LOCK_RAW,
                    hashProfile.authorityRef(), hashProfile.approvalRef(),
                    hashProfile.effectiveAt());
            return new VerifiedQualityContract(policy, hashProfile, attestation);
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw invalid(failure);
        }
    }

    private Artifact artifact(Path relative, String expectedRaw, String expectedCanonical) {
        try {
            Path path = controlledPath(relative);
            byte[] bytes = Files.readAllBytes(path);
            if (!expectedRaw.equals(StrictQualityContractJson.rawDigest(bytes))) throw invalid();
            JsonNode document = StrictQualityContractJson.parse(bytes);
            if (expectedCanonical != null
                    && !expectedCanonical.equals(
                            StrictQualityContractJson.canonicalDigest(document))) {
                throw invalid();
            }
            return new Artifact(document);
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (IOException failure) {
            throw invalid(failure);
        }
    }

    private Path controlledPath(Path relative) throws IOException {
        if (relative.isAbsolute() || relative.normalize().startsWith("..")) throw invalid();
        if (!Files.isDirectory(repositoryRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(repositoryRoot)) {
            throw invalid();
        }
        Path rootReal = repositoryRoot.toRealPath();
        Path candidate = repositoryRoot.resolve(relative).normalize();
        if (!candidate.startsWith(repositoryRoot)) throw invalid();
        Path cursor = repositoryRoot;
        for (Path segment : repositoryRoot.relativize(candidate)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) throw invalid();
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || !candidate.toRealPath().startsWith(rootReal)) {
            throw invalid();
        }
        return candidate;
    }

    private static ExecutableQualityPolicy policy(JsonNode root) {
        requireExact(root, Set.of(
                "$schema", "profileVersion", "decisionId", "authorityRef", "approvalRef",
                "approvedBy", "approvedAt", "effectiveAt", "owner", "evidenceRef", "status",
                "controlledInputs", "canonicalization", "numericSemantics", "windowSemantics",
                "freshnessManifestBinding", "commonMetrics", "sources", "nonMetricConstraints",
                "overallResult"));

        List<MetricDefinition> commonMetrics = metrics(root.required("commonMetrics"));
        List<SourcePolicy> sources = sources(root.required("sources"));
        if (!commonMetrics.stream().map(MetricDefinition::metricId).toList()
                        .equals(COMMON_METRIC_ORDER)
                || !sources.stream().map(SourcePolicy::sourceId).toList().equals(SOURCE_ORDER)
                || sources.stream().mapToInt(source -> source.sourceGates().size()).sum() != 30
                || sources.stream().mapToInt(source -> source.freshnessLanes().size()).sum() != 31) {
            throw invalid();
        }
        requireUnique(commonMetrics.stream().map(MetricDefinition::metricId).toList());
        requireUnique(sources.stream().map(SourcePolicy::sourceId).toList());

        JsonNode canonical = object(root, "canonicalization", Set.of(
                "profile", "encoding", "objectKeyOrder", "duplicateKeys", "binaryFloat",
                "time", "digestAlgorithm", "digestPrefix"));
        JsonNode numeric = object(root, "numericSemantics", Set.of(
                "representation", "basisPointScale", "valueScale", "roundingMode",
                "comparisonStage", "zeroDenominator"));
        JsonNode window = object(root, "windowSemantics", Set.of(
                "interval", "storageTimezone", "scheduleTimezone", "freshnessWindowHours",
                "cutoffBoundary"));
        JsonNode manifest = object(root, "freshnessManifestBinding", Set.of(
                "sourceOccurredAt", "scheduledDueAt", "receivedAt", "laneId"));
        JsonNode inputs = object(root, "controlledInputs", Set.of("dataCatalog", "qualityGate"));
        JsonNode overall = object(root, "overallResult", Set.of(
                "operator", "minimumApplicableHardGates", "evaluationErrorResult",
                "failedResult", "passedResult"));

        ExecutableQualityPolicy result = new ExecutableQualityPolicy(
                text(root, "profileVersion"), text(root, "decisionId"),
                text(root, "authorityRef"), text(root, "approvalRef"),
                text(root, "approvedBy"), instant(root, "approvedAt"),
                instant(root, "effectiveAt"), text(root, "owner"), text(root, "evidenceRef"),
                text(root, "status"),
                new Canonicalization(
                        text(canonical, "profile"), text(canonical, "encoding"),
                        text(canonical, "objectKeyOrder"), text(canonical, "duplicateKeys"),
                        text(canonical, "binaryFloat"), text(canonical, "time"),
                        text(canonical, "digestAlgorithm"), text(canonical, "digestPrefix")),
                new NumericSemantics(
                        text(numeric, "representation"), integer(numeric, "basisPointScale"),
                        smallInteger(numeric, "valueScale"), text(numeric, "roundingMode"),
                        text(numeric, "comparisonStage"), text(numeric, "zeroDenominator")),
                new WindowSemantics(
                        text(window, "interval"), text(window, "storageTimezone"),
                        text(window, "scheduleTimezone"), integer(window, "freshnessWindowHours"),
                        text(window, "cutoffBoundary")),
                new FreshnessManifestBinding(
                        text(manifest, "sourceOccurredAt"), text(manifest, "scheduledDueAt"),
                        text(manifest, "receivedAt"), text(manifest, "laneId")),
                new ControlledInputs(
                        digestBinding(inputs.required("dataCatalog")),
                        digestBinding(inputs.required("qualityGate"))),
                commonMetrics, sources, constraints(root.required("nonMetricConstraints")),
                new OverallResult(
                        text(overall, "operator"),
                        smallInteger(overall, "minimumApplicableHardGates"),
                        text(overall, "evaluationErrorResult"), text(overall, "failedResult"),
                        text(overall, "passedResult")));
        verifyFixedPolicyIdentity(result);
        return result;
    }

    private static List<MetricDefinition> metrics(JsonNode array) {
        requireArray(array);
        List<MetricDefinition> result = new ArrayList<>();
        array.forEach(value -> result.add(metric(value)));
        return List.copyOf(result);
    }

    private static MetricDefinition metric(JsonNode value) {
        requireObject(value);
        Set<String> allowed = Set.of(
                "definitionKind", "metricId", "sourceId", "gateId", "formulaId",
                "formulaVersion", "category", "calculation", "unit", "operator", "boundary",
                "thresholdNumerator", "thresholdDenominator", "denominatorZeroBehavior",
                "valueScale", "roundingMode", "comparisonStage", "applicability", "fieldSet",
                "owner", "approvalRef", "effectiveAt", "evidenceRef", "hardGate");
        if (!allowed.containsAll(value.propertyNames())) throw invalid();
        String definitionKind = text(value, "definitionKind");
        String sourceId = optionalText(value, "sourceId");
        String gateId = optionalText(value, "gateId");
        if (("common".equals(definitionKind) && (sourceId != null || gateId != null))
                || ("source-gate".equals(definitionKind)
                        && (sourceId == null || gateId == null))
                || (!"common".equals(definitionKind) && !"source-gate".equals(definitionKind))) {
            throw invalid();
        }
        JsonNode calculation = object(value, "calculation", Set.of("kind", "numerator", "denominator"));
        JsonNode applicability = value.required("applicability");
        requireObject(applicability);
        if (!Set.of("predicateId", "sourceIds").containsAll(applicability.propertyNames())) {
            throw invalid();
        }
        return new MetricDefinition(
                definitionKind, text(value, "metricId"), sourceId, gateId,
                text(value, "formulaId"), text(value, "formulaVersion"),
                text(value, "category"),
                new Calculation(
                        text(calculation, "kind"), operand(calculation.required("numerator")),
                        operand(calculation.required("denominator"))),
                text(value, "unit"), text(value, "operator"), text(value, "boundary"),
                integer(value, "thresholdNumerator"), integer(value, "thresholdDenominator"),
                text(value, "denominatorZeroBehavior"), smallInteger(value, "valueScale"),
                text(value, "roundingMode"), text(value, "comparisonStage"),
                new Applicability(
                        text(applicability, "predicateId"),
                        optionalStrings(applicability, "sourceIds")),
                optionalStrings(value, "fieldSet"), text(value, "owner"),
                text(value, "approvalRef"), instant(value, "effectiveAt"),
                text(value, "evidenceRef"), bool(value, "hardGate"));
    }

    private static Operand operand(JsonNode value) {
        requireObject(value);
        String kind = text(value, "kind");
        if ("measured".equals(kind)) {
            requireExact(value, Set.of("kind", "operandId"));
            return Operand.measured(text(value, "operandId"));
        }
        if ("constant".equals(kind)) {
            requireExact(value, Set.of("kind", "value"));
            return Operand.constant(integer(value, "value"));
        }
        throw invalid();
    }

    private static List<SourcePolicy> sources(JsonNode array) {
        requireArray(array);
        List<SourcePolicy> result = new ArrayList<>();
        array.forEach(value -> {
            requireExact(value, Set.of(
                    "sourceId", "owner", "schemaBinding", "applicableCommonMetricIds",
                    "sourceGates", "freshnessLanes"));
            List<MetricDefinition> gates = metrics(value.required("sourceGates"));
            String sourceId = text(value, "sourceId");
            if (gates.stream().anyMatch(gate -> !sourceId.equals(gate.sourceId()))) throw invalid();
            List<String> applicable = strings(value.required("applicableCommonMetricIds"));
            requireUnique(applicable);
            if (!COMMON_METRIC_ORDER.containsAll(applicable)) throw invalid();
            result.add(new SourcePolicy(
                    sourceId, text(value, "owner"), digestBinding(value.required("schemaBinding")),
                    applicable, gates, freshnessLanes(value.required("freshnessLanes"))));
        });
        return List.copyOf(result);
    }

    private static List<FreshnessLane> freshnessLanes(JsonNode array) {
        requireArray(array);
        List<FreshnessLane> result = new ArrayList<>();
        array.forEach(value -> {
            requireExact(value, Set.of(
                    "laneId", "unit", "timezone", "inclusive", "allowNoActivity", "rule"));
            result.add(new FreshnessLane(
                    text(value, "laneId"), text(value, "unit"), text(value, "timezone"),
                    bool(value, "inclusive"), bool(value, "allowNoActivity"),
                    freshnessRule(value.required("rule"))));
        });
        requireUnique(result.stream().map(FreshnessLane::laneId).toList());
        return List.copyOf(result);
    }

    private static FreshnessRule freshnessRule(JsonNode value) {
        String kind = text(value, "kind");
        return switch (kind) {
            case "duration" -> {
                requireExact(value, Set.of(
                        "kind", "laterField", "earlierField", "maxDurationMilliseconds"));
                yield new DurationRule(
                        kind, text(value, "laterField"), text(value, "earlierField"),
                        integer(value, "maxDurationMilliseconds"));
            }
            case "local-cutoff" -> {
                requireExact(value, Set.of(
                        "kind", "laterField", "earlierField", "dueLocalTime", "dayOffset"));
                yield new LocalCutoffRule(
                        kind, text(value, "laterField"), text(value, "earlierField"),
                        text(value, "dueLocalTime"), smallInteger(value, "dayOffset"));
            }
            case "advance-horizon" -> {
                requireExact(value, Set.of(
                        "kind", "laterField", "earlierField", "minimumLeadMilliseconds"));
                yield new AdvanceHorizonRule(
                        kind, text(value, "laterField"), text(value, "earlierField"),
                        integer(value, "minimumLeadMilliseconds"));
            }
            default -> throw invalid();
        };
    }

    private static List<NonMetricConstraint> constraints(JsonNode array) {
        requireArray(array);
        List<NonMetricConstraint> result = new ArrayList<>();
        array.forEach(value -> {
            requireExact(value, Set.of(
                    "constraintId", "kind", "sourceId", "deniedConsumerPurpose"));
            result.add(new NonMetricConstraint(
                    text(value, "constraintId"), text(value, "kind"), text(value, "sourceId"),
                    text(value, "deniedConsumerPurpose")));
        });
        return List.copyOf(result);
    }

    private static DigestBinding digestBinding(JsonNode value) {
        requireExact(value, Set.of("path", "version", "rawSha256", "canonicalDigest"));
        return new DigestBinding(
                text(value, "path"), text(value, "version"),
                prefixedDigest(value, "rawSha256"), digest(value, "canonicalDigest"));
    }

    private static QualitySnapshotHashProfile hashProfile(JsonNode root) {
        requireExact(root, Set.of(
                "$schema", "hashProfileVersion", "addendumId", "decisionId", "authorityRef",
                "approvalRef", "approvedAt", "effectiveAt", "status", "domainTag",
                "canonicalizationProfile", "algorithm", "digestPrefix", "includedTopLevelFields",
                "excludedSnapshotFields", "metricResultFields", "metricOrdering",
                "impactScopeOrdering", "nullableMaterialFields", "timeCanonicalization",
                "stringEscaping", "operandClosure", "controlledInputs"));
        JsonNode metricOrdering = object(root, "metricOrdering", Set.of(
                "common", "sourceGates", "formulaCardinality"));
        JsonNode impactOrdering = object(root, "impactScopeOrdering", Set.of(
                "order", "duplicates", "empty"));
        JsonNode time = object(root, "timeCanonicalization", Set.of(
                "semanticType", "timezone", "precision", "format", "subMicrosecond"));
        JsonNode escaping = object(root, "stringEscaping", Set.of(
                "strategy", "quotationMark", "reverseSolidus", "backspace", "formFeed",
                "lineFeed", "carriageReturn", "tab", "otherControls", "solidus", "nonAscii",
                "unicodeScalarsOnly"));
        JsonNode closure = object(root, "operandClosure", Set.of(
                "independentField", "ratio", "count", "duration", "compositeAnd",
                "notApplicable"));
        List<ControlledInput> controlledInputs = hashInputs(root.required("controlledInputs"));
        if (controlledInputs.size() != 5) throw invalid();
        QualitySnapshotHashProfile result = new QualitySnapshotHashProfile(
                text(root, "hashProfileVersion"), text(root, "addendumId"),
                text(root, "decisionId"), text(root, "authorityRef"),
                text(root, "approvalRef"), instant(root, "approvedAt"),
                instant(root, "effectiveAt"), text(root, "status"), text(root, "domainTag"),
                text(root, "canonicalizationProfile"), text(root, "algorithm"),
                text(root, "digestPrefix"), strings(root.required("includedTopLevelFields")),
                strings(root.required("excludedSnapshotFields")),
                strings(root.required("metricResultFields")),
                new MetricOrdering(
                        text(metricOrdering, "common"), text(metricOrdering, "sourceGates"),
                        text(metricOrdering, "formulaCardinality")),
                new ImpactScopeOrdering(
                        text(impactOrdering, "order"), text(impactOrdering, "duplicates"),
                        text(impactOrdering, "empty")),
                strings(root.required("nullableMaterialFields")),
                new TimeCanonicalization(
                        text(time, "semanticType"), text(time, "timezone"),
                        text(time, "precision"), text(time, "format"),
                        text(time, "subMicrosecond")),
                new StringEscaping(
                        text(escaping, "strategy"), text(escaping, "quotationMark"),
                        text(escaping, "reverseSolidus"), text(escaping, "backspace"),
                        text(escaping, "formFeed"), text(escaping, "lineFeed"),
                        text(escaping, "carriageReturn"), text(escaping, "tab"),
                        text(escaping, "otherControls"), text(escaping, "solidus"),
                        text(escaping, "nonAscii"), bool(escaping, "unicodeScalarsOnly")),
                new OperandClosure(
                        bool(closure, "independentField"), text(closure, "ratio"),
                        text(closure, "count"), text(closure, "duration"),
                        text(closure, "compositeAnd"), text(closure, "notApplicable")),
                controlledInputs);
        verifyFixedHashIdentity(result);
        return result;
    }

    private static List<ControlledInput> hashInputs(JsonNode array) {
        requireArray(array);
        List<ControlledInput> result = new ArrayList<>();
        array.forEach(value -> {
            requireObject(value);
            Set<String> expected = value.has("canonicalDigest")
                    ? Set.of("path", "rawSha256", "canonicalDigest")
                    : Set.of("path", "rawSha256");
            requireExact(value, expected);
            result.add(new ControlledInput(
                    text(value, "path"), prefixedDigest(value, "rawSha256"),
                    value.has("canonicalDigest") ? digest(value, "canonicalDigest") : null));
        });
        requireUnique(result.stream().map(ControlledInput::path).toList());
        return List.copyOf(result);
    }

    private static void verifyPolicyLock(JsonNode lock, ExecutableQualityPolicy policy) {
        requireExact(lock, Set.of(
                "$schema", "lockVersion", "profileVersion", "catalogVersion",
                "qualityGateVersion", "policyCanonicalDigest", "lifecycleCanonicalDigest",
                "retentionCanonicalDigest", "digests"));
        JsonNode digests = lock.required("digests");
        requireObject(digests);
        if (!"EXECUTABLE-QUALITY-CONTRACT-LOCK-1.0.0".equals(text(lock, "lockVersion"))
                || !policy.profileVersion().equals(text(lock, "profileVersion"))
                || !policy.controlledInputs().dataCatalog().version()
                        .equals(text(lock, "catalogVersion"))
                || !policy.controlledInputs().qualityGate().version()
                        .equals(text(lock, "qualityGateVersion"))
                || !POLICY_CANONICAL.equals(digest(lock, "policyCanonicalDigest"))
                || !POLICY_RAW.equals(digest(digests, POLICY.toString()))
                || !policy.controlledInputs().dataCatalog().rawDigest().equals(
                        digest(digests, policy.controlledInputs().dataCatalog().path()))
                || !policy.controlledInputs().qualityGate().rawDigest().equals(
                        digest(digests, policy.controlledInputs().qualityGate().path()))
                || digests.size() != 48) {
            throw invalid();
        }
        for (SourcePolicy source : policy.sources()) {
            DigestBinding schema = source.schemaBinding();
            if (!schema.rawDigest().equals(digest(digests, schema.path()))) {
                throw invalid();
            }
        }
    }

    /**
     * Verifies the physical bytes behind every reference used by the executable policy and hash
     * profile. The wrapper locks alone are not sufficient: a deployment with a missing or drifted
     * DCC, quality gate, source schema, projection, canonicalization profile, or approval record
     * must fail both startup and final revalidation.
     */
    private void verifyReferencedChain(
            ExecutableQualityPolicy policy, QualitySnapshotHashProfile hashProfile) {
        verifyReference(policy.controlledInputs().dataCatalog());
        verifyReference(policy.controlledInputs().qualityGate());
        policy.sources().forEach(source -> verifyReference(source.schemaBinding()));
        hashProfile.controlledInputs().forEach(this::verifyReference);
    }

    private void verifyLockedArtifacts(JsonNode policyLock, JsonNode hashLock) {
        JsonNode policyDigests = policyLock.required("digests");
        requireObject(policyDigests);
        for (String path : policyDigests.propertyNames()) {
            verifyRawReference(path, digest(policyDigests, path));
        }

        Map<String, JsonNode> hashDigests = indexed(hashLock.required("digests"), "path");
        hashDigests.forEach((path, binding) ->
                verifyRawReference(path, digest(binding, "rawDigest")));
    }

    private void verifyReference(DigestBinding binding) {
        verifyReference(binding.path(), binding.rawDigest(), binding.canonicalDigest());
    }

    private void verifyReference(ControlledInput input) {
        verifyReference(input.path(), input.rawDigest(), input.canonicalDigest());
    }

    private void verifyReference(
            String relativePath, String expectedRaw, String expectedCanonical) {
        try {
            byte[] bytes = Files.readAllBytes(controlledPath(Path.of(relativePath)));
            if (!expectedRaw.equals(StrictQualityContractJson.rawDigest(bytes))) {
                throw invalid();
            }
            if (expectedCanonical != null
                    && !expectedCanonical.equals(StrictQualityContractJson.canonicalDigest(
                            StrictQualityContractJson.parse(bytes)))) {
                throw invalid();
            }
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (IOException failure) {
            throw invalid(failure);
        }
    }

    private void verifyRawReference(String relativePath, String expectedRaw) {
        try {
            byte[] bytes = Files.readAllBytes(controlledPath(Path.of(relativePath)));
            if (!expectedRaw.equals(StrictQualityContractJson.rawDigest(bytes))) {
                throw invalid();
            }
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (IOException failure) {
            throw invalid(failure);
        }
    }

    private static void verifyHashChain(
            JsonNode profileDocument,
            JsonNode lock,
            QualitySnapshotHashProfile profile) {
        requireExact(lock, Set.of(
                "$schema", "lockVersion", "hashProfileVersion", "authorityRef",
                "profileCanonicalDigest", "digests", "upstreamBindings"));
        if (!"QSHM-CONTRACT-LOCK-1.0.0".equals(text(lock, "lockVersion"))
                || !profile.hashProfileVersion().equals(text(lock, "hashProfileVersion"))
                || !profile.authorityRef().equals(text(lock, "authorityRef"))
                || !HASH_PROFILE_CANONICAL.equals(digest(lock, "profileCanonicalDigest"))) {
            throw invalid();
        }
        Map<String, JsonNode> inner = indexed(lock.required("digests"), "path");
        JsonNode storedProfile = inner.get(HASH_PROFILE.toString());
        if (inner.size() != 8 || storedProfile == null
                || !HASH_PROFILE_RAW.equals(digest(storedProfile, "rawDigest"))) {
            throw invalid();
        }
        Map<String, JsonNode> upstream = indexed(lock.required("upstreamBindings"), "path");
        if (upstream.size() != 5 || profile.controlledInputs().size() != 5) throw invalid();
        for (ControlledInput input : profile.controlledInputs()) {
            JsonNode binding = upstream.get(input.path());
            if (binding == null
                    || !input.rawDigest().equals(prefixedDigest(binding, "rawSha256"))
                    || !Objects.equals(
                            input.canonicalDigest(),
                            binding.has("canonicalDigest")
                                    ? digest(binding, "canonicalDigest") : null)) {
                throw invalid();
            }
        }
        ControlledInput policy = controlledInput(profile, POLICY);
        ControlledInput policyLock = controlledInput(profile, POLICY_LOCK);
        if (!POLICY_RAW.equals(policy.rawDigest())
                || !POLICY_CANONICAL.equals(policy.canonicalDigest())
                || !POLICY_LOCK_RAW.equals(policyLock.rawDigest())
                || !POLICY_LOCK_CANONICAL.equals(policyLock.canonicalDigest())
                || !HASH_PROFILE_CANONICAL.equals(
                        StrictQualityContractJson.canonicalDigest(profileDocument))) {
            throw invalid();
        }
    }

    private static ControlledInput controlledInput(
            QualitySnapshotHashProfile profile, Path path) {
        return profile.controlledInputs().stream()
                .filter(value -> value.path().equals(path.toString()))
                .findFirst().orElseThrow(FrozenExecutableQualityPolicyLoader::invalid);
    }

    private static void verifyFixedPolicyIdentity(ExecutableQualityPolicy policy) {
        if (!"QMDP-1.0.0".equals(policy.profileVersion())
                || !"approved".equals(policy.status())
                || !"AUTH-2026-08-08-001".equals(policy.authorityRef())
                || !"AUTH-2026-08-08-001".equals(policy.approvalRef())
                || !Instant.parse("2026-08-09T02:02:22Z").equals(policy.effectiveAt())
                || !"DCC-1.1.0".equals(policy.controlledInputs().dataCatalog().version())
                || !"QG-1.0.0".equals(policy.controlledInputs().qualityGate().version())) {
            throw invalid();
        }
    }

    private static void verifyFixedHashIdentity(QualitySnapshotHashProfile profile) {
        if (!"QSHM-1.0.0".equals(profile.hashProfileVersion())
                || !"approved".equals(profile.status())
                || !"AUTH-2026-08-09-001".equals(profile.authorityRef())
                || !"AUTH-2026-08-09-001".equals(profile.approvalRef())
                || !Instant.parse("2026-08-09T11:24:55Z").equals(profile.effectiveAt())
                || !"SCHOLARSENSE-CANONICAL-JSON-1.0.0"
                        .equals(profile.canonicalizationProfile())
                || profile.includedTopLevelFields().size() != 26
                || profile.excludedSnapshotFields().size() != 5
                || profile.metricResultFields().size() != 14) {
            throw invalid();
        }
    }

    private static Map<String, JsonNode> indexed(JsonNode array, String key) {
        requireArray(array);
        Map<String, JsonNode> result = new LinkedHashMap<>();
        array.forEach(value -> {
            requireObject(value);
            if (result.put(text(value, key), value) != null) throw invalid();
        });
        return Map.copyOf(result);
    }

    private static JsonNode object(JsonNode parent, String field, Set<String> fields) {
        JsonNode value = parent.required(field);
        requireExact(value, fields);
        return value;
    }

    private static void requireExact(JsonNode value, Set<String> fields) {
        requireObject(value);
        if (!value.propertyNames().equals(fields)) throw invalid();
    }

    private static void requireObject(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
    }

    private static void requireArray(JsonNode value) {
        if (value == null || !value.isArray()) throw invalid();
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null) return null;
        if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static String digest(JsonNode parent, String field) {
        String value = text(parent, field);
        if (!value.matches("sha256:[0-9a-f]{64}")) throw invalid();
        return value;
    }

    private static String prefixedDigest(JsonNode parent, String field) {
        String value = text(parent, field);
        if (!value.matches("[0-9a-f]{64}")) throw invalid();
        return "sha256:" + value;
    }

    private static long integer(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isIntegralNumber()) throw invalid();
        return value.longValue();
    }

    private static int smallInteger(JsonNode parent, String field) {
        long value = integer(parent, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid();
        return Math.toIntExact(value);
    }

    private static boolean bool(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private static Instant instant(JsonNode parent, String field) {
        try {
            return OffsetDateTime.parse(text(parent, field)).toInstant();
        } catch (DateTimeParseException failure) {
            throw invalid(failure);
        }
    }

    private static List<String> strings(JsonNode array) {
        requireArray(array);
        List<String> result = new ArrayList<>();
        array.forEach(value -> {
            if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
            result.add(value.textValue());
        });
        return List.copyOf(result);
    }

    private static List<String> optionalStrings(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null ? List.of() : strings(value);
    }

    private static void requireUnique(List<String> values) {
        if (new HashSet<>(values).size() != values.size()) throw invalid();
    }

    private static IngestionQualityApplicationException invalid() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_CONTRACT_INVALID");
    }

    private static IngestionQualityApplicationException invalid(Throwable cause) {
        return new IngestionQualityApplicationException(
                "INGESTION_QUALITY_CONTRACT_INVALID", cause);
    }

    private record Artifact(JsonNode document) {}
}
