package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

/** Trusted deployment/build identity to which a signed target report must be bound. */
public record FrozenDataCatalogSubject(
        String authority,
        String environment,
        String candidateCommit,
        String candidateTree,
        long minimumHandoffRevision) {
    private static final String OID = "[0-9a-f]{40}";
    public static final long MAX_HANDOFF_REVISION = 9_007_199_254_740_991L;

    public FrozenDataCatalogSubject {
        if (authority == null || authority.isBlank()
                || !java.util.Set.of("test", "stage", "prod").contains(environment)
                || candidateCommit == null || !candidateCommit.matches(OID)
                || candidateTree == null || !candidateTree.matches(OID)
                || candidateCommit.chars().allMatch(value -> value == '0')
                || candidateTree.chars().allMatch(value -> value == '0')
                || minimumHandoffRevision < 1
                || minimumHandoffRevision > MAX_HANDOFF_REVISION) {
            throw new IllegalArgumentException("INGESTION_QUALITY_EXPECTED_SUBJECT_INVALID");
        }
    }

    public FrozenDataCatalogSubject(
            String authority, String environment,
            String candidateCommit, String candidateTree) {
        this(authority, environment, candidateCommit, candidateTree, 1);
    }

    public static FrozenDataCatalogSubject boundToRuntime(
            String authority,
            String configuredEnvironment,
            String runtimeEnvironment,
            FrozenDataCatalogBuildSubject build,
            long minimumHandoffRevision,
            long releaseMinimumHandoffRevision) {
        if (runtimeEnvironment == null
                || !runtimeEnvironment.equals(configuredEnvironment)) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_TARGET_ENVIRONMENT_MISMATCH");
        }
        if (minimumHandoffRevision != releaseMinimumHandoffRevision) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_HANDOFF_REVISION_FLOOR_MISMATCH");
        }
        return new FrozenDataCatalogSubject(
                authority, runtimeEnvironment,
                build.candidateCommit(), build.candidateTree(), minimumHandoffRevision);
    }
}
