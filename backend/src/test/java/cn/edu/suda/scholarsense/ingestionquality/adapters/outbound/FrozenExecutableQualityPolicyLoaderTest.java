package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogPolicy;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

class FrozenExecutableQualityPolicyLoaderTest {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Path POLICY = Path.of(
            "contracts/ingestion-quality/batch-quality/executable-quality-policy-1.0.0.json");
    private static final Path POLICY_LOCK = Path.of(
            "contracts/ingestion-quality/batch-quality/executable-quality-contract-lock-1.0.0.json");
    private static final Path HASH_PROFILE = Path.of(
            "contracts/ingestion-quality/batch-quality/quality-snapshot-hash-profile-1.0.0.json");
    private static final Path HASH_LOCK = Path.of(
            "contracts/ingestion-quality/batch-quality/quality-snapshot-hash-contract-lock-1.0.0.json");
    private static final List<Path> RUNTIME_CONTRACTS =
            List.of(POLICY, POLICY_LOCK, HASH_PROFILE, HASH_LOCK);
    private static final List<Path> COMPLETE_CHAIN = completeChain();
    private static final List<Path> REFERENCED_UPSTREAMS = COMPLETE_CHAIN.stream()
            .filter(path -> !RUNTIME_CONTRACTS.contains(path))
            .toList();

    @TempDir
    Path temporary;

    @Test
    void loadsTheExactQmdpAndQshmChainIntoAnIndependentClosedTypedContract() {
        var verified = new FrozenExecutableQualityPolicyLoader(REPOSITORY).loadVerified();
        var policy = verified.policy();
        var hashProfile = verified.hashProfile();
        var attestation = verified.attestation();

        assertEquals("QMDP-1.0.0", policy.profileVersion());
        assertEquals("AUTH-2026-08-08-001", policy.authorityRef());
        assertEquals("AUTH-2026-08-08-001", policy.approvalRef());
        assertEquals(Instant.parse("2026-08-09T02:02:22Z"), policy.effectiveAt());
        assertEquals(12, policy.commonMetrics().size());
        assertEquals(17, policy.sources().size());
        assertEquals(30, policy.sources().stream()
                .flatMap(source -> source.sourceGates().stream()).count());
        assertEquals(31, policy.sources().stream()
                .flatMap(source -> source.freshnessLanes().stream()).count());

        var firstMetric = policy.commonMetrics().getFirst();
        assertEquals("PRIMARY_KEY_COMPLETENESS_BP", firstMetric.metricId());
        assertEquals("ratio", firstMetric.calculation().kind());
        assertEquals("measured", firstMetric.calculation().numerator().kind());
        assertEquals("manifest-records-with-complete-valid-business-key",
                firstMetric.calculation().numerator().operandId());
        assertEquals(9_950L, firstMetric.thresholdNumerator());
        assertEquals(10_000L, firstMetric.thresholdDenominator());
        assertEquals("always", firstMetric.applicability().predicateId());

        var firstSource = policy.sources().getFirst();
        assertEquals("SRC-P0-STUDENT-001", firstSource.sourceId());
        assertEquals("STUDENT-SLICE-1.0.0", firstSource.schemaBinding().version());
        assertEquals(12, firstSource.applicableCommonMetricIds().size());
        assertEquals("incremental-record", firstSource.freshnessLanes().getFirst().laneId());
        assertEquals("duration", firstSource.freshnessLanes().getFirst().rule().kind());

        assertEquals("QSHM-1.0.0", hashProfile.hashProfileVersion());
        assertEquals("AUTH-2026-08-09-001", hashProfile.authorityRef());
        assertEquals("AUTH-2026-08-09-001", hashProfile.approvalRef());
        assertEquals(Instant.parse("2026-08-09T11:24:55Z"), hashProfile.effectiveAt());
        assertEquals("scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1",
                hashProfile.domainTag());
        assertEquals(26, hashProfile.includedTopLevelFields().size());
        assertEquals(5, hashProfile.excludedSnapshotFields().size());
        assertEquals(14, hashProfile.metricResultFields().size());
        assertEquals(5, hashProfile.controlledInputs().size());

        var policyHandoff = hashProfile.controlledInputs().stream()
                .filter(input -> input.path().equals(POLICY.toString()))
                .findFirst().orElseThrow();
        assertEquals(attestation.qmdpPolicyRawDigest(), policyHandoff.rawDigest());
        assertEquals(attestation.qmdpPolicyCanonicalDigest(), policyHandoff.canonicalDigest());
        var lockHandoff = hashProfile.controlledInputs().stream()
                .filter(input -> input.path().equals(POLICY_LOCK.toString()))
                .findFirst().orElseThrow();
        assertEquals(attestation.qmdpContractLockRawDigest(), lockHandoff.rawDigest());
        assertEquals(attestation.qmdpContractLockCanonicalDigest(), lockHandoff.canonicalDigest());

        assertFalse(FrozenDataCatalogPolicy.class.isAssignableFrom(policy.getClass()));
        assertNotEquals(FrozenDataCatalogPolicy.class, policy.getClass());
        assertFalse(Map.class.isAssignableFrom(policy.commonMetrics().getFirst().getClass()));
        assertFalse(Map.class.isAssignableFrom(policy.sources().getFirst().getClass()));
        assertFalse(Map.class.isAssignableFrom(hashProfile.controlledInputs().getFirst().getClass()));
        assertThrows(UnsupportedOperationException.class,
                () -> policy.commonMetrics().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> policy.sources().getFirst().sourceGates().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> policy.sources().getFirst().freshnessLanes().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> hashProfile.includedTopLevelFields().clear());
    }

    @Test
    void attestsExactRawAndCanonicalDigestsVersionsAndApprovalEvidence() {
        var attestation = new FrozenExecutableQualityPolicyLoader(REPOSITORY)
                .loadVerified().attestation();

        assertEquals("QMDP-1.0.0", attestation.qmdpProfileVersion());
        assertEquals("sha256:1e7703748a7bda56034ab189700a82a503674d364ecac67c1eb35fe6ee8c0d84",
                attestation.qmdpPolicyRawDigest());
        assertEquals("sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8",
                attestation.qmdpPolicyCanonicalDigest());
        assertEquals("EXECUTABLE-QUALITY-CONTRACT-LOCK-1.0.0",
                attestation.qmdpContractLockVersion());
        assertEquals("sha256:b93d5547e6b28aa7281f736bd2440800eea590987d68ae8ab28ffd6a225cdb6f",
                attestation.qmdpContractLockRawDigest());
        assertEquals("sha256:386f02acbdb9310fbe93e155e021023154005b2e9e01673f93c2ecdc881f8fce",
                attestation.qmdpContractLockCanonicalDigest());
        assertEquals("AUTH-2026-08-08-001", attestation.qmdpAuthorityRef());
        assertEquals("AUTH-2026-08-08-001", attestation.qmdpApprovalRef());
        assertEquals(Instant.parse("2026-08-09T02:02:22Z"), attestation.qmdpEffectiveAt());

        assertEquals("QSHM-1.0.0", attestation.qshmProfileVersion());
        assertEquals("sha256:2389902346fa1cef377bba7d8d575b03ef41e7f0614262b540df2e494f7fb882",
                attestation.qshmProfileRawDigest());
        assertEquals("sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2",
                attestation.qshmProfileCanonicalDigest());
        assertEquals("QSHM-CONTRACT-LOCK-1.0.0", attestation.qshmContractLockVersion());
        assertEquals("sha256:95eeb36ad905079eabf3f83addb40c335e0d9c862c4c582a5981e21b2379dc82",
                attestation.qshmContractLockRawDigest());
        assertEquals("AUTH-2026-08-09-001", attestation.qshmAuthorityRef());
        assertEquals("AUTH-2026-08-09-001", attestation.qshmApprovalRef());
        assertEquals(Instant.parse("2026-08-09T11:24:55Z"), attestation.qshmEffectiveAt());
    }

    @ParameterizedTest(name = "missing controlled runtime artifact is rejected: {0}")
    @MethodSource("runtimeContracts")
    void rejectsEveryMissingControlledRuntimeArtifact(Path missing) throws Exception {
        Path repository = contractCopy("missing-" + missing.getFileName());
        Files.delete(repository.resolve(missing));

        assertContractInvalid(() ->
                new FrozenExecutableQualityPolicyLoader(repository).loadVerified());
    }

    @ParameterizedTest(name = "raw-byte drift is rejected: {0}")
    @MethodSource("runtimeContracts")
    void rejectsRawByteDriftOfEveryControlledRuntimeArtifact(Path drifted) throws Exception {
        Path repository = contractCopy("raw-drift-" + drifted.getFileName());
        Files.writeString(repository.resolve(drifted), "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        assertContractInvalid(() ->
                new FrozenExecutableQualityPolicyLoader(repository).loadVerified());
    }

    @ParameterizedTest(name = "referenced upstream must exist and retain exact bytes: {0}")
    @MethodSource("referencedUpstreams")
    void rejectsEveryMissingOrDriftedReferencedUpstreamArtifact(Path upstream) throws Exception {
        Path missingRepository = contractCopy("upstream-missing-" + upstream.getFileName());
        Files.delete(missingRepository.resolve(upstream));
        assertContractInvalid(() ->
                new FrozenExecutableQualityPolicyLoader(missingRepository).loadVerified());

        Path driftedRepository = contractCopy("upstream-drift-" + upstream.getFileName());
        Files.writeString(driftedRepository.resolve(upstream), "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        assertContractInvalid(() ->
                new FrozenExecutableQualityPolicyLoader(driftedRepository).loadVerified());
    }

    @Test
    void rejectsUnknownOrMissingPolicyMembersAndAnyPathVersionOrCanonicalDigestDrift()
            throws Exception {
        List<PolicyMutation> mutations = List.of(
                new PolicyMutation("unknown-member", POLICY,
                        value -> value.replaceFirst("\\{", "{\n  \"unknown\": true,")),
                new PolicyMutation("missing-common-metrics", POLICY,
                        value -> value.replace("\"commonMetrics\":", "\"missingCommonMetrics\":")),
                new PolicyMutation("controlled-input-path", POLICY,
                        value -> value.replace(
                                "contracts/data-catalog/dcc-1.1.0.json",
                                "../data-catalog/dcc-1.1.0.json")),
                new PolicyMutation("unknown-policy-version", POLICY,
                        value -> value.replace("QMDP-1.0.0", "QMDP-9.9.9")),
                new PolicyMutation("policy-canonical-digest", POLICY_LOCK,
                        value -> value.replace(
                                "sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8",
                                "sha256:" + "0".repeat(64))),
                new PolicyMutation("unknown-lock-version", POLICY_LOCK,
                        value -> value.replace(
                                "EXECUTABLE-QUALITY-CONTRACT-LOCK-1.0.0",
                                "EXECUTABLE-QUALITY-CONTRACT-LOCK-9.9.9")),
                new PolicyMutation("qshm-policy-handoff", HASH_PROFILE,
                        value -> value.replace(
                                "1e7703748a7bda56034ab189700a82a503674d364ecac67c1eb35fe6ee8c0d84",
                                "0".repeat(64))),
                new PolicyMutation("qshm-profile-version", HASH_PROFILE,
                        value -> value.replace("QSHM-1.0.0", "QSHM-9.9.9")),
                new PolicyMutation("qshm-profile-canonical-digest", HASH_LOCK,
                        value -> value.replace(
                                "sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2",
                                "sha256:" + "f".repeat(64))),
                new PolicyMutation("qshm-upstream-path", HASH_LOCK,
                        value -> value.replace(
                                POLICY.toString(),
                                "contracts/ingestion-quality/batch-quality/unknown-policy.json")));

        for (PolicyMutation mutation : mutations) {
            Path repository = contractCopy(mutation.name());
            mutate(repository.resolve(mutation.path()), mutation.mutation());

            assertContractInvalid(
                    () -> new FrozenExecutableQualityPolicyLoader(repository).loadVerified(),
                    mutation.name());
        }
    }

    @ParameterizedTest(name = "strict controlled JSON rejects {0}")
    @MethodSource("invalidStrictJson")
    void strictControlledJsonRejectsDuplicateBomFloatNegativeZeroUnsafeIntegerAndLoneSurrogate(
            StrictJsonCase invalid) {
        assertContractInvalid(() -> StrictQualityContractJson.parse(invalid.bytes()), invalid.name());
    }

    @Test
    void strictControlledJsonAcceptsSafeIntegersValidSurrogatePairsAndDirectUtf8Scalars() {
        byte[] bytes = ("{\"z\":\"中文/😀\",\"safe\":9007199254740991,"
                + "\"escapedPair\":\"\\uD83D\\uDE00\"}").getBytes(StandardCharsets.UTF_8);

        JsonNode parsed = StrictQualityContractJson.parse(bytes);

        assertEquals(9_007_199_254_740_991L, parsed.required("safe").longValue());
        assertEquals("中文/😀", parsed.required("z").textValue());
        assertEquals("😀", parsed.required("escapedPair").textValue());
    }

    private Path contractCopy(String name) throws IOException {
        Path copy = temporary.resolve(name);
        for (Path relative : COMPLETE_CHAIN) {
            Path destination = copy.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(REPOSITORY.resolve(relative), destination,
                    StandardCopyOption.COPY_ATTRIBUTES);
        }
        return copy;
    }

    private static void mutate(Path path, UnaryOperator<String> mutation) throws IOException {
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String changed = mutation.apply(original);
        assertNotEquals(original, changed, "test mutation must change controlled bytes");
        Files.writeString(path, changed, StandardCharsets.UTF_8);
    }

    private static Stream<Path> runtimeContracts() {
        return RUNTIME_CONTRACTS.stream();
    }

    private static Stream<Path> referencedUpstreams() {
        return REFERENCED_UPSTREAMS.stream();
    }

    private static List<Path> completeChain() {
        try {
            LinkedHashSet<Path> result = new LinkedHashSet<>(RUNTIME_CONTRACTS);
            JsonNode policyLock = StrictQualityContractJson.parse(
                    Files.readAllBytes(REPOSITORY.resolve(POLICY_LOCK)));
            for (String path : policyLock.required("digests").propertyNames()) {
                result.add(Path.of(path));
            }
            JsonNode hashLock = StrictQualityContractJson.parse(
                    Files.readAllBytes(REPOSITORY.resolve(HASH_LOCK)));
            hashLock.required("digests").forEach(value ->
                    result.add(Path.of(value.required("path").textValue())));
            JsonNode hashProfile = StrictQualityContractJson.parse(
                    Files.readAllBytes(REPOSITORY.resolve(HASH_PROFILE)));
            hashProfile.required("controlledInputs").forEach(value ->
                    result.add(Path.of(value.required("path").textValue())));
            return List.copyOf(result);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Stream<StrictJsonCase> invalidStrictJson() {
        byte[] bom = new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf,
                (byte) '{', (byte) '}', (byte) '\n'};
        return Stream.of(
                strict("duplicate-key", "{\"a\":1,\"a\":2}"),
                new StrictJsonCase("utf8-bom", bom),
                strict("decimal", "{\"a\":1.0}"),
                strict("exponent", "{\"a\":1e2}"),
                strict("negative-zero", "{\"a\":-0}"),
                strict("unsafe-positive-integer", "{\"a\":9007199254740992}"),
                strict("unsafe-negative-integer", "{\"a\":-9007199254740992}"),
                strict("escaped-lone-high-surrogate", "{\"a\":\"\\uD800\"}"),
                strict("escaped-lone-low-surrogate", "{\"a\":\"\\uDC00\"}"));
    }

    private static StrictJsonCase strict(String name, String json) {
        return new StrictJsonCase(name, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertContractInvalid(org.junit.jupiter.api.function.Executable executable) {
        assertContractInvalid(executable, "controlled contract must fail closed");
    }

    private static void assertContractInvalid(
            org.junit.jupiter.api.function.Executable executable, String message) {
        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class, executable, message);
        assertEquals("INGESTION_QUALITY_CONTRACT_INVALID", failure.code(), message);
    }

    private record PolicyMutation(
            String name, Path path, UnaryOperator<String> mutation) {}

    private record StrictJsonCase(String name, byte[] bytes) {
        @Override public String toString() { return name; }
    }
}
