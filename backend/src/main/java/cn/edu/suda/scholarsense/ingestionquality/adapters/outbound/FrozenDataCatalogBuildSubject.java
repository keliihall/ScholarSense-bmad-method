package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/** Reads commit/tree from the build-filtered classpath resource, never from runtime environment. */
public record FrozenDataCatalogBuildSubject(String candidateCommit, String candidateTree) {
    private static final String RESOURCE = "ingestion-quality-runtime/catalog-build-subject.properties";

    public static FrozenDataCatalogBuildSubject load(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        Properties values = new Properties();
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("INGESTION_QUALITY_BUILD_SUBJECT_MISSING");
            }
            values.load(input);
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_BUILD_SUBJECT_UNAVAILABLE", unavailable);
        }
        String commit = values.getProperty("candidateCommit", "");
        String tree = values.getProperty("candidateTree", "");
        if (!commit.matches("[0-9a-f]{40}") || !tree.matches("[0-9a-f]{40}")
                || commit.chars().allMatch(value -> value == '0')
                || tree.chars().allMatch(value -> value == '0')) {
            throw new IllegalStateException("INGESTION_QUALITY_BUILD_SUBJECT_UNBOUND");
        }
        return new FrozenDataCatalogBuildSubject(commit, tree);
    }
}
