package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityErrorCode;
import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A complete verified 17/17 evidence set with a server-derived digest. */
public record CatalogEvidenceSet(List<CatalogEvidence> items, String digest) {
    public CatalogEvidenceSet {
        items = List.copyOf(items);
        requireDigest(digest);
    }

    public static CatalogEvidenceSet verifiedFor(
            DataSourceCatalog catalog, List<CatalogEvidence> evidence) {
        List<CatalogEvidence> ordered = evidence.stream()
                .sorted(Comparator.comparing(CatalogEvidence::sourceId)).toList();
        if (ordered.size() != 17
                || new HashSet<>(ordered.stream().map(CatalogEvidence::sourceId).toList()).size() != 17
                || catalog.sources().size() != 17) {
            throw invalid();
        }
        Map<String, CatalogEvidence> bySource = ordered.stream().collect(Collectors.toMap(
                CatalogEvidence::sourceId, Function.identity()));
        if (!bySource.keySet().equals(catalog.sources().stream()
                .map(source -> source.sourceId()).collect(Collectors.toSet()))) {
            throw invalid();
        }
        String authority = ordered.getFirst().authority();
        String environment = ordered.getFirst().environment();
        String commit = ordered.getFirst().candidateCommit();
        String tree = ordered.getFirst().candidateTree();
        for (var source : catalog.sources()) {
            CatalogEvidence item = bySource.get(source.sourceId());
            if (!item.schemaVersion().equals(source.schemaVersion())
                    || !item.qualityGateVersion().equals(source.qualityGateVersion())
                    || !item.authority().equals(authority)
                    || !item.environment().equals(environment)
                    || !item.candidateCommit().equals(commit)
                    || !item.candidateTree().equals(tree)) {
                throw invalid();
            }
        }
        return new CatalogEvidenceSet(ordered, serverDigest(ordered));
    }

    private static String serverDigest(List<CatalogEvidence> ordered) {
        StringBuilder canonical = new StringBuilder("DCC-EVIDENCE-SET-1.0.0\0");
        for (CatalogEvidence item : ordered) {
            canonical.append(item.sourceId()).append('\0').append(item.evidenceDigest()).append('\0');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) throw invalid();
    }

    private static IngestionQualityException invalid() {
        return new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
    }
}
