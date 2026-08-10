package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Objects;

public record NormalizedFact(NormalizedFactIdentity identity, String contentDigest) {
    public NormalizedFact {
        Objects.requireNonNull(identity);
        contentDigest = IngestionQualityDomainRules.requireSha256(contentDigest);
    }
}
